//! mesh hub - device registry, command queue, config store.
//! Same HTTP contract as mesh/hub/server.ts so old and new agents can coexist.
use axum::extract::DefaultBodyLimit;
use axum::{
    extract::{Path, Query, Request, State},
    body::Body,
    http::StatusCode,
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{any, get, post},
    Json, Router,
};
use vibelink_protocol::{now_ms, Command, ConfigInfo, Device, DeviceReport, Event, Health};
use serde_json::{json, Value};
mod approvals;
mod auth;
mod bots;
mod model_gateway;
mod model_usage;
mod agent_sessions;
mod model_policy;
use tower::ServiceExt;
mod policy;
mod tenants;
mod voice;
use std::{
    collections::{BTreeMap, HashMap},
    fs,
    io::Write,
    path::{Path as FsPath, PathBuf},
    sync::{Arc, Mutex},
};

const VERSION: &str = "0.1.0";
const OFFLINE_AFTER_MS: u64 = 20_000;
const EVENT_CAP: usize = 500;

#[derive(Default)]
pub(crate) struct Store {
    pub(crate) devices: HashMap<String, Device>,
    pub(crate) commands: Vec<Command>,
    pub(crate) events: Vec<Event>,
    pub(crate) seq: u64,
}

struct App {
    pub(crate) auth: Arc<auth::Auth>,
    pub(crate) tenants: Arc<tenants::BetaTenantStore>,
    pub(crate) beta_mode: bool,
    pub(crate) bots: bots::BotFleetStore,
    pub(crate) approvals: approvals::ApprovalGate,
    pub(crate) voice: voice::VoiceService,
    data: PathBuf,
    store: Mutex<Store>,
    policy: Mutex<policy::Policy>,
    sessions: Arc<Mutex<SessionPlane>>,
    http: reqwest::Client,
    bindings: agent_sessions::Sessions,
    usage: Arc<model_usage::UsageStore>,
    grants: Arc<model_gateway::Grants>,
    magpie: String,
    tenant_id: Option<String>,
    tenant_apps: Mutex<HashMap<String, Shared>>,
}

/// Requests waiting for an agent, plus the oneshot channels the HTTP side waits on.
#[derive(Default)]
struct SessionPlane {
    queue: HashMap<String, Vec<Value>>,
    waiters: HashMap<String, tokio::sync::oneshot::Sender<Result<Value, String>>>,
    seq: u64,
}

type Shared = Arc<App>;

fn write_atomic(path: &FsPath, data: &[u8]) {
    if let Some(dir) = path.parent() {
        let _ = fs::create_dir_all(dir);
    }
    let tmp = path.with_extension("tmp");
    if let Ok(mut f) = fs::File::create(&tmp) {
        if f.write_all(data).is_ok() {
            let _ = fs::rename(&tmp, path);
        }
    }
}

impl App {
    pub(crate) fn persist(&self, store: &Store) {
        if let Ok(s) = serde_json::to_vec_pretty(&store.devices.values().collect::<Vec<_>>()) {
            write_atomic(&self.data.join("devices.json"), &s);
        }
        let tail: Vec<_> = store.commands.iter().rev().take(500).rev().collect();
        if let Ok(s) = serde_json::to_vec_pretty(&tail) {
            write_atomic(&self.data.join("commands.json"), &s);
        }
        let tail: Vec<_> = store.events.iter().rev().take(EVENT_CAP).rev().collect();
        if let Ok(s) = serde_json::to_vec_pretty(&tail) {
            write_atomic(&self.data.join("events.json"), &s);
        }
    }

    pub(crate) fn event(store: &mut Store, device: Option<String>, kind: &str, message: String) {
        store.events.push(Event {
            at: now_ms(),
            device,
            kind: kind.to_string(),
            message,
        });
        if store.events.len() > EVENT_CAP {
            let drop = store.events.len() - EVENT_CAP;
            store.events.drain(0..drop);
        }
    }

    pub(crate) fn next_id(store: &mut Store) -> String {
        store.seq += 1;
        format!("{:x}{:04x}", now_ms(), store.seq & 0xffff)
    }
}

fn load_policy(data: &PathBuf) -> policy::Policy {
    let file = data.join("policy.json");
    if let Ok(raw) = fs::read_to_string(&file) {
        if let Ok(p) = serde_json::from_str::<policy::Policy>(&raw) {
            return p;
        }
    }
    let p = policy::Policy::default();
    let _ = fs::write(&file, serde_json::to_string_pretty(&p).unwrap_or_default());
    p
}

fn load(data: &PathBuf) -> Store {
    let mut store = Store::default();
    if let Ok(raw) = fs::read_to_string(data.join("devices.json")) {
        if let Ok(list) = serde_json::from_str::<Vec<Device>>(&raw) {
            for d in list {
                store.devices.insert(d.report.id.clone(), d);
            }
        }
    }
    if let Ok(raw) = fs::read_to_string(data.join("commands.json")) {
        if let Ok(list) = serde_json::from_str::<Vec<Command>>(&raw) {
            store.commands = list;
        }
    }
    if let Ok(raw) = fs::read_to_string(data.join("events.json")) {
        if let Ok(list) = serde_json::from_str::<Vec<Event>>(&raw) {
            store.events = list;
        }
    }
    store
}

fn view(mut d: Device) -> Device {
    let age = now_ms().saturating_sub(d.last_seen);
    d.age_ms = age;
    d.online = age < OFFLINE_AFTER_MS;
    d
}

// ---------- auth ----------
async fn auth_mw(State(app): State<Shared>, mut req: Request, next: Next) -> Response {
    let path = req.uri().path().to_string();
    if (path == "/api/device-link" || path.starts_with("/api/device-link/")) && auth::is_blocked_relay_path(&path) {
        return (StatusCode::FORBIDDEN, Json(json!({"error": "local-only relay endpoint"}))).into_response();
    }
    let need = auth::required(req.method().as_str(), &path);
    if need == auth::Need::Public {
        return next.run(req).await;
    }
    let presented = auth::presented_token(req.headers(), req.uri().query());
    if path.starts_with("/api/llm/") {
        if let Some(identity) = presented.as_deref().and_then(|t|app.grants.verify(t)) {
            req.extensions_mut().insert(identity);
            return next.run(req).await;
        }
    }
    let Some(ctx) = presented.as_deref().and_then(|t| app.auth.verify(t)) else {
        return (StatusCode::UNAUTHORIZED, Json(json!({"error": "unauthorized"}))).into_response();
    };
    if !auth::allows(ctx.scope, need) {
        return (
            StatusCode::FORBIDDEN,
            Json(json!({"error": format!("token scope '{}' not allowed here", ctx.scope.as_str())})),
        )
            .into_response();
    }
    let tenant_id = tenants::extract_tenant_id(&ctx.name).map(str::to_string);
    req.extensions_mut().insert(ctx);
    if let Some(tid) = tenant_id {
        if !tenant_route_allowed(&path) { return (StatusCode::FORBIDDEN, Json(json!({"error": "not available to public tenants"}))).into_response(); }
        let tenant_app = {
            let mut cache = app.tenant_apps.lock().unwrap();
            cache.entry(tid.clone()).or_insert_with(|| {
                let dir = app.data.join("tenants").join(&tid);
                Arc::new(App {
                    auth: app.auth.clone(), tenants: app.tenants.clone(), beta_mode: true,
                    bots: bots::BotFleetStore::load_for_edition(dir.clone(), true),
                    approvals: approvals::ApprovalGate::load(dir.clone()), voice: voice::VoiceService::load(dir.clone()),
                    bindings: agent_sessions::Sessions::load(dir.clone()), usage: app.usage.clone(), grants: app.grants.clone(), magpie: app.magpie.clone(), tenant_id: Some(tid.clone()), data: dir.clone(), store: Mutex::new(load(&dir)),
                    policy: Mutex::new(load_policy(&dir)), sessions: app.sessions.clone(),
                    http: app.http.clone(), tenant_apps: Mutex::new(HashMap::new()),
                })
            }).clone()
        };
        // Re-route from a clean request: retaining Axum URL params duplicates path captures.
        let ctx = req.extensions().get::<auth::AuthCtx>().cloned().unwrap();
        req.extensions_mut().clear();
        req.extensions_mut().insert(ctx);
        return api_router(tenant_app).oneshot(req).await.unwrap();
    }
    next.run(req).await
}

fn tenant_route_allowed(path: &str) -> bool {
    path == "/api/bots" || path.starts_with("/api/bots/") || path == "/api/bot-groups" || path.starts_with("/api/bot-groups/")
        || path.starts_with("/api/sessions/") || path.starts_with("/api/session/")
        || path.starts_with("/api/llm/") || path == "/api/usage" || path == "/api/beta/quota" || path == "/api/devices"
}

/// CONNECT 网关的鉴权：X-Mesh-Token 必须是 admin / client 作用域。
fn gateway_token_ok(app: &App, head: &str) -> bool {
    head.lines().skip(1).any(|l| {
        l.split_once(':')
            .filter(|(k, _)| k.trim().eq_ignore_ascii_case("x-mesh-token"))
            .map(|(_, v)| app.auth.gateway_ok(v.trim()))
            .unwrap_or(false)
    })
}

// ---------- tokens / pairing ----------

async fn tokens_list(State(app): State<Shared>) -> Json<Value> {
    Json(json!({ "tokens": app.auth.list() }))
}

async fn tokens_issue(State(app): State<Shared>, Json(body): Json<Value>) -> Response {
    let name = body.get("name").and_then(|v| v.as_str()).unwrap_or("device");
    let Some(scope) = auth::Scope::parse(body.get("scope").and_then(|v| v.as_str()).unwrap_or("client")) else {
        return (StatusCode::BAD_REQUEST, Json(json!({"error": "scope must be client|agent"}))).into_response();
    };
    if scope == auth::Scope::Admin {
        return (StatusCode::BAD_REQUEST, Json(json!({"error": "use rotate-admin for the admin token"}))).into_response();
    }
    let (view, plain) = app.auth.issue(name, scope);
    {
        let mut store = app.store.lock().unwrap();
        App::event(&mut store, None, "auth", format!("签发 token {}（{}，{}）", view.id, view.name, scope.as_str()));
        app.persist(&store);
    }
    Json(json!({ "token": plain, "info": view })).into_response()
}

async fn tokens_revoke(State(app): State<Shared>, Path(id): Path<String>) -> Response {
    if !app.auth.revoke(&id) {
        return (StatusCode::NOT_FOUND, Json(json!({"error": "no such active token"}))).into_response();
    }
    let mut store = app.store.lock().unwrap();
    App::event(&mut store, None, "auth", format!("吊销 token {id}"));
    app.persist(&store);
    Json(json!({"ok": true})).into_response()
}

async fn tokens_rotate_admin(State(app): State<Shared>) -> Response {
    let t = app.auth.rotate_admin();
    let mut store = app.store.lock().unwrap();
    App::event(&mut store, None, "auth", "管理员 token 已轮换".into());
    app.persist(&store);
    Json(json!({"token": t})).into_response()
}

async fn pair_start(State(app): State<Shared>, body: Option<Json<Value>>) -> Response {
    let name = body
        .as_ref()
        .and_then(|b| b.get("name").and_then(|v| v.as_str()).map(|s| s.to_string()))
        .unwrap_or_else(|| "phone".into());
    let extra: Vec<String> = body
        .as_ref()
        .and_then(|b| b.get("urls").and_then(|v| v.as_array()).cloned())
        .unwrap_or_default()
        .iter()
        .filter_map(|v| v.as_str().map(|s| s.to_string()))
        .filter(|s| s.starts_with("http://") || s.starts_with("https://"))
        .collect();
    let (id, secret, expires_at) = app.auth.start_pairing(&name);
    let urls = hub_urls(&app, &extra);
    let host = host_name();
    let link = auth::pair_link(&secret, &host, &urls);
    Json(json!({"id": id, "link": link, "urls": urls, "expiresAt": expires_at, "name": name, "host": host})).into_response()
}

/// 这台电脑的名字（手机确认页上显示「连接到 xx？」）。
fn host_name() -> String {
    std::env::var("VIBELINK_HOST_NAME")
        .or_else(|_| std::env::var("COMPUTERNAME"))
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_default()
}

/// 手机扫到码、还没点「连接」：只校验，不消耗密钥。返回电脑名和过期时间给确认页。
async fn pair_scan(State(app): State<Shared>, Json(body): Json<Value>) -> Response {
    let code = body.get("code").and_then(|v| v.as_str()).unwrap_or("");
    let name = body.get("name").and_then(|v| v.as_str()).unwrap_or("");
    match app.auth.mark_scanned(code, name) {
        Ok(expires_at) => Json(json!({"ok": true, "host": host_name(), "expiresAt": expires_at})).into_response(),
        Err(e) => (StatusCode::FORBIDDEN, Json(json!({"error": e}))).into_response(),
    }
}

async fn pair_status(State(app): State<Shared>, Path(id): Path<String>) -> Response {
    let (status, name) = app.auth.pairing_status(&id);
    Json(json!({"status": status, "name": name})).into_response()
}

fn hub_port() -> u16 {
    std::env::var("VIBELINK_PORT").ok().and_then(|v| v.parse().ok()).unwrap_or(18995)
}

/// 手机能用来连 hub 的地址：本机各网卡的局域网 / tailnet IP，加上公网入口
/// （`VIBELINK_PUBLIC_URL` 或 `data/public-url.txt`，每行一个），再加上调用方额外给的。
fn hub_urls(app: &App, extra: &[String]) -> Vec<String> {
    let ips: Vec<std::net::Ipv4Addr> = if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter_map(|i| match i.ip() {
            std::net::IpAddr::V4(v4) => Some(v4),
            _ => None,
        })
        .collect();
    let mut public: Vec<String> = extra.to_vec();
    if let Ok(v) = std::env::var("VIBELINK_PUBLIC_URL") {
        public.extend(v.split(';').map(|s| s.to_string()));
    }
    if let Ok(v) = fs::read_to_string(app.data.join("public-url.txt")) {
        public.extend(v.lines().map(|s| s.to_string()));
    }
    public.retain(|s| s.trim().starts_with("https://") || s.trim().starts_with("http://"));
    auth::order_hub_urls(&ips, hub_port(), &public)
}

/// 用 Unicode 半块字符把二维码画进终端（每个字符 = 上下两个模块）。
fn qr_to_terminal(text: &str) -> String {
    use qrcodegen::{QrCode, QrCodeEcc};
    let Ok(qr) = QrCode::encode_text(text, QrCodeEcc::Medium) else { return String::from("(链接太长，画不出二维码)\n") };
    let n = qr.size();
    let quiet = 2;
    let dark = |x: i32, y: i32| x >= 0 && y >= 0 && x < n && y < n && qr.get_module(x, y);
    let mut out = String::new();
    let mut y = -quiet;
    while y < n + quiet {
        for x in -quiet..n + quiet {
            // 终端一般是深底浅字：用「亮 = 空白模块」的反色画法，手机才扫得出
            out.push(match (dark(x, y), dark(x, y + 1)) {
                (false, false) => '█',
                (false, true) => '▀',
                (true, false) => '▄',
                (true, true) => ' ',
            });
        }
        out.push('\n');
        y += 2;
    }
    out
}

async fn pair_redeem(State(app): State<Shared>, Json(body): Json<Value>) -> Response {
    let code = body.get("code").and_then(|v| v.as_str()).unwrap_or("");
    let name = body.get("name").and_then(|v| v.as_str()).unwrap_or("");
    match app.auth.redeem(code, name) {
        Ok((view, plain)) => {
            let mut store = app.store.lock().unwrap();
            App::event(&mut store, None, "auth", format!("新设备配对：{}（{}）", view.name, view.id));
            app.persist(&store);
            Json(json!({"token": plain, "info": view})).into_response()
        }
        Err(e) => {
            let mut store = app.store.lock().unwrap();
            App::event(&mut store, None, "auth", format!("配对失败：{e}"));
            (StatusCode::FORBIDDEN, Json(json!({"error": e}))).into_response()
        }
    }
}

// ---------- handlers ----------
async fn health(State(app): State<Shared>) -> Json<Value> {
    let n = app.store.lock().unwrap().devices.len();
    let mut v = json!(Health {
        ok: true,
        version: VERSION.to_string(),
        protocol: vibelink_protocol::PROTOCOL_VERSION.to_string(),
        devices: n,
    });
    v["policyTicks"] = json!(policy::ticks());
    Json(v)
}

async fn get_policy(State(app): State<Shared>) -> Json<Value> {
    let p = app.policy.lock().unwrap().clone();
    Json(serde_json::to_value(p).unwrap_or_else(|_| json!({})))
}

async fn put_policy(State(app): State<Shared>, Json(p): Json<policy::Policy>) -> Response {
    *app.policy.lock().unwrap() = p.clone();
    let _ = fs::write(app.data.join("policy.json"), serde_json::to_string_pretty(&p).unwrap_or_default());
    let mut store = app.store.lock().unwrap();
    App::event(&mut store, None, "policy", format!("策略已更新（enabled={} dryRun={}）", p.enabled, p.dry_run));
    app.persist(&store);
    Json(json!({"ok": true})).into_response()
}

async fn ingest(
    State(app): State<Shared>,
    ctx: Option<axum::Extension<auth::AuthCtx>>,
    Json(mut report): Json<DeviceReport>,
) -> Response {
    if report.id.trim().is_empty() {
        return (StatusCode::BAD_REQUEST, Json(json!({"error": "id required"}))).into_response();
    }
    if app.beta_mode {
        report.proxy = Default::default();
    }
    if let Some(axum::Extension(ref c)) = ctx {
        if let Some(tid) = tenants::extract_tenant_id(&c.name) {
            report.proxy = Default::default();
            let prefix = format!("{tid}:");
            if !report.id.starts_with(&prefix) {
                report.id = format!("{prefix}{}", report.id);
            }
        }
    }
    let now = now_ms();
    let mut store = app.store.lock().unwrap();
    let prev = store.devices.get(&report.id).cloned();
    let device = Device {
        first_seen: prev.as_ref().map(|p| p.first_seen).unwrap_or(now),
        last_seen: now,
        online: true,
        age_ms: 0,
        report,
    };
    let host = device.report.hostname.clone();
    let id = device.report.id.clone();
    let was_offline = prev.map(|p| now.saturating_sub(p.last_seen) > OFFLINE_AFTER_MS).unwrap_or(true);
    store.devices.insert(id.clone(), device);
    if was_offline {
        App::event(&mut store, Some(id), "online", format!("{host} 上线"));
    }
    app.persist(&store);
    Json(json!({"ok": true, "serverTime": now})).into_response()
}

async fn devices(
    State(app): State<Shared>,
    ctx: Option<axum::Extension<auth::AuthCtx>>,
) -> Json<Value> {
    let caller_tid = ctx
        .as_ref()
        .and_then(|axum::Extension(c)| tenants::extract_tenant_id(&c.name).map(|s| s.to_string()));
    let store = app.store.lock().unwrap();
    let mut list: Vec<Device> = store
        .devices
        .values()
        .filter(|d| match &caller_tid {
            Some(tid) => d.report.id.starts_with(&format!("{tid}:")),
            None => true,
        })
        .cloned()
        .map(|mut d| {
            if app.beta_mode || caller_tid.is_some() {
                d.report.proxy = Default::default();
            }
            view(d)
        })
        .collect();
    list.sort_by(|a, b| a.report.hostname.cmp(&b.report.hostname));
    Json(json!({"devices": list, "now": now_ms(), "offlineAfterMs": OFFLINE_AFTER_MS}))
}

async fn device(State(app): State<Shared>, Path(id): Path<String>) -> Response {
    let store = app.store.lock().unwrap();
    match store.devices.get(&id) {
        Some(d) => Json(view(d.clone())).into_response(),
        None => (StatusCode::NOT_FOUND, Json(json!({"error": "no such device"}))).into_response(),
    }
}

async fn events(State(app): State<Shared>, Query(q): Query<HashMap<String, String>>) -> Json<Value> {
    let limit: usize = q.get("limit").and_then(|v| v.parse().ok()).unwrap_or(80).min(300);
    let store = app.store.lock().unwrap();
    let list: Vec<Event> = store.events.iter().rev().take(limit).cloned().collect();
    Json(json!({"events": list}))
}

async fn create_command(State(app): State<Shared>, Json(body): Json<Value>) -> Response {
    let device = body.get("device").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let action = body.get("action").and_then(|v| v.as_str()).unwrap_or("").to_string();
    if device.is_empty() || action.is_empty() {
        return (StatusCode::BAD_REQUEST, Json(json!({"error": "device and action required"}))).into_response();
    }
    let args = body.get("args").cloned().unwrap_or(json!({}));
    let mut store = app.store.lock().unwrap();
    let cmd = Command {
        id: App::next_id(&mut store),
        device: device.clone(),
        action: action.clone(),
        args,
        status: "pending".into(),
        created_at: now_ms(),
        started_at: None,
        finished_at: None,
        result: None,
    };
    App::event(&mut store, Some(device), "command", format!("排队 {action}"));
    store.commands.push(cmd.clone());
    app.persist(&store);
    Json(cmd).into_response()
}

async fn list_commands(State(app): State<Shared>, Query(q): Query<HashMap<String, String>>) -> Json<Value> {
    let device = q.get("device").cloned();
    let status = q.get("status").cloned().unwrap_or_else(|| "pending".into());
    let store = app.store.lock().unwrap();
    let list: Vec<Command> = store
        .commands
        .iter()
        .filter(|c| device.as_ref().map(|d| &c.device == d).unwrap_or(true))
        .filter(|c| status == "all" || c.status == status)
        .take(50)
        .cloned()
        .collect();
    Json(json!({"commands": list}))
}

async fn claim_command(State(app): State<Shared>, Path(id): Path<String>) -> Response {
    let mut store = app.store.lock().unwrap();
    match store.commands.iter_mut().find(|c| c.id == id) {
        Some(c) => {
            c.status = "running".into();
            c.started_at = Some(now_ms());
            let copy = c.clone();
            app.persist(&store);
            Json(copy).into_response()
        }
        None => (StatusCode::NOT_FOUND, Json(json!({"error": "no such command"}))).into_response(),
    }
}

async fn command_result(State(app): State<Shared>, Json(body): Json<Value>) -> Response {
    let id = body.get("id").and_then(|v| v.as_str()).unwrap_or("");
    let ok = body.get("ok").and_then(|v| v.as_bool()).unwrap_or(false);
    let result = body.get("result").cloned();
    let mut store = app.store.lock().unwrap();
    match store.commands.iter_mut().find(|c| c.id == id) {
        Some(c) => {
            c.status = if ok { "done" } else { "failed" }.into();
            c.finished_at = Some(now_ms());
            c.result = result;
            let dev = c.device.clone();
            let act = c.action.clone();
            let st = c.status.clone();
            App::event(&mut store, Some(dev), "command", format!("{act} {st}"));
            app.persist(&store);
            Json(json!({"ok": true})).into_response()
        }
        None => (StatusCode::NOT_FOUND, Json(json!({"error": "no such command"}))).into_response(),
    }
}

#[derive(serde::Serialize)]
struct ConfigSummary {
    name: String,
    versions: Vec<String>,
}

fn config_dir(app: &App, name: &str) -> PathBuf {
    app.data.join("configs").join(name)
}

fn versions(dir: &FsPath) -> Vec<String> {
    let mut v: Vec<String> = fs::read_dir(dir)
        .map(|rd| {
            rd.flatten()
                .filter(|e| e.file_name() != "latest")
                .map(|e| e.file_name().to_string_lossy().to_string())
                .collect()
        })
        .unwrap_or_default();
    v.sort();
    v.reverse();
    v
}

async fn list_configs(State(app): State<Shared>) -> Json<Value> {
    let root = app.data.join("configs");
    let mut out: Vec<ConfigSummary> = Vec::new();
    if let Ok(rd) = fs::read_dir(&root) {
        for e in rd.flatten() {
            if e.path().is_dir() {
                let name = e.file_name().to_string_lossy().to_string();
                out.push(ConfigSummary { versions: versions(&e.path()), name });
            }
        }
    }
    out.sort_by(|a, b| a.name.cmp(&b.name));
    Json(json!({"configs": out}))
}

async fn get_config(State(app): State<Shared>, Path(name): Path<String>) -> Response {
    if !name.chars().all(|c| c.is_ascii_alphanumeric() || "._-".contains(c)) {
        return (StatusCode::BAD_REQUEST, Json(json!({"error": "bad name"}))).into_response();
    }
    let dir = config_dir(&app, &name);
    match fs::read_to_string(dir.join("latest")) {
        Ok(content) => Json(json!({"name": name, "versions": versions(&dir), "content": content})).into_response(),
        Err(_) => (StatusCode::NOT_FOUND, Json(json!({"error": "no such config"}))).into_response(),
    }
}

async fn put_config(State(app): State<Shared>, Path(name): Path<String>, body: String) -> Response {
    if !name.chars().all(|c| c.is_ascii_alphanumeric() || "._-".contains(c)) {
        return (StatusCode::BAD_REQUEST, Json(json!({"error": "bad name"}))).into_response();
    }
    if body.trim().is_empty() {
        return (StatusCode::BAD_REQUEST, Json(json!({"error": "empty body"}))).into_response();
    }
    let dir = config_dir(&app, &name);
    let _ = fs::create_dir_all(&dir);
    let stamp = format!("{}", now_ms());
    let _ = fs::write(dir.join(&stamp), &body);
    let _ = fs::write(dir.join("latest"), &body);
    let mut store = app.store.lock().unwrap();
    App::event(&mut store, None, "config", format!("{name} 新版本 {stamp}"));
    app.persist(&store);
    Json(json!({"ok": true, "name": name, "version": stamp})).into_response()
}

// ---------------- session plane ----------------

/// Queue a request for the device's agent and wait for its reply.
async fn call_agent(app: &Shared, device: &str, kind: &str, payload: Value, secs: u64) -> Result<Value, String> {
    if kind.starts_with("scheduler.") && app.beta_mode {
        return Err("Public Bot schedules require metered Hub execution and are currently paused".into());
    }
    if kind == "scheduler.action" && payload["action"] == "create"
        && payload["botId"].as_str().is_some_and(|id| !id.is_empty()) {
        return Err("Bot schedules paused until renewable per-user model grants are supported".into());
    }

    let mut payload = payload;
    let mut binding = None;
    let mut old_session = String::new();
    let mut actual_device = device.to_string();
    if kind.starts_with("codex.") || kind == "sessions.list" {
        let sid = payload["session"].as_str().unwrap_or("").to_string();
        let stored = app.bindings.get(&sid);
        if app.beta_mode && !sid.is_empty() && stored.is_none() { return Err("Session is not bound to this tenant's agent".into()); }
        let b = match stored {Some(b)=>b,None=>agent_sessions::new_binding(app,device,&payload)?};
        if !sid.is_empty() && payload["model"].as_str().is_some_and(|m|m!=b.model) {return Err("Session model is bound; create a new session to change it".into());}
        if b.agent != "codex" { return Err("Session agent mismatch".into()); }
        if let Some(bot) = payload["botId"].as_str().and_then(|id|app.bots.get_bot(id)) {
            if !sid.is_empty() && (bot.id != b.bot_id || bot.model != b.model) { return Err("Session model/Bot binding mismatch; create a new session".into()); }
        }
        if let Some(caller) = payload.get("meterIdentity").filter(|v|!v.is_null()) {
            let caller: model_usage::Identity = serde_json::from_value(caller.clone()).map_err(|_|"Invalid identity")?;
            if !sid.is_empty() && caller.user_id != b.owner.user_id { return Err("Session belongs to another user".into()); }
        }
        actual_device = b.device.clone();
        payload["codexRuntime"] = agent_sessions::runtime(app,&b,matches!(kind,"codex.create"|"codex.prompt"));
        if !sid.is_empty() {payload["session"] = json!(b.id);old_session=sid;}
        if kind=="codex.create" || kind=="codex.prompt" {binding=Some(b);}
    } else if app.beta_mode || kind.starts_with("antigravity.") || kind.starts_with("claude.") || kind.starts_with("opencode.") {
        return Err("This runtime or development resource is unavailable".into());
    }
    let (id, rx) = {
        let mut s = app.sessions.lock().unwrap();
        s.seq += 1;
        let id = format!("q{:x}{:x}", now_ms(), s.seq);
        let (tx, rx) = tokio::sync::oneshot::channel();
        s.waiters.insert(id.clone(), tx);
        s.queue
            .entry(actual_device.to_string())
            .or_default()
            .push(json!({ "id": id, "kind": if kind.starts_with("codex.") {format!("codex.runtime.{}",kind.trim_start_matches("codex."))} else {kind.into()}, "payload": payload }));
        (id, rx)
    };
    match tokio::time::timeout(std::time::Duration::from_secs(secs), rx).await {
        Ok(Ok(Ok(mut v))) => {
            if let Some(mut b) = binding {
                b.id = v["id"].as_str().unwrap_or(&old_session).to_string();
                if !b.id.is_empty() {
                    v["agent"]=json!("codex");v["model"]=json!(b.model);v["device"]=json!(b.device);
                    app.bindings.bind(&old_session,b);
                }
            }
            Ok(v)
        },
        Ok(Ok(Err(e))) => Err(e),
        Ok(Err(_)) => Err("agent disconnected".into()),
        Err(_) => {
            app.sessions.lock().unwrap().waiters.remove(&id);
            Err(format!("agent {device} timeout"))
        }
    }
}

async fn agent_session_requests(State(app): State<Shared>, Query(q): Query<HashMap<String, String>>) -> Json<Value> {
    let device = q.get("device").cloned().unwrap_or_default();
    let local_host = host_name().to_lowercase();
    let mut s = app.sessions.lock().unwrap();
    let mut list = s.queue.remove(&device).unwrap_or_default();
    // 本机 Agent 可能以 "pc" 或本机 hostname（如 "haoshao"）上报，统一双向合并拉取，彻底杜绝 "agent pc timeout"
    if device.eq_ignore_ascii_case("pc") || device.eq_ignore_ascii_case(&local_host) {
        if let Some(mut extra) = s.queue.remove("pc") {
            list.append(&mut extra);
        }
        if let Some(mut extra) = s.queue.remove(&local_host) {
            list.append(&mut extra);
        }
    }
    Json(json!({ "requests": list }))
}

async fn agent_session_result(State(app): State<Shared>, Json(body): Json<Value>) -> Response {
    let id = body.get("id").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let ok = body.get("ok").and_then(|v| v.as_bool()).unwrap_or(false);
    let payload = body.get("payload").cloned().unwrap_or(Value::Null);
    let err = body.get("error").and_then(|v| v.as_str()).unwrap_or("agent error").to_string();
    let tx = app.sessions.lock().unwrap().waiters.remove(&id);
    if let Some(tx) = tx {
        let _ = tx.send(if ok { Ok(payload) } else { Err(err) });
    }
    Json(json!({"ok": true})).into_response()
}

fn session_error(e: String) -> Response {
    (StatusCode::BAD_GATEWAY, Json(json!({ "error": e }))).into_response()
}

async fn sessions_list(State(app): State<Shared>, Query(q): Query<HashMap<String, String>>) -> Response {
    let device = q.get("device").cloned().unwrap_or_default();
    if device.is_empty() {
        return session_error("device required".into());
    }
    {
        let s = app.store.lock().unwrap();
        if let Some(d) = s.devices.get(&device) {
            if d.report.agent_version.as_str() < "0.2.5" && d.report.opencode.is_none() {
                return Json(json!({ "data": [] })).into_response();
            }
        }
    }
    match call_agent(&app, &device, "opencode.list", json!({}), 6).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

/// 所有 agent 的会话（opencode / Codex / Claude / Antigravity），手机端先选 agent 再进会话。
async fn sessions_all(State(app): State<Shared>, Query(q): Query<HashMap<String, String>>, ctx: Option<axum::Extension<auth::AuthCtx>>) -> Response {
    let device = q.get("device").cloned().unwrap_or_default();
    if device.is_empty() {
        return session_error("device required".into());
    }
    let caller = agent_sessions::caller(ctx.as_ref().map(|c| &c.0));
    let bindings = app.bindings.list().into_iter().filter(|b| {
        caller["userId"].as_str().is_none_or(|id| id == b.owner.user_id)
    });
    let mut sessions = Vec::new();
    let mut scopes = std::collections::HashSet::new();
    for binding in bindings {
        if !scopes.insert(binding.scope.clone()) {continue;}
        let payload=json!({"model":binding.model,"meterIdentity":binding.owner,"codexRuntime":agent_sessions::runtime(&app,&binding,false)});
        // List against the binding's profile, including GPT and DeepSeek Codex homes.
        let mut payload=payload;payload["session"]=json!(binding.id);
        if let Ok(v)=call_agent(&app,&binding.device,"codex.list",payload,8).await {
            if let Some(list)=v.pointer("/agents/0/sessions").and_then(Value::as_array){sessions.extend(list.iter().cloned().map(|mut session|{session["model"]=json!(binding.model);session["device"]=json!(binding.device);session}));}
        }
    }
    if scopes.is_empty() && !app.beta_mode {
        if let Ok(v)=call_agent(&app,&device,"codex.list",json!({"meterIdentity":caller}),8).await {
            if let Some(list)=v.pointer("/agents/0/sessions").and_then(Value::as_array){sessions.extend(list.iter().cloned());}
        }
    }
    Json(json!({"agents":[{"id":"codex","name":"Codex","sessions":sessions}]})).into_response()
}

/// 按 agent 取消息：opencode 走本地服务，Codex/Claude 读各自的 jsonl。
async fn session_messages_by_agent(
    State(app): State<Shared>,
    Path((dev, agent, sid)): Path<(String, String, String)>,
    Query(q): Query<HashMap<String, String>>,
    ctx: Option<axum::Extension<auth::AuthCtx>>,
) -> Response {
    let kind = match agent.as_str() {
        "codex" => format!("{agent}.messages"),
        other => return session_error(format!("unknown agent {other}")),
    };
    // 只把 cursor 传下去；opencode 的 cursor 不能和 order 同时出现，order 由 agent 决定
    let cursor = q.get("cursor").cloned().unwrap_or_default();
    match call_agent(&app, &dev, &kind, json!({ "session": sid, "cursor": cursor, "meterIdentity": agent_sessions::caller(ctx.as_ref().map(|c| &c.0)) }), 15).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}
async fn session_messages(State(app): State<Shared>, Path((dev, sid)): Path<(String, String)>) -> Response {
    match call_agent(&app, &dev, "opencode.messages", json!({ "session": sid }), 8).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

fn normalize_agent_kind(agent: Option<&str>, sid: &str) -> Result<String, String> {
    let a = agent.unwrap_or("").trim();
    match a {
        "codex" => Ok(a.to_string()),
        "" => {
            if sid.starts_with("ses_") {
                Ok("codex".to_string())
            } else {
                Ok("codex".to_string())
            }
        }
        other => Err(format!("unknown agent {other}")),
    }
}

async fn session_create(
    State(app): State<Shared>,
    Path(dev): Path<String>,
    ctx: Option<axum::Extension<auth::AuthCtx>>,
    Json(body): Json<Value>,
) -> Response {
    let agent = body.get("agent").and_then(|v| v.as_str()).unwrap_or("codex").trim().to_string();
    let kind = match agent.as_str() {
        "codex" => format!("{agent}.create"),
        other => return session_error(format!("unknown agent {other}")),
    };
    let cwd = body.get("cwd").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let text = body.get("text").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let bot_id = body
        .get("botId")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .trim()
        .to_string();
    if let Some(bot) = app.bots.get_bot(&bot_id) {
        if bot.engine != agent { return (StatusCode::BAD_REQUEST, Json(json!({"error": "Bot runtime binding mismatch"}))).into_response(); }
    }
    let mut payload = json!({ "cwd": cwd, "text": text });
    payload["meterIdentity"] = agent_sessions::caller(ctx.as_ref().map(|c|&c.0));
    if let Some(model)=body.get("model") {payload["model"]=model.clone();}
    if !bot_id.is_empty() {
        payload["botId"] = json!(bot_id);
        if let Some(bot_ctx) = app.bots.build_bot_context_prompt(&bot_id) {
            payload["botContext"] = bot_ctx;
        }
    }
    if let Some(te) = body.get("thinkingEffort").and_then(|v| v.as_str()) {
        payload["thinkingEffort"] = json!(te);
    }
    if let Some(iso) = body.get("isolationMode").and_then(|v| v.as_str()) {
        payload["isolationMode"] = json!(iso);
    }
    if let Some(f) = body.get("files") {
        payload["files"] = f.clone();
    }
    match call_agent(&app, &dev, &kind, payload, 20).await {
        Ok(mut v) => {
            if let Some(obj) = v.as_object_mut() {
                obj.entry("device".to_string()).or_insert_with(|| json!(dev));
                obj.entry("agent".to_string()).or_insert_with(|| json!(agent));
                if !bot_id.is_empty() {
                    obj.entry("botId".to_string()).or_insert_with(|| json!(bot_id));
                    if let Some(sid) = obj.get("id").and_then(|x| x.as_str()) {
                        let _ = app.bots.upsert_bot(&json!({
                            "id": bot_id,
                            "name": app.bots.get_bot(&bot_id).map(|b| b.name).unwrap_or_else(|| "Bot".into()),
                            "activeSessionId": sid,
                            "lastText": if text.is_empty() { "已开启新会话" } else { text.as_str() }
                        }));
                    }
                }
            }
            Json(v).into_response()
        }
        Err(e) => session_error(e),
    }
}

async fn session_prompt(
    State(app): State<Shared>,
    Path((dev, sid)): Path<(String, String)>,
    ctx: Option<axum::Extension<auth::AuthCtx>>,
    Json(body): Json<Value>,
) -> Response {
    let text = body.get("text").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let has_files = body
        .get("files")
        .and_then(|f| f.as_array())
        .map(|a| !a.is_empty())
        .unwrap_or(false);
    if text.trim().is_empty() && !has_files {
        return (StatusCode::BAD_REQUEST, Json(json!({"error": "text or files required"}))).into_response();
    }
    let agent = match normalize_agent_kind(body.get("agent").and_then(|v| v.as_str()), &sid) {
        Ok(a) => a,
        Err(e) => return session_error(e),
    };
    let kind = format!("{agent}.prompt");
    let mut payload = json!({ "session": sid, "text": text });
    payload["meterIdentity"] = agent_sessions::caller(ctx.as_ref().map(|c|&c.0));
    if let Some(model)=body.get("model") {payload["model"]=model.clone();}
    if let Some(bot_id) = body.get("botId").and_then(|v| v.as_str()).filter(|s| !s.is_empty()) {
        payload["botId"] = json!(bot_id);
        if let Some(bot_ctx) = app.bots.build_bot_context_prompt(bot_id) {
            payload["botContext"] = bot_ctx;
        }
    }
    if let Some(te) = body.get("thinkingEffort").and_then(|v| v.as_str()) {
        payload["thinkingEffort"] = json!(te);
    }
    if let Some(cwd) = body.get("cwd").and_then(|v| v.as_str()) {
        payload["cwd"] = json!(cwd);
    }
    if let Some(f) = body.get("files") {
        payload["files"] = f.clone();
    }
    match call_agent(&app, &dev, &kind, payload, 20).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

async fn workspace_tree(
    State(app): State<Shared>,
    Path(dev): Path<String>,
    Query(q): Query<HashMap<String, String>>,
) -> Response {
    let cwd = q.get("cwd").cloned().unwrap_or_default();
    let depth: u64 = q.get("depth").and_then(|d| d.parse().ok()).unwrap_or(2);
    match call_agent(&app, &dev, "workspace.tree", json!({ "cwd": cwd, "depth": depth }), 10).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

async fn workspace_file(
    State(app): State<Shared>,
    Path(dev): Path<String>,
    Query(q): Query<HashMap<String, String>>,
) -> Response {
    let path = q.get("path").cloned().unwrap_or_default();
    match call_agent(&app, &dev, "workspace.read", json!({ "path": path }), 15).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

async fn workspace_upload(
    State(app): State<Shared>,
    Path(dev): Path<String>,
    Json(body): Json<Value>,
) -> Response {
    let cwd = body.get("cwd").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let subdir = body.get("subdir").and_then(|v| v.as_bool()).unwrap_or(false);
    let files = body.get("files").cloned().unwrap_or(json!([]));
    match call_agent(&app, &dev, "workspace.upload", json!({ "cwd": cwd, "subdir": subdir, "files": files }), 20).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

async fn workspace_git(
    State(app): State<Shared>,
    Path(dev): Path<String>,
    Query(q): Query<HashMap<String, String>>,
) -> Response {
    let cwd = q.get("cwd").cloned().unwrap_or_default();
    match call_agent(&app, &dev, "workspace.git", json!({ "cwd": cwd }), 12).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

async fn desktop_frame(
    State(app): State<Shared>,
    Path(dev): Path<String>,
    Query(q): Query<HashMap<String, String>>,
) -> Response {
    let width: u64 = q.get("width").and_then(|w| w.parse().ok()).unwrap_or(1280);
    let last_hash = q.get("lastHash").cloned().unwrap_or_default();
    match call_agent(&app, &dev, "desktop.frame", json!({ "width": width, "lastHash": last_hash }), 12).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

async fn desktop_input(
    State(app): State<Shared>,
    Path(dev): Path<String>,
    Json(body): Json<Value>,
) -> Response {
    match call_agent(&app, &dev, "desktop.input", body, 10).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

async fn desktop_lease_get(
    State(app): State<Shared>,
    Path(dev): Path<String>,
) -> Response {
    match call_agent(&app, &dev, "desktop.lease", json!({ "action": "status" }), 8).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

async fn desktop_lease_post(
    State(app): State<Shared>,
    Path(dev): Path<String>,
    Json(body): Json<Value>,
) -> Response {
    match call_agent(&app, &dev, "desktop.lease", body, 8).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

async fn terminal_get(
    State(app): State<Shared>,
    Path(dev): Path<String>,
    Query(q): Query<HashMap<String, String>>,
) -> Response {
    let session_id = q.get("sessionId").cloned().unwrap_or_default();
    match call_agent(&app, &dev, "terminal.list", json!({ "sessionId": session_id }), 10).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

async fn terminal_post(
    State(app): State<Shared>,
    Path(dev): Path<String>,
    Json(body): Json<Value>,
) -> Response {
    match call_agent(&app, &dev, "terminal.action", body, 15).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

async fn worktree_get(
    State(app): State<Shared>,
    Path(dev): Path<String>,
    Query(q): Query<HashMap<String, String>>,
) -> Response {
    let cwd = q.get("cwd").cloned().unwrap_or_default();
    match call_agent(&app, &dev, "worktree.list", json!({ "cwd": cwd }), 10).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

async fn worktree_post(
    State(app): State<Shared>,
    Path(dev): Path<String>,
    Json(body): Json<Value>,
) -> Response {
    match call_agent(&app, &dev, "worktree.action", body, 20).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

async fn scheduler_get(
    State(app): State<Shared>,
    Path(dev): Path<String>,
) -> Response {
    match call_agent(&app, &dev, "scheduler.list", json!({}), 10).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

async fn scheduler_post(
    State(app): State<Shared>,
    Path(dev): Path<String>,
    Json(body): Json<Value>,
) -> Response {
    match call_agent(&app, &dev, "scheduler.action", body, 15).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

async fn plugins_get(
    State(app): State<Shared>,
    Path(dev): Path<String>,
) -> Response {
    match call_agent(&app, &dev, "plugins.list", json!({}), 10).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

async fn plugins_post(
    State(app): State<Shared>,
    Path(dev): Path<String>,
    Json(body): Json<Value>,
) -> Response {
    match call_agent(&app, &dev, "plugins.action", body, 15).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

// ---------------- Persistent Bots, Memories, Groups & Fleet Management ----------------

async fn bots_list(State(app): State<Shared>) -> Json<Value> {
    Json(app.bots.list_snapshot())
}

async fn bots_templates_get(State(app): State<Shared>) -> Json<Value> {
    Json(json!({ "templates": app.bots.list_templates() }))
}

async fn bots_apply_preset_post(State(app): State<Shared>, Json(body): Json<Value>) -> Response {
    let preset = body
        .get("preset")
        .and_then(Value::as_str)
        .unwrap_or("grok_420")
        .trim();
    match app.bots.apply_preset_fleet(preset) {
        Ok(v) => Json(v).into_response(),
        Err(e) => (StatusCode::BAD_REQUEST, Json(json!({ "error": e }))).into_response(),
    }
}

async fn bot_recipe_export_get(State(app): State<Shared>, Path(id): Path<String>) -> Response {
    match app.bots.export_bot_recipe(&id) {
        Ok(v) => Json(v).into_response(),
        Err(e) => (StatusCode::NOT_FOUND, Json(json!({ "error": e }))).into_response(),
    }
}

async fn bots_import_recipe_post(State(app): State<Shared>, Json(body): Json<Value>) -> Response {
    match app.bots.import_bot_recipe(&body) {
        Ok(bot) => Json(json!({ "ok": true, "bot": bot })).into_response(),
        Err(e) => (StatusCode::BAD_REQUEST, Json(json!({ "error": e }))).into_response(),
    }
}

async fn bots_upsert(State(app): State<Shared>, Json(body): Json<Value>) -> Response {
    match app.bots.upsert_bot(&body) {
        Ok(bot) => Json(json!({ "ok": true, "bot": bot })).into_response(),
        Err(e) => (StatusCode::BAD_REQUEST, Json(json!({ "error": e }))).into_response(),
    }
}

async fn bot_get(State(app): State<Shared>, Path(id): Path<String>) -> Response {
    match app.bots.get_bot(&id) {
        Some(bot) => Json(json!({ "bot": bot })).into_response(),
        None => (StatusCode::NOT_FOUND, Json(json!({ "error": "bot not found" }))).into_response(),
    }
}

async fn bot_context_get(
    State(app): State<Shared>,
    Path(id): Path<String>,
    Query(q): Query<HashMap<String, String>>,
) -> Response {
    let mode = q.get("mode").map(String::as_str).unwrap_or("work");
    let query_hint = q.get("query").map(String::as_str).unwrap_or("");
    let attached_skills: Vec<String> = q
        .get("skills")
        .map(|s| {
            s.split(',')
                .map(|x| x.trim().to_string())
                .filter(|x| !x.is_empty())
                .collect()
        })
        .unwrap_or_default();
    match app
        .bots
        .build_bot_context_prompt_with_options(&id, mode, query_hint, &attached_skills)
    {
        Some(ctx) => Json(ctx).into_response(),
        None => (StatusCode::NOT_FOUND, Json(json!({ "error": "bot not found" }))).into_response(),
    }
}

async fn bot_skills_learn_post(
    State(app): State<Shared>,
    Path(id): Path<String>,
    Json(body): Json<Value>,
) -> Response {
    match app.bots.draft_or_save_learned_skill(&id, &body) {
        Ok(res) => Json(res).into_response(),
        Err(e) => (StatusCode::BAD_REQUEST, Json(json!({ "error": e }))).into_response(),
    }
}

async fn bot_memories_post(
    State(app): State<Shared>,
    Path(id): Path<String>,
    Json(body): Json<Value>,
) -> Response {
    match app.bots.add_memory(&id, &body) {
        Ok(fact) => Json(json!({ "ok": true, "memory": fact })).into_response(),
        Err(e) => (StatusCode::BAD_REQUEST, Json(json!({ "error": e }))).into_response(),
    }
}

async fn bot_memories_delete(
    State(app): State<Shared>,
    Path((id, mem_id)): Path<(String, String)>,
) -> Response {
    match app.bots.delete_memory(&id, &mem_id) {
        Ok(removed) => Json(json!({ "ok": true, "removed": removed })).into_response(),
        Err(e) => (StatusCode::BAD_REQUEST, Json(json!({ "error": e }))).into_response(),
    }
}

async fn bot_groups_post(State(app): State<Shared>, Json(body): Json<Value>) -> Response {
    match app.bots.upsert_group(&body) {
        Ok(grp) => Json(json!({ "ok": true, "group": grp })).into_response(),
        Err(e) => (StatusCode::BAD_REQUEST, Json(json!({ "error": e }))).into_response(),
    }
}

async fn bot_group_messages_get(
    State(app): State<Shared>,
    Path(id): Path<String>,
) -> Json<Value> {
    Json(json!({ "messages": app.bots.list_group_messages(&id) }))
}

async fn bot_group_messages_post(
    State(app): State<Shared>,
    Path(id): Path<String>,
    Json(body): Json<Value>,
) -> Response {
    match app.bots.append_group_message(&id, &body) {
        Ok(msg) => Json(json!({ "ok": true, "message": msg })).into_response(),
        Err(e) => (StatusCode::BAD_REQUEST, Json(json!({ "error": e }))).into_response(),
    }
}

async fn fleet_action_post(State(app): State<Shared>, Json(body): Json<Value>) -> Response {
    let tool = body.get("tool").and_then(Value::as_str).unwrap_or("").trim();
    let caller_bot_id = body.get("callerBotId").and_then(Value::as_str).unwrap_or("").trim();
    let target_id = body
        .get("botId")
        .or_else(|| body.get("bot_id"))
        .or_else(|| body.get("groupId"))
        .or_else(|| body.get("targetId"))
        .and_then(Value::as_str)
        .unwrap_or("")
        .trim();

    // 当由另一个 Bot 发起时，先走三级审批门禁（Ask / Auto / Never）
    if !caller_bot_id.is_empty() && !matches!(tool, "list_fleet" | "organize_bot" | "update_bot") {
        let title = format!("车队操作：{tool}");
        let summary = format!("Bot {caller_bot_id} 请求对 {target_id} 执行 {tool}");
        let (allow, card) = app.approvals.evaluate_or_enqueue(
            tool,
            &title,
            &summary,
            caller_bot_id,
            target_id,
            body.clone(),
            body.clone(),
        );
        if !allow {
            let status = if card.status == "denied" {
                StatusCode::FORBIDDEN
            } else {
                StatusCode::ACCEPTED
            };
            return (
                status,
                Json(json!({
                    "ok": false,
                    "pendingApproval": card.status == "pending",
                    "approval": card,
                })),
            )
                .into_response();
        }
    }

    // create_child_routine 转发给对应设备上的 scheduler.action
    if tool == "create_child_routine" {
        let target_bot = match app.bots.get_bot(target_id) {
            Some(b) if !b.archived => b,
            _ => {
                return (
                    StatusCode::BAD_REQUEST,
                    Json(json!({ "error": format!("active bot '{target_id}' not found") })),
                )
                    .into_response()
            }
        };
        let prompt = body.get("prompt").and_then(Value::as_str).unwrap_or("").trim();
        let cron = body.get("cron").and_then(Value::as_str).unwrap_or("每天 09:00").trim();
        let name = body
            .get("name")
            .and_then(Value::as_str)
            .unwrap_or("子 Bot 例行任务")
            .trim();
        if bots::contains_secret(prompt) || bots::contains_secret(name) {
            return (
                StatusCode::BAD_REQUEST,
                Json(json!({ "error": "secret filter rejected routine name or prompt" })),
            )
                .into_response();
        }
        let sched_req = json!({
            "action": "create",
            "name": format!("[{}] {}", target_bot.name, name),
            "botId": target_bot.id,
            "agent": target_bot.engine,
            "cwd": target_bot.cwd,
            "prompt": prompt,
            "cron": cron,
            "createdByBotId": caller_bot_id,
        });
        return match call_agent(&app, &target_bot.device, "scheduler.action", sched_req, 12).await {
            Ok(v) => Json(json!({ "ok": true, "routine": v })).into_response(),
            Err(e) => session_error(e),
        };
    }

    match app.bots.execute_fleet_tool(&body) {
        Ok(res) => Json(res).into_response(),
        Err(e) => (StatusCode::BAD_REQUEST, Json(json!({ "error": e }))).into_response(),
    }
}

// ---------------- Approvals Gate API ----------------

async fn approvals_list(
    State(app): State<Shared>,
    Query(q): Query<HashMap<String, String>>,
) -> Json<Value> {
    let status = q.get("status").map(|s| s.as_str());
    Json(app.approvals.list_cards(status))
}

async fn approvals_policy_get(State(app): State<Shared>) -> Json<Value> {
    Json(json!({ "policy": app.approvals.get_policy() }))
}

async fn approvals_policy_post(State(app): State<Shared>, Json(body): Json<Value>) -> Response {
    match app.approvals.update_policy(&body) {
        Ok(p) => Json(json!({ "ok": true, "policy": p })).into_response(),
        Err(e) => (StatusCode::BAD_REQUEST, Json(json!({ "error": e }))).into_response(),
    }
}

async fn approvals_request_post(State(app): State<Shared>, Json(body): Json<Value>) -> Json<Value> {
    let kind = body.get("kind").and_then(Value::as_str).unwrap_or("custom");
    let title = body.get("title").and_then(Value::as_str).unwrap_or("请求确认");
    let summary = body.get("summary").and_then(Value::as_str).unwrap_or("");
    let caller = body.get("callerBotId").and_then(Value::as_str).unwrap_or("");
    let target = body.get("targetId").and_then(Value::as_str).unwrap_or("");
    let action_payload = body.get("actionPayload").cloned().unwrap_or(json!({}));
    let details = body.get("details").cloned().unwrap_or(json!({}));
    let (allow, card) = app.approvals.evaluate_or_enqueue(
        kind,
        title,
        summary,
        caller,
        target,
        action_payload,
        details,
    );
    Json(json!({ "allowed": allow, "card": card }))
}

async fn approvals_decide_post(
    State(app): State<Shared>,
    Path(id): Path<String>,
    Json(body): Json<Value>,
) -> Response {
    let approve = body.get("approve").and_then(Value::as_bool).unwrap_or(false);
    let reason = body.get("reason").and_then(Value::as_str).unwrap_or("");
    match app.approvals.decide(&id, approve, reason) {
        Ok(card) => {
            let mut execution_result = Value::Null;
            if approve && card.kind == "deepseek_command" {
                execution_result = match call_agent(&app, card.action_payload["device"].as_str().unwrap_or("pc"), "terminal.action", card.action_payload["payload"].clone(), 120).await {
                    Ok(v) => v, Err(e) => json!({"error":e}),
                };
            }
            if approve && card.action_payload.get("tool").is_some() {
                match app.bots.execute_fleet_tool(&card.action_payload) {
                    Ok(v) => execution_result = v,
                    Err(e) => {
                        return (
                            StatusCode::CONFLICT,
                            Json(json!({
                                "ok": false,
                                "card": card,
                                "error": e,
                            })),
                        )
                            .into_response();
                    }
                }
            }
            Json(json!({
                "ok": true,
                "card": card,
                "executionResult": execution_result,
            }))
            .into_response()
        }
        Err(e) => (StatusCode::BAD_REQUEST, Json(json!({ "error": e }))).into_response(),
    }
}

// ---------------- Voice & Bot Call API ----------------

async fn voice_config_get(State(app): State<Shared>) -> Json<Value> {
    Json(json!({ "config": app.voice.get_config() }))
}

async fn voice_config_post(State(app): State<Shared>, Json(body): Json<Value>) -> Json<Value> {
    let cfg = app.voice.update_config(&body);
    Json(json!({ "ok": true, "config": cfg }))
}

async fn voice_tts_post(State(app): State<Shared>, Json(body): Json<Value>) -> Response {
    let text = body.get("text").and_then(Value::as_str).unwrap_or("").trim();
    if text.is_empty() {
        return (StatusCode::BAD_REQUEST, Json(json!({ "error": "text required" }))).into_response();
    }
    let skip_audio = body.get("skipAudio").and_then(Value::as_bool).unwrap_or(false);
    Json(app.voice.synthesize(text, skip_audio)).into_response()
}

/// 实时语音通话单轮对话：接收用户口述文本，驱动目标 Bot（或快速通话答复），并返回口语化摘要 + WAV 语音。
async fn voice_call_turn_post(State(app): State<Shared>, Json(body): Json<Value>) -> Response {
    let bot_id = body.get("botId").and_then(Value::as_str).unwrap_or("bot_cto").trim();
    let user_text = body.get("text").and_then(Value::as_str).unwrap_or("").trim();
    let skip_audio = body.get("skipAudio").and_then(Value::as_bool).unwrap_or(false);
    if user_text.is_empty() {
        return (StatusCode::BAD_REQUEST, Json(json!({ "error": "text required" }))).into_response();
    }
    let bot = app.bots.get_bot(bot_id).unwrap_or_else(|| bots::BotRecord {
        id: bot_id.to_string(),
        template_id: "tpl_grok_cos".to_string(),
        name: "Grok".to_string(),
        avatar: "preset:grok".to_string(),
        job_title: "幕僚长 · 总调度官".to_string(),
        department: "Grok 4.20 核心中枢".to_string(),
        lifecycle: "MANAGEMENT".to_string(),
        engine: model_policy::default_engine("tpl_grok_cos", app.beta_mode).into(),
        model: String::new(),
        thinking_effort: "high".to_string(),
        device: "pc".to_string(),
        cwd: String::new(),
        isolation_mode: "shared".to_string(),
        approval_mode: "ask".to_string(),
        public_description: String::new(),
        role_prompt: String::new(),
        instructions_version: 1,
        instructions_history: Vec::new(),
        manage_other_bots: true,
        daily_check_ins: true,
        disabled_tools: Vec::new(),
        example_requests: Vec::new(),
        requires: Vec::new(),
        skills: Vec::new(),
        default_routines: Vec::new(),
        open_work: Vec::new(),
        memories: Vec::new(),
        active_session_id: String::new(),
        session_ids: Vec::new(),
        archived: false,
        section: String::new(),
        pinned: true,
        created_by: "system".to_string(),
        last_text: String::new(),
        last_at: now_ms(),
    });

    // 若目标设备在线且要求触发底层会话，异步派发给对应引擎会话
    let trigger_agent = body.get("triggerSession").and_then(Value::as_bool).unwrap_or(true);
    let mut session_id = bot.active_session_id.clone();
    if trigger_agent {
        let prompt_with_voice_tag = format!(
            "[语音通话口述] {}\n（请先用 2~3 句简洁口语回答核心结论，方便直接语音播报。）",
            user_text
        );
        if session_id.is_empty() {
            let create_kind = format!("{}.create", bot.engine);
            let mut payload = json!({
                "cwd": bot.cwd,
                "text": prompt_with_voice_tag,
                "botId": bot.id,
            });
            if let Some(ctx) = app.bots.build_bot_context_prompt(&bot.id) {
                payload["botContext"] = ctx;
            }
            if let Ok(v) = call_agent(&app, &bot.device, &create_kind, payload, 8).await {
                if let Some(sid) = v.get("id").and_then(Value::as_str) {
                    session_id = sid.to_string();
                }
            }
        } else {
            let prompt_kind = format!("{}.prompt", bot.engine);
            let _ = call_agent(
                &app,
                &bot.device,
                &prompt_kind,
                json!({
                    "session": session_id,
                    "text": prompt_with_voice_tag,
                    "cwd": bot.cwd,
                    "botId": bot.id,
                }),
                6,
            )
            .await;
        }
    }

    let reply_text = body
        .get("replyOverride")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
        .unwrap_or_else(|| {
            format!(
                "我是{}（{}）。已收到你的语音指令：「{}」，正在工作区后台同步执行，你可以继续说话或稍后查看会话详情。",
                bot.name, bot.job_title, user_text
            )
        });

    let _ = app.bots.upsert_bot(&json!({
        "id": bot.id,
        "name": bot.name,
        "activeSessionId": session_id,
        "lastText": format!("📞 语音通话：{user_text}"),
    }));

    let mut tts = app.voice.synthesize(&reply_text, skip_audio);
    if let Some(obj) = tts.as_object_mut() {
        obj.insert("botId".into(), json!(bot.id));
        obj.insert("botName".into(), json!(bot.name));
        obj.insert("botJobTitle".into(), json!(bot.job_title));
        obj.insert("sessionId".into(), json!(session_id));
        obj.insert("replyText".into(), json!(reply_text));
    }
    Json(tts).into_response()
}

async fn session_interrupt(
    State(app): State<Shared>,
    Path((dev, sid)): Path<(String, String)>,
    ctx: Option<axum::Extension<auth::AuthCtx>>,
    body: Option<Json<Value>>,
) -> Response {
    let req_agent = body
        .as_ref()
        .and_then(|b| b.0.get("agent").and_then(|v| v.as_str()));
    let agent = match normalize_agent_kind(req_agent, &sid) {
        Ok(a) => a,
        Err(e) => return session_error(e),
    };
    let kind = format!("{agent}.interrupt");
    match call_agent(&app, &dev, &kind, json!({ "session": sid, "meterIdentity": agent_sessions::caller(ctx.as_ref().map(|c| &c.0)) }), 6).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}
/// GitHub-release-shaped manifest for the Android app's built-in updater.
// ---------------- TCP gateway (给手机的 tailnet 转发) ----------------

/// 手机在 App 里跑不了 tailscale，就让它把 100.x 的 CONNECT 发到这里：
/// 手机 → `CONNECT 100.105.59.31:8443` → PC（本来就在 tailnet 里）→ 目标。
/// mihomo 的 http 代理类型会带自定义头，鉴权用 X-Mesh-Token。
async fn tcp_gateway(app: Shared) {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let port: u16 = std::env::var("VIBELINK_GW_PORT").ok().and_then(|v| v.parse().ok()).unwrap_or(18996);
    let listener = match tokio::net::TcpListener::bind(("0.0.0.0", port)).await {
        Ok(l) => l,
        Err(e) => { println!("[hub] tcp gateway bind {port} failed: {e}"); return; }
    };
    println!("[hub] tcp gateway on :{port}");
    loop {
        let (mut sock, _) = match listener.accept().await { Ok(x) => x, Err(_) => continue };
        let app = app.clone();
        tokio::spawn(async move {
            let mut buf = vec![0u8; 8192];
            let n = match sock.read(&mut buf).await { Ok(n) => n, Err(_) => return };
            let head = String::from_utf8_lossy(&buf[..n]).to_string();
            let mut lines = head.lines();
            let first = lines.next().unwrap_or("").to_string();
            if !first.starts_with("CONNECT ") {
                let _ = sock.write_all(b"HTTP/1.1 405 Method Not Allowed\r\n\r\n").await;
                return;
            }
            let target = first.split_whitespace().nth(1).unwrap_or("").to_string();
            // CONNECT 头之后同一包里可能已经带了首段数据，不能丢
            let head_end = head.find("\r\n\r\n").map(|i| i + 4).unwrap_or(n);
            let rest = buf[head_end.min(n)..n].to_vec();
            println!("[hub] exit {target} rest={} total={}", rest.len(), n);
            let _ = lines;
            let ok = gateway_token_ok(&app, &head);
            if !ok {
                let _ = sock.write_all(b"HTTP/1.1 401 Unauthorized\r\n\r\n").await;
                return;
            }
            let mut upstream = match tokio::net::TcpStream::connect(&target).await {
                Ok(s) => s,
                Err(_) => { let _ = sock.write_all(b"HTTP/1.1 502 Bad Gateway\r\n\r\n").await; return; }
            };
            if sock.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n").await.is_err() { return; }
            if !rest.is_empty() && upstream.write_all(&rest).await.is_err() { return; }
            let _ = tokio::io::copy_bidirectional(&mut sock, &mut upstream).await;
        });
    }
}

/// 手机 → hub:18997 → 笔记本 mihomo(7897) → HY2 → 外网。
/// HY2 是 QUIC，手机侧没法经 TCP 网关拨号，所以整条出口链在 PC 上终结：
/// 手机侧 mihomo 只负责 TUN / DNS / 分流，出口由笔记本那套 HY2 提供。
async fn tcp_exit_gateway(app: Shared) {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let port: u16 = std::env::var("VIBELINK_EXIT_PORT").ok().and_then(|v| v.parse().ok()).unwrap_or(18997);
    let upstream = std::env::var("VIBELINK_EXIT_UPSTREAM")
        .unwrap_or_else(|_| "100.123.253.85:7897".to_string());
    let listener = match tokio::net::TcpListener::bind(("0.0.0.0", port)).await {
        Ok(l) => l,
        Err(e) => { println!("[hub] exit gateway bind {port} failed: {e}"); return; }
    };
    println!("[hub] exit gateway on :{port} -> {upstream}");
    loop {
        let (mut sock, _) = match listener.accept().await { Ok(x) => x, Err(_) => continue };
        let app = app.clone();
        let upstream = upstream.clone();
        tokio::spawn(async move {
            let mut buf = vec![0u8; 8192];
            let n = match sock.read(&mut buf).await { Ok(n) => n, Err(_) => return };
            let head = String::from_utf8_lossy(&buf[..n]).to_string();
            let mut lines = head.lines();
            let first = lines.next().unwrap_or("").to_string();
            if !first.starts_with("CONNECT ") {
                let _ = sock.write_all(b"HTTP/1.1 405 Method Not Allowed\r\n\r\n").await;
                return;
            }
            let target = first.split_whitespace().nth(1).unwrap_or("").to_string();
            let head_end = head.find("\r\n\r\n").map(|i| i + 4).unwrap_or(n);
            let rest = buf[head_end.min(n)..n].to_vec();
            println!("[hub] exit {target} rest={} total={}", rest.len(), n);
            let _ = lines;
            let ok = gateway_token_ok(&app, &head);
            if !ok {
                let _ = sock.write_all(b"HTTP/1.1 401 Unauthorized\r\n\r\n").await;
                return;
            }
            let mut up = match tokio::net::TcpStream::connect(&upstream).await {
                Ok(s) => s,
                Err(e) => {
                    println!("[hub] exit gateway upstream {upstream} failed: {e}");
                    let _ = sock.write_all(b"HTTP/1.1 502 Bad Gateway\r\n\r\n").await;
                    return;
                }
            };
            let req = format!("CONNECT {target} HTTP/1.1\r\nHost: {target}\r\n\r\n");
            if up.write_all(req.as_bytes()).await.is_err() { return; }
            // 逐字节读上游响应头，避免吞掉后续数据的第一个字节
            let mut resp = Vec::new();
            let mut one = [0u8; 1];
            loop {
                if up.read_exact(&mut one).await.is_err() { return; }
                resp.push(one[0]);
                if resp.ends_with(b"\r\n\r\n") || resp.len() > 4096 { break; }
            }
            if !(resp.starts_with(b"HTTP/1.1 200") || resp.starts_with(b"HTTP/1.0 200")) {
                let code = String::from_utf8_lossy(&resp).lines().next().unwrap_or("").to_string();
                println!("[hub] exit gateway {target} rejected: {code}");
                let _ = sock.write_all(b"HTTP/1.1 502 Bad Gateway\r\n\r\n").await;
                return;
            }
            if sock.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n").await.is_err() { return; }
            if !rest.is_empty() && up.write_all(&rest).await.is_err() { println!("[hub] exit {target} rest write failed"); return; }
            println!("[hub] exit {target} relay start");
            let r = tokio::io::copy_bidirectional(&mut sock, &mut up).await;
            println!("[hub] exit {target} closed: {r:?}");
        });
    }
}

// ---------------- Cindy Self-Hosted Relay proxy (/api/device-link/*) ----------------

fn cindy_relay_addr() -> String {
    std::env::var("CINDY_RELAY_UPSTREAM").unwrap_or_else(|_| "127.0.0.1:47820".to_string())
}

/// 将 `/api/device-link/*`（含 WebSocket `/api/device-link/ws` 与 REST 配对/证书/媒体接口）
/// 反向代理到本机 `@cindy/device-link-relay`（默认 127.0.0.1:47820）。
/// 这样手机端 Cindy 客户端可直接复用 vibelink-hub 的局域网、Tailscale 与 https://hub.vibelink.cloud 公网隧道。
async fn cindy_device_link_proxy(State(app): State<Shared>, mut req: Request) -> Response {
    if app.beta_mode {
        return (StatusCode::NOT_FOUND, Json(json!({"error": "not available on public beta hub"}))).into_response();
    }
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let relay_addr = cindy_relay_addr();
    let pq = req
        .uri()
        .path_and_query()
        .map(|x| x.as_str())
        .unwrap_or("/api/device-link/relay/info")
        .to_string();

    let is_ws = req
        .headers()
        .get(axum::http::header::UPGRADE)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.eq_ignore_ascii_case("websocket"))
        .unwrap_or(false);

    if is_ws {
        let mut upstream = match tokio::net::TcpStream::connect(&relay_addr).await {
            Ok(s) => s,
            Err(e) => {
                return (
                    StatusCode::BAD_GATEWAY,
                    Json(json!({"error": format!("cindy relay unreachable ({relay_addr}): {e}")})),
                )
                    .into_response();
            }
        };

        let mut raw_req = format!("{} {pq} HTTP/1.1\r\nHost: {relay_addr}\r\n", req.method());
        for (name, val) in req.headers() {
            if name == axum::http::header::HOST
                || name.as_str().eq_ignore_ascii_case("x-forwarded-for")
                || name.as_str().eq_ignore_ascii_case("x-vibelink-proxied")
            {
                continue;
            }
            if let Ok(s) = val.to_str() {
                raw_req.push_str(&format!("{}: {s}\r\n", name.as_str()));
            }
        }
        raw_req.push_str("x-vibelink-proxied: 1\r\n");
        if let Some(ip) = req.headers().get("cf-connecting-ip").and_then(|v| v.to_str().ok()) {
            raw_req.push_str(&format!("x-forwarded-for: {ip}\r\n"));
        }
        raw_req.push_str("\r\n");
        if upstream.write_all(raw_req.as_bytes()).await.is_err() {
            return (StatusCode::BAD_GATEWAY, "cindy relay ws write failed").into_response();
        }

        let mut resp_buf = Vec::new();
        let mut one = [0u8; 1];
        loop {
            if upstream.read_exact(&mut one).await.is_err() {
                return (StatusCode::BAD_GATEWAY, "cindy relay ws handshake EOF").into_response();
            }
            resp_buf.push(one[0]);
            if resp_buf.ends_with(b"\r\n\r\n") || resp_buf.len() > 8192 {
                break;
            }
        }

        let head_str = String::from_utf8_lossy(&resp_buf);
        let mut lines = head_str.lines();
        let status_line = lines.next().unwrap_or("");
        let status_code = status_line
            .split_whitespace()
            .nth(1)
            .and_then(|s| s.parse::<u16>().ok())
            .and_then(|c| StatusCode::from_u16(c).ok())
            .unwrap_or(StatusCode::BAD_GATEWAY);

        if status_code != StatusCode::SWITCHING_PROTOCOLS {
            return (
                status_code,
                Json(json!({"error": format!("cindy relay ws rejected: {status_line}")})),
            )
                .into_response();
        }

        let mut resp_headers = Vec::new();
        for line in lines {
            let line = line.trim();
            if line.is_empty() {
                continue;
            }
            if let Some((k, v)) = line.split_once(':') {
                resp_headers.push((k.trim().to_string(), v.trim().to_string()));
            }
        }

        let on_upgrade = hyper::upgrade::on(&mut req);
        tokio::spawn(async move {
            if let Ok(upgraded) = on_upgrade.await {
                let mut client_io = hyper_util::rt::TokioIo::new(upgraded);
                let _ = tokio::io::copy_bidirectional(&mut client_io, &mut upstream).await;
            }
        });

        let mut builder = Response::builder().status(StatusCode::SWITCHING_PROTOCOLS);
        for (k, v) in resp_headers {
            builder = builder.header(k, v);
        }
        return builder
            .body(Body::empty())
            .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response());
    }

    let (parts, body) = req.into_parts();
    let Ok(bytes) = axum::body::to_bytes(body, 64 * 1024 * 1024).await else {
        return (StatusCode::PAYLOAD_TOO_LARGE, Json(json!({"error": "body too large"}))).into_response();
    };
    let url = format!("http://{relay_addr}{pq}");
    let mut out = app
        .http
        .request(parts.method.clone(), &url)
        .timeout(std::time::Duration::from_secs(35));
    for (k, v) in &parts.headers {
        if k == axum::http::header::HOST
            || k == axum::http::header::CONTENT_LENGTH
            || k.as_str().eq_ignore_ascii_case("x-forwarded-for")
            || k.as_str().eq_ignore_ascii_case("x-vibelink-proxied")
        {
            continue;
        }
        out = out.header(k, v);
    }
    if let Some(host) = parts.headers.get(axum::http::header::HOST) {
        out = out.header("x-forwarded-host", host);
    }
    // 中继靠回环地址判断「本机调用」；经 hub 转发的请求必须带上标记，让中继拒绝本机专用接口。
    out = out.header("x-vibelink-proxied", "1");
    if let Some(ip) = parts.headers.get("cf-connecting-ip") {
        out = out.header("x-forwarded-for", ip);
    }
    if !bytes.is_empty() {
        out = out.body(bytes);
    }
    match out.send().await {
        Ok(resp) => {
            let status = resp.status();
            let mut builder = Response::builder().status(status);
            for (k, v) in resp.headers() {
                if k == axum::http::header::TRANSFER_ENCODING || k == axum::http::header::CONNECTION {
                    continue;
                }
                builder = builder.header(k, v);
            }
            match builder.body(Body::from_stream(resp.bytes_stream())) {
                Ok(r) => r,
                Err(e) => (StatusCode::BAD_GATEWAY, e.to_string()).into_response(),
            }
        }
        Err(e) => (
            StatusCode::BAD_GATEWAY,
            Json(json!({"error": format!("cindy relay unreachable ({relay_addr}): {e}")})),
        )
            .into_response(),
    }
}

/// Rewrite the `u=` base URL inside a `cindy://relay/join?...` link so it matches the
/// exact origin (LAN / Tailscale / https://hub.vibelink.cloud) the phone used to reach the hub.
fn rewrite_join_link_base(link: &str, new_base: &str) -> String {
    let Some((prefix, query)) = link.split_once('?') else {
        return link.to_string();
    };
    let encoded_base: String = new_base
        .bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => (b as char).to_string(),
            _ => format!("%{:02X}", b),
        })
        .collect();
    let mut parts_out = Vec::new();
    let mut replaced = false;
    for part in query.split('&') {
        if part.starts_with("u=") {
            parts_out.push(format!("u={encoded_base}"));
            replaced = true;
        } else {
            parts_out.push(part.to_string());
        }
    }
    if !replaced {
        parts_out.insert(0, format!("u={encoded_base}"));
    }
    format!("{prefix}?{}", parts_out.join("&"))
}

async fn cindy_join_link_get(State(app): State<Shared>, headers: axum::http::HeaderMap) -> Response {
    if app.beta_mode {
        return (StatusCode::NOT_FOUND, Json(json!({"error": "not available on public beta hub"}))).into_response();
    }
    let relay_addr = cindy_relay_addr();
    let host = headers
        .get("x-forwarded-host")
        .or_else(|| headers.get("host"))
        .and_then(|v| v.to_str().ok())
        .unwrap_or("127.0.0.1:18995");
    let proto = headers
        .get("x-forwarded-proto")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("http");
    let caller_base = format!("{proto}://{host}");

    let info_url = format!("http://{relay_addr}/api/device-link/relay/info");
    let relay_info = match app.http.get(&info_url).send().await {
        Ok(r) if r.status().is_success() => r
            .bytes()
            .await
            .ok()
            .and_then(|b| serde_json::from_slice::<Value>(&b).ok()),
        _ => None,
    };

    // If local helper exposes /api/device-link/relay/local-join-link, ask it to mint a fresh one
    let fresh_url = format!(
        "http://{relay_addr}/api/device-link/relay/local-join-link?base={}",
        caller_base
    );
    if let Ok(r) = app.http.post(&fresh_url).send().await {
        if r.status().is_success() {
            if let Ok(b) = r.bytes().await {
                if let Ok(mut v) = serde_json::from_slice::<Value>(&b) {
                    if let Some(link) = v.get("link").and_then(|x| x.as_str()).map(|s| s.to_string()) {
                        v["link"] = json!(rewrite_join_link_base(&link, &caller_base));
                    }
                    v["relayOnline"] = json!(true);
                    v["relayBaseUrl"] = json!(caller_base);
                    return Json(v).into_response();
                }
            }
        }
    }

    let file = app.data.join("cindy-join.json");
    if let Ok(raw) = fs::read_to_string(&file) {
        if let Ok(mut v) = serde_json::from_str::<Value>(&raw) {
            if let Some(link) = v.get("link").and_then(|x| x.as_str()).map(|s| s.to_string()) {
                v["link"] = json!(rewrite_join_link_base(&link, &caller_base));
            }
            v["relayOnline"] = json!(relay_info.is_some());
            v["relayBaseUrl"] = json!(caller_base);
            if let Some(info) = relay_info {
                v["relayInfo"] = info;
            }
            return Json(v).into_response();
        }
    }

    Json(json!({
        "relayOnline": relay_info.is_some(),
        "relayBaseUrl": caller_base,
        "relayInfo": relay_info,
        "link": null,
        "error": "no active join link; run start-cindy.ps1 or generate one in Cindy Desktop"
    }))
    .into_response()
}

async fn cindy_join_link_post(State(app): State<Shared>, Json(body): Json<Value>) -> Response {
    if app.beta_mode {
        return (StatusCode::NOT_FOUND, Json(json!({"error": "not available on public beta hub"}))).into_response();
    }
    let link = body.get("link").and_then(|v| v.as_str()).unwrap_or("").trim();
    if link.is_empty() {
        return (StatusCode::BAD_REQUEST, Json(json!({"error": "link required"}))).into_response();
    }
    let record = json!({
        "link": link,
        "expiresAt": body.get("expiresAt").cloned().unwrap_or(json!(now_ms() + 10 * 60_000)),
        "updatedAt": now_ms(),
    });
    write_atomic(
        &app.data.join("cindy-join.json"),
        serde_json::to_string_pretty(&record).unwrap_or_default().as_bytes(),
    );
    Json(json!({"ok": true, "saved": record})).into_response()
}
// ---------------- agent binary distribution ----------------

/// What the agent asks on startup: is there a newer build for my OS/arch?
async fn agent_latest(State(app): State<Shared>, Query(q): Query<HashMap<String, String>>) -> Response {
    let target = q.get("target").cloned().unwrap_or_default();
    if target.is_empty() || !target.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_') {
        return (StatusCode::BAD_REQUEST, Json(json!({ "error": "bad target" }))).into_response();
    }
    let dir = app.data.join("agent");
    let Ok(raw) = fs::read_to_string(dir.join(format!("{target}.json"))) else {
        return (StatusCode::NOT_FOUND, Json(json!({ "error": "no build for this target" }))).into_response();
    };
    let Ok(mut meta) = serde_json::from_str::<Value>(&raw) else {
        return (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": "bad meta" }))).into_response();
    };
    let file = meta.get("file").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let size = fs::metadata(dir.join(&file)).map(|m| m.len()).unwrap_or(0);
    meta["path"] = json!(format!("/agent/{file}"));
    meta["size"] = json!(size);
    Json(meta).into_response()
}

async fn agent_file(State(app): State<Shared>, Path(name): Path<String>) -> Response {
    if !name.chars().all(|c| c.is_ascii_alphanumeric() || "._-".contains(c)) {
        return (StatusCode::BAD_REQUEST, "bad name").into_response();
    }
    match fs::read(app.data.join("agent").join(&name)) {
        Ok(bytes) => (
            StatusCode::OK,
            [(axum::http::header::CONTENT_TYPE, "application/octet-stream")],
            bytes,
        )
            .into_response(),
        Err(_) => (StatusCode::NOT_FOUND, "no such agent build").into_response(),
    }
}

fn apk_dir(app: &App, query: &HashMap<String, String>) -> PathBuf {
    match query.get("edition").map(String::as_str) {
        Some("beta") => app.data.parent().unwrap_or(&app.data).join("beta/apk"),
        Some("dev") => app.data.parent().unwrap_or(&app.data).join("dev/apk"),
        _ => app.data.join("apk"),
    }
}

async fn apk_latest(State(app): State<Shared>, Query(mut q): Query<HashMap<String, String>>, headers: axum::http::HeaderMap) -> Response {
    // Existing developer APKs omit edition even when checking the public beta Hub.
    // Keep explicit selection authoritative; use the app UA only for legacy clients.
    if !q.contains_key("edition") {
        if let Some(version) = headers.get("user-agent").and_then(|h| h.to_str().ok())
            .and_then(|ua| ua.strip_prefix("VibeLink/")) {
            q.insert("edition".into(), if version.trim().ends_with("-beta") {"beta"} else {"dev"}.into());
        }
    }
    let dir = apk_dir(&app, &q);
    let Ok(raw) = fs::read_to_string(dir.join("meta.json")) else {
        return (StatusCode::NOT_FOUND, Json(json!({"error": "no apk published"}))).into_response();
    };
    let Ok(meta) = serde_json::from_str::<serde_json::Value>(&raw) else {
        return (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({"error": "bad meta.json"}))).into_response();
    };
    let file = meta.get("file").and_then(|v| v.as_str()).unwrap_or_default().to_string();
    if file.is_empty() || !dir.join(&file).exists() {
        return (StatusCode::NOT_FOUND, Json(json!({"error": "apk file missing"}))).into_response();
    }
    let host = headers
        .get("host")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("127.0.0.1:18995")
        .to_string();
    // 经 Cloudflare 隧道时是 https，按 x-forwarded-proto 来，别给手机一个 http 地址
    let proto = headers
        .get("x-forwarded-proto")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("http");
    // 安装包不含秘密，下载地址不再带 token
    let edition = if q.get("edition").is_some_and(|e| e == "beta") || (q.get("edition").is_none() && app.beta_mode) { "beta" } else { "dev" };
    let url = format!("{proto}://{host}/apk/{file}?edition={edition}");
    let size = fs::metadata(dir.join(&file)).map(|m| m.len()).unwrap_or(0);
    let tag_version = meta
        .get("version")
        .and_then(|v| v.as_str())
        .or_else(|| meta.get("version_name").and_then(|v| v.as_str()))
        .map(|s| if s.starts_with('v') { s.to_string() } else { format!("v{s}") })
        .unwrap_or_else(|| "v0.0.0".to_string());
    let body_notes = meta
        .get("notes")
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty())
        .or_else(|| meta.get("changelog").and_then(|v| v.as_str()))
        .unwrap_or_default()
        .to_string();
    Json(json!({
        "edition": edition,
        "tag_name": tag_version,
        "version": tag_version,
        "version_name": meta.get("version_name").cloned().unwrap_or(json!(tag_version.trim_start_matches('v'))),
        "version_code": meta.get("version_code").cloned().unwrap_or(json!(0)),
        "sha256": meta.get("sha256").cloned().unwrap_or(json!("")),
        "name": meta.get("name").cloned().unwrap_or(json!(format!("VibeLink {}", tag_version))),
        "body": body_notes,
        "notes": body_notes,
        "published_at": meta.get("date").cloned().unwrap_or(json!("")),
        "html_url": url,
        "assets": [{
            "name": file,
            "browser_download_url": url,
            "size": size
        }]
    }))
    .into_response()
}

/// `GET /apk` → 302 到最新的 APK（带版本号文件名 + 时间戳防缓存 + token）。给手机上手输用。
async fn apk_short(State(app): State<Shared>) -> Response {
    let raw = fs::read_to_string(app.data.join("apk").join("meta.json")).unwrap_or_default();
    let file = serde_json::from_str::<Value>(&raw)
        .ok()
        .and_then(|v| v.get("file").and_then(|x| x.as_str()).map(|s| s.to_string()))
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "latest.apk".to_string());
    let location = format!("/apk/{file}?ts={}", now_ms());
    (
        StatusCode::FOUND,
        [
            (axum::http::header::LOCATION, location),
            (axum::http::header::CACHE_CONTROL, "no-store, no-cache, must-revalidate".to_string()),
        ],
    )
        .into_response()
}
async fn apk_file(State(app): State<Shared>, Path(name): Path<String>, Query(q): Query<HashMap<String, String>>) -> Response {
    let dir = apk_dir(&app, &q);
    if !name.chars().all(|c| c.is_ascii_alphanumeric() || "._-".contains(c)) {
        return (StatusCode::BAD_REQUEST, "bad name").into_response();
    }
    // `/apk/latest.apk` 永远指向 meta.json 里的当前版本：手机上可以存一个固定地址，
    // 不用每次改文件名。
    let name = if name == "latest.apk" || name == "latest" {
        let raw = fs::read_to_string(dir.join("meta.json")).unwrap_or_default();
        serde_json::from_str::<Value>(&raw)
            .ok()
            .and_then(|v| v.get("file").and_then(|x| x.as_str()).map(|s| s.to_string()))
            .unwrap_or(name)
    } else {
        name
    };
    match fs::read(dir.join(&name)) {
        Ok(bytes) => (
            StatusCode::OK,
            [
                (axum::http::header::CONTENT_TYPE, "application/vnd.android.package-archive".to_string()),
                (axum::http::header::CACHE_CONTROL, "no-store, no-cache, must-revalidate".to_string()),
                (axum::http::header::CONTENT_DISPOSITION, format!("attachment; filename=\"{name}\"")),
            ],
            bytes,
        )
            .into_response(),
        Err(_) => (StatusCode::NOT_FOUND, "no such apk").into_response(),
    }
}
async fn phone(State(app): State<Shared>, Path(name): Path<String>) -> Response {
    let file = config_dir(&app, &name).join("latest");
    match fs::read_to_string(file) {
        Ok(body) => (
            StatusCode::OK,
            [(axum::http::header::CONTENT_TYPE, "text/yaml; charset=utf-8")],
            body,
        )
            .into_response(),
        Err(_) => (StatusCode::NOT_FOUND, "no such config").into_response(),
    }
}

async fn beta_activate_post(State(app): State<Shared>, Json(body): Json<Value>) -> Response {
    let code = body.get("code").and_then(|v| v.as_str()).unwrap_or("");
    let device = body.get("device").and_then(|v| v.as_str()).unwrap_or("mobile");
    match app.tenants.activate(code, device) {
        Ok(t) => {
            let identity=model_usage::Identity{user_id:t.tenant_id.clone(),label:t.label.clone(),beta:true};
            let usage=app.usage.summary(&identity,&model_usage::day_at(now_ms()));
            let mut response=json!({
            "ok": true,
            "tenantId": t.tenant_id,
            "label": t.label,
            "token": t.client_token,
            "agentToken": t.agent_token,
            "hubUrl": "https://hub.vibelink.cloud",
            "model": model_policy::DEEPSEEK_MODEL,
            "modelDisplayName": "DeepSeek V4.1 Flash (内测免费赠送)",
            "dailyLimitRequests": t.daily_limit_requests,
            "usedRequests": t.used_requests,
            "dailyLimitChars": t.daily_limit_chars,
            "usedChars": t.used_chars,
        });
        for field in ["usedRequests","dailyLimitRequests","usedChars","dailyLimitChars","totalTokens","inputTokens","outputTokens","cachedTokens","estimatedTokens","dailyLimitTokens"] {response[field]=usage[field].clone();}
        Json(response).into_response()
        },
        Err(e) => (StatusCode::FORBIDDEN, Json(json!({"ok": false, "error": e}))).into_response(),
    }
}

async fn beta_quota_get(
    State(app): State<Shared>,
    ctx: Option<axum::Extension<auth::AuthCtx>>,
) -> Response {
    let Some(axum::Extension(c)) = ctx else {
        return (StatusCode::UNAUTHORIZED, Json(json!({"error": "unauthorized"}))).into_response();
    };
    let identity=model_gateway::identity(&c);
    let mut data=app.usage.summary(&identity,&model_usage::day_at(now_ms()));
    data["tenantId"]=json!(identity.user_id);
    Json(data).into_response()
}

async fn beta_tenants_get(State(app): State<Shared>) -> Response {
    Json(json!({
        "betaMode": app.beta_mode,
        "tenants": app.tenants.list_summary().into_iter().map(|mut row|{
            let identity=model_usage::Identity{user_id:row["tenantId"].as_str().unwrap_or("").into(),label:row["label"].as_str().unwrap_or("").into(),beta:true};
            let usage=app.usage.summary(&identity,&model_usage::day_at(now_ms()));
            if let Some(object)=usage.as_object(){for(k,v)in object{row[k]=v.clone();}}row
        }).collect::<Vec<_>>(),
    }))
    .into_response()
}

async fn usage_self(State(app): State<Shared>, axum::Extension(ctx):axum::Extension<auth::AuthCtx>) -> Json<Value> {
    Json(app.usage.summary(&model_gateway::identity(&ctx), &model_usage::day_at(now_ms())))
}
async fn usage_users(State(app):State<Shared>, Query(q):Query<HashMap<String,String>>) -> Json<Value> {
    Json(app.usage.users(q.get("day").map(String::as_str).unwrap_or(&model_usage::day_at(now_ms()))))
}
async fn usage_requests(State(app):State<Shared>) -> Json<Value> {Json(app.usage.recent())}
async fn usage_limits(State(app):State<Shared>, Json(body):Json<Value>) -> Response {
    let result=serde_json::from_value::<model_usage::Limits>(body["limits"].clone()).map_err(|e|e.to_string())
        .and_then(|limits|app.usage.set_limits(body["userId"].as_str().unwrap_or(""),limits));
    match result {Ok(())=>Json(json!({"ok":true})).into_response(),Err(e)=>(StatusCode::BAD_REQUEST,Json(json!({"error":e}))).into_response()}
}

#[tokio::main]
async fn main() {
    let base_data = PathBuf::from(std::env::var("VIBELINK_DATA").unwrap_or_else(|_| "data".into()));
    let beta_mode = std::env::var("VIBELINK_MODE")
        .map(|v| v.eq_ignore_ascii_case("beta"))
        .unwrap_or(false)
        || std::env::args().any(|a| a == "--beta");
    if !beta_mode { model_policy::migrate_legacy(&base_data).expect("migrate legacy development data"); }
    let data = model_policy::data_dir(base_data, beta_mode);
    let _ = fs::create_dir_all(data.join("configs"));

    let (auth, admin_token) = auth::Auth::load(data.clone());
    if cli(&admin_token).await {
        return;
    }

    let tenants_store = tenants::BetaTenantStore::load(data.clone(), &auth);
    let store = load(&data);
    let bots_store = bots::BotFleetStore::load_for_edition(data.clone(), beta_mode);
    let approvals_gate = approvals::ApprovalGate::load(data.clone());
    let voice_svc = voice::VoiceService::load(data.clone());
    let app = Arc::new(App {
        auth: Arc::new(auth),
        tenants: Arc::new(tenants_store),
        beta_mode,
        bots: bots_store,
        approvals: approvals_gate,
        voice: voice_svc,
        bindings: agent_sessions::Sessions::load(data.clone()),
        usage: Arc::new(model_usage::UsageStore::load(data.clone()).expect("load model usage ledger")),
        grants: Arc::new(model_gateway::Grants::default()),
        magpie: model_gateway::upstream(),
        tenant_id: None,
        tenant_apps: Mutex::new(HashMap::new()),
        data: data.clone(),
        store: Mutex::new(store),
        policy: Mutex::new(load_policy(&data)),
        sessions: Arc::new(Mutex::new(SessionPlane::default())),
        http: reqwest::Client::builder()
            .no_proxy()
            .connect_timeout(std::time::Duration::from_secs(5))
            .build()
            .unwrap_or_default(),
    });

    app.usage.import_legacy(app.tenants.list_summary()).expect("migrate tenant usage");

    let router = api_router(app.clone()).layer(middleware::from_fn_with_state(app.clone(), auth_mw));

    let port: u16 = std::env::var("VIBELINK_PORT").ok().and_then(|v| v.parse().ok()).unwrap_or(18995);
    policy::spawn(app.clone());
    if !beta_mode {
        tokio::spawn(tcp_gateway(app.clone()));
        tokio::spawn(tcp_exit_gateway(app.clone()));
    }
    let listener = tokio::net::TcpListener::bind(("0.0.0.0", port)).await.expect("bind");
    println!(
        "[hub] v{VERSION} (mode={}) on http://0.0.0.0:{port}",
        if beta_mode { "beta" } else { "dev" }
    );
    println!("[hub] admin token: <data>/token.txt");
    println!("[hub] data:  {}", data.display());
    axum::serve(listener, router).await.expect("serve");
}

fn api_router(app: Shared) -> Router {
    Router::new()
        .route("/api/health", get(health))
        .route("/api/tokens", get(tokens_list).post(tokens_issue))
        .route("/api/tokens/rotate-admin", post(tokens_rotate_admin))
        .route("/api/tokens/{id}", axum::routing::delete(tokens_revoke))
        .route("/api/pair/start", post(pair_start))
        .route("/api/pair/status/{id}", get(pair_status))
        .route("/api/pair", post(pair_redeem))
        .route("/api/pair/scan", post(pair_scan))
        .route("/api/policy", get(get_policy).post(put_policy))
        .route("/api/ingest", post(ingest))
        .route("/api/devices", get(devices))
        .route("/api/device/{id}", get(device))
        .route("/api/events", get(events))
        .route("/api/commands", get(list_commands).post(create_command))
        .route("/api/commands/{id}/claim", post(claim_command))
        .route("/api/commands/result", post(command_result))
        .route("/api/configs", get(list_configs))
        .route("/api/config/{name}", get(get_config).post(put_config))
        .route("/phone/{name}", get(phone))
        .route("/api/apk/latest", get(apk_latest))
        .route("/api/llm/{*rest}", any(model_gateway::proxy))
        .route("/api/device-link/{*rest}", any(cindy_device_link_proxy))
        .route("/api/cindy/join-link", get(cindy_join_link_get).post(cindy_join_link_post))
        .route("/api/agent/latest", get(agent_latest))
        .route("/agent/{name}", get(agent_file))
        .route("/api/agent/session-requests", get(agent_session_requests))
        .route("/api/agent/session-result", post(agent_session_result))
        .route("/api/sessions", get(sessions_list))
        .route("/api/sessions/all", get(sessions_all))
        .route("/api/sessions/{dev}/create", post(session_create))
        .route("/api/sessions/{dev}/{agent}/{sid}/messages", get(session_messages_by_agent))
        .route("/api/sessions/{dev}/{sid}/messages", get(session_messages))
        .route("/api/sessions/{dev}/{sid}/prompt", post(session_prompt))
        .route("/api/sessions/{dev}/{sid}/interrupt", post(session_interrupt))
        .route("/api/workspace/{dev}/tree", get(workspace_tree))
        .route("/api/workspace/{dev}/file", get(workspace_file))
        .route("/api/workspace/{dev}/upload", post(workspace_upload))
        .route("/api/workspace/{dev}/git", get(workspace_git))
        .route("/api/desktop/{dev}/frame", get(desktop_frame))
        .route("/api/desktop/{dev}/input", post(desktop_input))
        .route("/api/desktop/{dev}/lease", get(desktop_lease_get).post(desktop_lease_post))
        .route("/api/terminal/{dev}", get(terminal_get).post(terminal_post))
        .route("/api/worktree/{dev}", get(worktree_get).post(worktree_post))
        .route("/api/scheduler/{dev}", get(scheduler_get).post(scheduler_post))
        .route("/api/plugins/{dev}", get(plugins_get).post(plugins_post))
        .route("/api/bots", get(bots_list).post(bots_upsert))
        .route("/api/bots/templates", get(bots_templates_get))
        .route("/api/bots/apply-preset", post(bots_apply_preset_post))
        .route("/api/bots/import-recipe", post(bots_import_recipe_post))
        .route("/api/bots/{id}", get(bot_get).patch(bots_upsert))
        .route("/api/bots/{id}/recipe", get(bot_recipe_export_get))
        .route("/api/bots/{id}/context", get(bot_context_get))
        .route("/api/bots/{id}/skills/learn", post(bot_skills_learn_post))
        .route("/api/bots/{id}/memories", post(bot_memories_post))
        .route("/api/bots/{id}/memories/{mem_id}", axum::routing::delete(bot_memories_delete))
        .route("/api/bot-groups", post(bot_groups_post))
        .route("/api/bot-groups/{id}/messages", get(bot_group_messages_get).post(bot_group_messages_post))
        .route("/api/fleet/action", post(fleet_action_post))
        .route("/api/approvals", get(approvals_list))
        .route("/api/approvals/policy", get(approvals_policy_get).post(approvals_policy_post))
        .route("/api/approvals/request", post(approvals_request_post))
        .route("/api/approvals/{id}/decide", post(approvals_decide_post))
        .route("/api/voice/config", get(voice_config_get).post(voice_config_post))
        .route("/api/voice/tts", post(voice_tts_post))
        .route("/api/voice/call/turn", post(voice_call_turn_post))
        .route("/api/beta/activate", post(beta_activate_post))
        .route("/api/beta/quota", get(beta_quota_get))
        .route("/api/beta/tenants", get(beta_tenants_get))
        .route("/api/usage", get(usage_self))
        .route("/api/usage/users", get(usage_users))
        .route("/api/usage/requests", get(usage_requests))
        .route("/api/usage/limits", post(usage_limits))
        .route("/apk", get(apk_short))
        .route("/apk/{name}", get(apk_file))
        .layer(DefaultBodyLimit::max(64 * 1024 * 1024))
        .with_state(app)

}

// keep BTreeMap import used even if protocol changes
#[allow(dead_code)]
fn _unused(_: BTreeMap<String, bool>) {}

// keep ConfigInfo linked for API compatibility
#[allow(dead_code)]
fn _cfg(_: ConfigInfo) {}



















// ---------------- CLI ----------------

/// `vibelink-hub token issue <name> <client|agent>` / `token list` / `token revoke <id>` /
/// `token rotate-admin` / `pair [name]`：调用本机正在运行的 hub（用 data/token.txt 的管理员 token）。
/// 没有子命令就返回 false，照常启动 hub。
async fn cli(admin: &str) -> bool {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.is_empty() || args[0].starts_with("--") {
        return false;
    }
    let port: u16 = std::env::var("VIBELINK_PORT").ok().and_then(|v| v.parse().ok()).unwrap_or(18995);
    let base = format!("http://127.0.0.1:{port}");
    let http = reqwest::Client::builder().no_proxy().build().unwrap_or_default();
    let a: Vec<&str> = args.iter().map(|s| s.as_str()).collect();
    let req = match a.as_slice() {
        ["token", "list"] => http.get(format!("{base}/api/tokens")),
        ["token", "issue", name, scope] => {
            http.post(format!("{base}/api/tokens")).json(&json!({"name": name, "scope": scope}))
        }
        ["token", "revoke", id] => http.delete(format!("{base}/api/tokens/{id}")),
        ["token", "rotate-admin"] => http.post(format!("{base}/api/tokens/rotate-admin")),
        ["pair"] => http.post(format!("{base}/api/pair/start")).json(&json!({"name": "phone"})),
        ["pair", name] => http.post(format!("{base}/api/pair/start")).json(&json!({"name": name})),
        ["pair-qr"] | ["pair-qr", _] => {
            let name = a.get(1).copied().unwrap_or("phone");
            let r = http
                .post(format!("{base}/api/pair/start"))
                .header("x-mesh-token", admin)
                .json(&json!({"name": name}))
                .send()
                .await;
            let v: Value = match r {
                Ok(r) if r.status().is_success() => r.json().await.unwrap_or(Value::Null),
                Ok(r) => {
                    eprintln!("hub refused: HTTP {}", r.status());
                    std::process::exit(1);
                }
                Err(e) => {
                    eprintln!("hub not reachable at {base}: {e}");
                    std::process::exit(1);
                }
            };
            let link = v.get("link").and_then(|x| x.as_str()).unwrap_or("");
            print!("{}", qr_to_terminal(link));
            println!("用手机 VibeLink「远程会话 › 扫码连接」扫上面的二维码，再在手机上点「连接」（10 分钟内有效，一次性）。");
            println!("手机试的地址：");
            for u in v.get("urls").and_then(|x| x.as_array()).cloned().unwrap_or_default() {
                println!("  {}", u.as_str().unwrap_or(""));
            }
            return true;
        }
        _ => {
            eprintln!(
                "usage: vibelink-hub [token list | token issue <name> <client|agent> | token revoke <id> | token rotate-admin | pair [name] | pair-qr [name]]"
            );
            std::process::exit(2);
        }
    };
    match req.header("x-mesh-token", admin).send().await {
        Ok(r) => {
            let status = r.status();
            let body = r.text().await.unwrap_or_default();
            println!("{body}");
            if !status.is_success() {
                std::process::exit(1);
            }
        }
        Err(e) => {
            eprintln!("hub not reachable at {base}: {e}");
            std::process::exit(1);
        }
    }
    true
}

#[cfg(test)]
mod runtime_tests;

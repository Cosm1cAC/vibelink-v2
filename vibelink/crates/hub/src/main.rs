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
mod policy;
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
    token: String,
    data: PathBuf,
    store: Mutex<Store>,
    policy: Mutex<policy::Policy>,
    sessions: Mutex<SessionPlane>,
    http: reqwest::Client,
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
async fn auth(State(app): State<Shared>, req: Request, next: Next) -> Response {
    let path = req.uri().path().to_string();
    // /apk 是给手机手输的短地址：不带 token，302 到带 token 的最新包
    if path == "/api/health" || path == "/apk" {
        return next.run(req).await;
    }
    let header_ok = req
        .headers()
        .get("x-mesh-token")
        .and_then(|v| v.to_str().ok())
        .map(|v| v == app.token)
        .unwrap_or(false);
    let query_ok = req
        .uri()
        .query()
        .map(|q| q.split('&').any(|kv| kv == format!("t={}", app.token)))
        .unwrap_or(false);
    let bearer_ok = req
        .headers()
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.split_once(' '))
        .map(|(scheme, tok)| scheme.eq_ignore_ascii_case("bearer") && tok.trim() == app.token)
        .unwrap_or(false);
    if !header_ok && !query_ok && !bearer_ok {
        return (StatusCode::UNAUTHORIZED, Json(json!({"error": "unauthorized"}))).into_response();
    }
    next.run(req).await
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

async fn ingest(State(app): State<Shared>, Json(report): Json<DeviceReport>) -> Response {
    if report.id.trim().is_empty() {
        return (StatusCode::BAD_REQUEST, Json(json!({"error": "id required"}))).into_response();
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

async fn devices(State(app): State<Shared>) -> Json<Value> {
    let store = app.store.lock().unwrap();
    let mut list: Vec<Device> = store.devices.values().cloned().map(view).collect();
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
    let (id, rx) = {
        let mut s = app.sessions.lock().unwrap();
        s.seq += 1;
        let id = format!("q{:x}{:x}", now_ms(), s.seq);
        let (tx, rx) = tokio::sync::oneshot::channel();
        s.waiters.insert(id.clone(), tx);
        s.queue
            .entry(device.to_string())
            .or_default()
            .push(json!({ "id": id, "kind": kind, "payload": payload }));
        (id, rx)
    };
    match tokio::time::timeout(std::time::Duration::from_secs(secs), rx).await {
        Ok(Ok(Ok(v))) => Ok(v),
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
    let mut s = app.sessions.lock().unwrap();
    let list = s.queue.remove(&device).unwrap_or_default();
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
    match call_agent(&app, &device, "opencode.list", json!({}), 6).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

/// 所有 agent 的会话（opencode / Codex / Claude / Antigravity），手机端先选 agent 再进会话。
async fn sessions_all(State(app): State<Shared>, Query(q): Query<HashMap<String, String>>) -> Response {
    let device = q.get("device").cloned().unwrap_or_default();
    if device.is_empty() {
        return session_error("device required".into());
    }
    match call_agent(&app, &device, "sessions.list", json!({}), 15).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

/// 按 agent 取消息：opencode 走本地服务，Codex/Claude 读各自的 jsonl。
async fn session_messages_by_agent(
    State(app): State<Shared>,
    Path((dev, agent, sid)): Path<(String, String, String)>,
    Query(q): Query<HashMap<String, String>>,
) -> Response {
    let kind = match agent.as_str() {
        "opencode" | "codex" | "claude" => format!("{agent}.messages"),
        other => return session_error(format!("unknown agent {other}")),
    };
    // 只把 cursor 传下去；opencode 的 cursor 不能和 order 同时出现，order 由 agent 决定
    let cursor = q.get("cursor").cloned().unwrap_or_default();
    match call_agent(&app, &dev, &kind, json!({ "session": sid, "cursor": cursor }), 15).await {
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

async fn session_prompt(
    State(app): State<Shared>,
    Path((dev, sid)): Path<(String, String)>,
    Json(body): Json<Value>,
) -> Response {
    let text = body.get("text").and_then(|v| v.as_str()).unwrap_or("").to_string();
    if text.trim().is_empty() {
        return (StatusCode::BAD_REQUEST, Json(json!({"error": "text required"}))).into_response();
    }
    let mut payload = json!({ "session": sid, "text": text });
    if let Some(f) = body.get("files") { payload["files"] = f.clone(); }
    match call_agent(&app, &dev, "opencode.prompt", payload, 15).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => session_error(e),
    }
}

async fn session_interrupt(State(app): State<Shared>, Path((dev, sid)): Path<(String, String)>) -> Response {
    match call_agent(&app, &dev, "opencode.interrupt", json!({ "session": sid }), 6).await {
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
            let mut ok = false;
            for l in lines {
                if let Some((k, v)) = l.split_once(':') {
                    if k.eq_ignore_ascii_case("x-mesh-token") && v.trim() == app.token { ok = true; }
                }
            }
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
            let mut ok = false;
            for l in lines {
                if let Some((k, v)) = l.split_once(':') {
                    if k.eq_ignore_ascii_case("x-mesh-token") && v.trim() == app.token { ok = true; }
                }
            }
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

// ---------------- LLM proxy (magpie) ----------------

const MAGPIE: &str = "http://127.0.0.1:3425";

/// 别的设备通过 hub 用 PC 上的 magpie：
/// `POST /api/llm/v1/chat/completions` → `http://127.0.0.1:3425/v1/chat/completions`。
/// 鉴权就是 hub 的 token（x-mesh-token / ?t= / Authorization: Bearer），
/// 转发时换成 magpie 自己的 key，手机端不用知道。
async fn llm_proxy(State(app): State<Shared>, Path(rest): Path<String>, req: Request) -> Response {
    let (parts, body) = req.into_parts();
    let Ok(bytes) = axum::body::to_bytes(body, 32 * 1024 * 1024).await else {
        return (StatusCode::PAYLOAD_TOO_LARGE, Json(json!({"error": "body too large"}))).into_response();
    };
    // base 写成 /api/llm 或 /api/llm/v1 都能用
    let rest = if rest.starts_with("v1/") || rest == "v1" {
        rest
    } else {
        format!("v1/{rest}")
    };
    let url = format!("{MAGPIE}/{rest}");
    let mut out = app.http.request(parts.method.clone(), &url);
    if let Some(ct) = parts.headers.get(axum::http::header::CONTENT_TYPE) {
        out = out.header(axum::http::header::CONTENT_TYPE, ct);
    }
    if let Some(ac) = parts.headers.get(axum::http::header::ACCEPT) {
        out = out.header(axum::http::header::ACCEPT, ac);
    }
    out = out.header(axum::http::header::AUTHORIZATION, "Bearer magpie");
    if !bytes.is_empty() {
        out = out.body(bytes);
    }
    match out.send().await {
        Ok(resp) => {
            let status = resp.status();
            let mut builder = Response::builder().status(status);
            for (k, v) in resp.headers() {
                if k == axum::http::header::CONTENT_TYPE || k == axum::http::header::CACHE_CONTROL {
                    builder = builder.header(k, v);
                }
            }
            // 直接透传字节流：magpie 回 SSE 时手机也能边生成边显示
            match builder.body(Body::from_stream(resp.bytes_stream())) {
                Ok(r) => r,
                Err(e) => (StatusCode::BAD_GATEWAY, e.to_string()).into_response(),
            }
        }
        Err(e) => (
            StatusCode::BAD_GATEWAY,
            Json(json!({"error": format!("magpie unreachable: {e}")})),
        )
            .into_response(),
    }
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

async fn apk_latest(State(app): State<Shared>, headers: axum::http::HeaderMap) -> Response {
    let dir = app.data.join("apk");
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
    let url = format!("{proto}://{host}/apk/{file}?t={}", app.token);
    let size = fs::metadata(dir.join(&file)).map(|m| m.len()).unwrap_or(0);
    Json(json!({
        "tag_name": meta.get("version").cloned().unwrap_or(json!("v0.0.0")),
        "name": meta.get("name").cloned().unwrap_or(json!("vibelink")),
        "body": meta.get("notes").cloned().unwrap_or(json!("")),
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

/// `GET /apk` → 302 到最新的 APK（带 token）。给手机上手输用。
async fn apk_short(State(app): State<Shared>) -> Response {
    let location = format!("/apk/latest.apk?t={}", app.token);
    (StatusCode::FOUND, [(axum::http::header::LOCATION, location)]).into_response()
}
async fn apk_file(State(app): State<Shared>, Path(name): Path<String>) -> Response {
    if !name.chars().all(|c| c.is_ascii_alphanumeric() || "._-".contains(c)) {
        return (StatusCode::BAD_REQUEST, "bad name").into_response();
    }
    // `/apk/latest.apk` 永远指向 meta.json 里的当前版本：手机上可以存一个固定地址，
    // 不用每次改文件名。
    let name = if name == "latest.apk" || name == "latest" {
        let raw = fs::read_to_string(app.data.join("apk").join("meta.json")).unwrap_or_default();
        serde_json::from_str::<Value>(&raw)
            .ok()
            .and_then(|v| v.get("file").and_then(|x| x.as_str()).map(|s| s.to_string()))
            .unwrap_or(name)
    } else {
        name
    };
    match fs::read(app.data.join("apk").join(&name)) {
        Ok(bytes) => (
            StatusCode::OK,
            [(axum::http::header::CONTENT_TYPE, "application/vnd.android.package-archive")],
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

#[tokio::main]
async fn main() {
    let data = PathBuf::from(std::env::var("VIBELINK_DATA").unwrap_or_else(|_| "data".into()));
    let _ = fs::create_dir_all(data.join("configs"));

    let token_file = data.join("token.txt");
    let token = match fs::read_to_string(&token_file) {
        Ok(t) if !t.trim().is_empty() => t.trim().to_string(),
        _ => {
            let t = format!("{:016x}{:016x}", now_ms(), std::process::id() as u64 * 0x9e3779b97f4a7c15);
            let _ = fs::write(&token_file, &t);
            t
        }
    };

    let store = load(&data);
    let app = Arc::new(App {
        token: token.clone(),
        data: data.clone(),
        store: Mutex::new(store),
        policy: Mutex::new(load_policy(&data)),
        sessions: Mutex::new(SessionPlane::default()),
        http: reqwest::Client::builder()
            .connect_timeout(std::time::Duration::from_secs(5))
            .build()
            .unwrap_or_default(),
    });

    let router = Router::new()
        .route("/api/health", get(health))
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
        .route("/api/llm/{*rest}", any(llm_proxy))
        .route("/api/agent/latest", get(agent_latest))
        .route("/agent/{name}", get(agent_file))
        .route("/api/agent/session-requests", get(agent_session_requests))
        .route("/api/agent/session-result", post(agent_session_result))
        .route("/api/sessions", get(sessions_list))
        .layer(DefaultBodyLimit::max(64 * 1024 * 1024))
        .route("/api/sessions/all", get(sessions_all))
        .route("/api/sessions/{dev}/{agent}/{sid}/messages", get(session_messages_by_agent))
        .route("/api/sessions/{dev}/{sid}/messages", get(session_messages))
        .route("/api/sessions/{dev}/{sid}/prompt", post(session_prompt))
        .route("/api/sessions/{dev}/{sid}/interrupt", post(session_interrupt))
        .route("/apk", get(apk_short))
        .route("/apk/{name}", get(apk_file))
        .with_state(app.clone())
        .layer(middleware::from_fn_with_state(app.clone(), auth));

    let port: u16 = std::env::var("VIBELINK_PORT").ok().and_then(|v| v.parse().ok()).unwrap_or(18995);
    policy::spawn(app.clone());
    tokio::spawn(tcp_gateway(app.clone()));
    tokio::spawn(tcp_exit_gateway(app.clone()));
    let listener = tokio::net::TcpListener::bind(("0.0.0.0", port)).await.expect("bind");
    println!("[hub] v{VERSION} on http://0.0.0.0:{port}");
    println!("[hub] token: {token}");
    println!("[hub] data:  {}", data.display());
    axum::serve(listener, router).await.expect("serve");
}

// keep BTreeMap import used even if protocol changes
#[allow(dead_code)]
fn _unused(_: BTreeMap<String, bool>) {}

// keep ConfigInfo linked for API compatibility
#[allow(dead_code)]
fn _cfg(_: ConfigInfo) {}



















//! Hub device login. Account identity is inherited from authenticated credentials,
//! never from renderer fields. QR scan and desktop polling use different secrets.
use crate::{
    Shared,
    auth::{self, AuthCtx},
    model_gateway,
};
use axum::{
    Extension, Json,
    extract::State,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde_json::{Value, json};
use std::{collections::HashMap, sync::Mutex};
use vibelink_protocol::now_ms;

const QR_TTL: u64 = 5 * 60_000;
pub fn routes() -> axum::Router<Shared> {
    use axum::routing::{get, post};
    axum::Router::new()
        .route("/api/auth/me", get(me))
        .route("/api/auth/session", post(exchange))
        .route("/api/auth/beta", post(beta))
        .route("/api/auth/logout", post(logout))
        .route("/api/auth/qr/start", post(qr_start))
        .route("/api/auth/qr/scan", post(qr_scan))
        .route("/api/auth/qr/confirm", post(qr_confirm))
        .route("/api/auth/qr/poll", post(qr_poll))
        .route("/api/auth/qr/ack", post(qr_ack))
        .layer(axum::extract::DefaultBodyLimit::max(8192))
        .layer(axum::middleware::map_response(
            |mut response: Response| async move {
                response.headers_mut().insert(
                    "cache-control",
                    axum::http::HeaderValue::from_static("no-store"),
                );
                response
                    .headers_mut()
                    .insert("pragma", axum::http::HeaderValue::from_static("no-cache"));
                response
            },
        ))
}
struct Challenge {
    scan_hash: String,
    poll_hash: String,
    device: String,
    edition: String,
    code: String,
    expires: u64,
    scanned: bool,
    denied: bool,
    source: Option<AuthCtx>,
    grant: Option<(String, AuthCtx)>,
    acknowledged: bool,
}
#[derive(Default)]
struct Pending {
    challenges: HashMap<String, Challenge>,
    starts: Vec<u64>,
    failures: Vec<u64>,
}
#[derive(Default)]
pub struct Logins(Mutex<Pending>);
impl Logins {
    fn attempt(&self) -> Result<(), String> {
        let mut p = self.0.lock().unwrap();
        let now = now_ms();
        p.failures.retain(|t| now.saturating_sub(*t) < 60_000);
        if p.failures.len() >= 120 {
            return Err("登录请求过于频繁，请稍后重试".into());
        }
        p.failures.push(now);
        Ok(())
    }
    fn start(&self, device: &str, edition: &str) -> Result<(Value, String), String> {
        let mut p = self.0.lock().unwrap();
        let now = now_ms();
        p.challenges.retain(|_, c| c.expires > now);
        p.starts.retain(|t| now.saturating_sub(*t) < 60_000);
        if p.starts.len() >= 30 || p.challenges.len() >= 64 {
            return Err("登录二维码请求过于频繁，请稍后重试".into());
        }
        p.starts.push(now);
        let id = format!("lg_{}", &auth::random_token()[..24]);
        let scan = auth::random_token();
        let poll = auth::random_token();
        let random = auth::random_token().to_ascii_uppercase();
        let code = format!("{}-{}", &random[..4], &random[4..8]);
        let device = device
            .trim()
            .chars()
            .filter(|c| !c.is_control())
            .take(60)
            .collect::<String>();
        let device = if device.is_empty() {
            "VibeLink 电脑".into()
        } else {
            device
        };
        let expires = now + QR_TTL;
        let response = json!({"ok":true,"id":id,"pollSecret":poll,"expiresAt":expires,"verificationCode":code,"deviceName":device,"edition":edition,"purpose":"desktop_login","intervalMs":2000});
        p.challenges.insert(
            id,
            Challenge {
                scan_hash: auth::hash_token(&scan),
                poll_hash: auth::hash_token(&poll),
                device,
                edition: edition.into(),
                code,
                expires,
                scanned: false,
                denied: false,
                source: None,
                grant: None,
                acknowledged: false,
            },
        );
        Ok((response, scan))
    }
    fn scan(&self, id: &str, secret: &str) -> Result<Value, String> {
        self.attempt()?;
        let mut p = self.0.lock().unwrap();
        let c = scan_challenge(&mut p, id, secret)?;
        if !c.denied && c.source.is_none() {
            c.scanned = true;
        }
        Ok(challenge_public(c))
    }
    fn confirm(
        &self,
        id: &str,
        secret: &str,
        source: &AuthCtx,
        edition: &str,
        decision: &str,
    ) -> Result<Value, String> {
        self.attempt()?;
        let mut p = self.0.lock().unwrap();
        let c = scan_challenge(&mut p, id, secret)?;
        if c.edition != edition {
            return Err("二维码与当前账号不属于同一版别".into());
        }
        if c.denied || c.source.is_some() {
            return Err("二维码已经处理，请重新生成".into());
        }
        if !matches!(decision, "approve" | "deny") {
            return Err("Invalid login decision".into());
        }
        if decision == "deny" {
            c.denied = true;
        } else {
            c.source = Some(source.clone());
        }
        Ok(json!({"ok":true,"status":if c.denied{"denied"}else{"approved"}}))
    }
    fn poll(&self, app: &Shared, id: &str, secret: &str, ack: bool) -> Result<Value, String> {
        let mut p = self.0.lock().unwrap();
        let c = p
            .challenges
            .get_mut(id)
            .filter(|c| secret.len() == 64 && c.poll_hash == auth::hash_token(secret))
            .ok_or("Invalid login polling proof")?;
        if c.expires <= now_ms() {
            return Ok(json!({"ok":true,"status":"expired"}));
        }
        if c.acknowledged {
            return Ok(json!({"ok":true,"status":"consumed"}));
        }
        if c.denied {
            return Ok(json!({"ok":true,"status":"denied"}));
        }
        let Some(source) = &c.source else {
            return Ok(json!({"ok":true,"status":if c.scanned{"scanned"}else{"pending"}}));
        };
        if !app.auth.source_active(source) {
            if let Some((_, ctx)) = &c.grant {
                let _ = app.auth.logout(ctx);
            }
            c.grant = None;
            c.denied = true;
            return Ok(json!({"ok":true,"status":"denied"}));
        }
        if ack {
            if c.grant.is_none() {
                return Err("Login credential was not delivered".into());
            }
            c.acknowledged = true;
            c.grant = None;
            return Ok(json!({"ok":true,"status":"consumed"}));
        }
        if c.grant.is_none() {
            let (token, ctx) = issue(app, source, &c.device)?;
            c.grant = Some((token, ctx));
        }
        let (token, ctx) = c.grant.as_ref().unwrap();
        Ok(
            json!({"ok":true,"status":"approved","token":token,"session":session(app,ctx),"hubUrl":login_urls(app).first()}),
        )
    }
}
fn scan_challenge<'a>(
    p: &'a mut Pending,
    id: &str,
    secret: &str,
) -> Result<&'a mut Challenge, String> {
    p.challenges
        .get_mut(id)
        .filter(|c| {
            c.expires > now_ms()
                && secret.len() == 64
                && c.scan_hash == auth::hash_token(secret)
                && !c.acknowledged
        })
        .ok_or("登录二维码无效或已过期".into())
}
fn challenge_public(c: &Challenge) -> Value {
    json!({"ok":true,"status":if c.denied{"denied"}else if c.source.is_some(){"approved"}else if c.scanned{"scanned"}else{"pending"},"deviceName":c.device,"edition":c.edition,"verificationCode":c.code,"expiresAt":c.expires,"purpose":"desktop_login"})
}
fn edition(app: &Shared) -> &'static str {
    if app.beta_mode { "beta" } else { "dev" }
}
pub fn session(app: &Shared, ctx: &AuthCtx) -> Value {
    let identity = model_gateway::identity(ctx);
    let tenant = crate::tenants::extract_tenant_id(&ctx.name);
    let label = if let Some(tid) = tenant {
        app.tenants
            .list_summary()
            .into_iter()
            .find(|t| t["tenantId"] == tid)
            .and_then(|t| t["label"].as_str().map(str::to_string))
            .unwrap_or(identity.label)
    } else {
        identity.label
    };
    json!({"userId":identity.user_id,"label":label,"displayName":label,"tenantId":tenant,"edition":edition(app),"sessionId":ctx.token_id,"expiresAt":ctx.expires_at,"permissions":["client"],"issuer":app.auth.issuer(),"hubId":app.auth.issuer()})
}
fn valid_source(app: &Shared, ctx: &AuthCtx) -> Result<(), String> {
    if !auth::allows(ctx.scope, auth::Need::Client) || !app.auth.source_active(ctx) {
        return Err("登录凭据无效".into());
    }
    if app.beta_mode && crate::tenants::extract_tenant_id(&ctx.name).is_none() {
        return Err("内测版请使用内测账号登录".into());
    }
    Ok(())
}
fn issue(app: &Shared, source: &AuthCtx, device: &str) -> Result<(String, AuthCtx), String> {
    valid_source(app, source)?;
    let account = session(app, source);
    let name = crate::tenants::extract_tenant_id(&source.name)
        .map(|tid| format!("tenant:{tid}:client"))
        .unwrap_or_else(|| {
            format!(
                "device:{}",
                device.trim().chars().take(60).collect::<String>()
            )
        });
    let (_, token) = app.auth.issue_derived_session(
        source,
        &name,
        account["label"].as_str().unwrap_or("VibeLink"),
    )?;
    let ctx = app
        .auth
        .verify(&token)
        .ok_or("New login could not be verified")?;
    Ok((token, ctx))
}
fn failure(message: String) -> Response {
    (
        StatusCode::BAD_REQUEST,
        Json(json!({"ok":false,"error":message})),
    )
        .into_response()
}
fn credential_response(app: &Shared, token: String, ctx: AuthCtx) -> Response {
    Json(json!({"ok":true,"token":token,"session":session(app,&ctx),"hubUrl":login_urls(app).first()})).into_response()
}
pub fn login_urls(app: &Shared) -> Vec<String> {
    if app.beta_mode {
        return vec!["https://hub.vibelink.cloud".into()];
    }
    crate::hub_urls(app, &[])
        .into_iter()
        .filter(|raw| {
            let Ok(url) = reqwest::Url::parse(raw) else {
                return false;
            };
            let Some(host) = url.host_str() else {
                return false;
            };
            host == "localhost"
                || host.parse::<std::net::Ipv4Addr>().is_ok_and(|ip| {
                    ip.is_loopback()
                        || ip.is_private()
                        || ip.is_link_local()
                        || (ip.octets()[0] == 100 && (64..=127).contains(&ip.octets()[1]))
                })
        })
        .collect()
}
pub async fn me(State(app): State<Shared>, Extension(ctx): Extension<AuthCtx>) -> Response {
    match valid_source(&app, &ctx) {
        Ok(()) => Json(json!({"ok":true,"session":session(&app,&ctx)})).into_response(),
        Err(error) => (
            StatusCode::UNAUTHORIZED,
            Json(json!({"ok":false,"error":error})),
        )
            .into_response(),
    }
}
pub async fn exchange(
    State(app): State<Shared>,
    Extension(ctx): Extension<AuthCtx>,
    Json(body): Json<Value>,
) -> Response {
    if let Err(error) = app.logins.attempt() {
        return failure(error);
    }
    match issue(
        &app,
        &ctx,
        body["deviceName"].as_str().unwrap_or("VibeLink device"),
    ) {
        Ok((token, new)) => credential_response(&app, token, new),
        Err(error) => failure(error),
    }
}
pub async fn beta(State(app): State<Shared>, Json(body): Json<Value>) -> Response {
    if !app.beta_mode {
        return failure("内测账号请连接公网内测服务".into());
    }
    if let Err(error) = app.logins.attempt() {
        return failure(error);
    }
    let device = body["deviceName"].as_str().unwrap_or("VibeLink device");
    let code = body["code"].as_str().unwrap_or("");
    if code.len() > 128 {
        return failure("内测码无效".into());
    }
    match app.tenants.activate(code, device) {
        Ok(tenant) => {
            let name = format!("tenant:{}:client", tenant.tenant_id);
            match app
                .auth
                .issue_session(&name, &tenant.tenant_id, &tenant.label)
            {
                Ok((_, token)) => {
                    let ctx = app.auth.verify(&token).unwrap();
                    credential_response(&app, token, ctx)
                }
                Err(error) => failure(error),
            }
        }
        Err(error) => (
            StatusCode::FORBIDDEN,
            Json(json!({"ok":false,"error":error})),
        )
            .into_response(),
    }
}
pub async fn logout(State(app): State<Shared>, Extension(ctx): Extension<AuthCtx>) -> Response {
    match app.auth.logout(&ctx) {
        Ok(()) => Json(json!({"ok":true})).into_response(),
        Err(error) => failure(error),
    }
}
pub async fn qr_start(State(app): State<Shared>, Json(body): Json<Value>) -> Response {
    match app.logins.start(
        body["deviceName"].as_str().unwrap_or("VibeLink 电脑"),
        edition(&app),
    ) {
        Ok((mut value, secret)) => {
            let id = value["id"].as_str().unwrap();
            let mut link = reqwest::Url::parse("vibelink://login").unwrap();
            {
                let mut query = link.query_pairs_mut();
                query
                    .append_pair("v", "1")
                    .append_pair("purpose", "desktop_login")
                    .append_pair("id", id)
                    .append_pair("s", &secret);
                for url in login_urls(&app) {
                    query.append_pair("u", &url);
                }
            }
            value["link"] = json!(link.as_str());
            Json(value).into_response()
        }
        Err(error) => failure(error),
    }
}
pub async fn qr_scan(State(app): State<Shared>, Json(body): Json<Value>) -> Response {
    match app.logins.scan(
        body["id"].as_str().unwrap_or(""),
        body["scanSecret"].as_str().unwrap_or(""),
    ) {
        Ok(mut value) => {
            value["issuer"] = json!(app.auth.issuer());
            value["hubId"] = json!(app.auth.issuer());
            Json(value).into_response()
        },
        Err(error) => failure(error),
    }
}
pub async fn qr_confirm(
    State(app): State<Shared>,
    Extension(ctx): Extension<AuthCtx>,
    Json(body): Json<Value>,
) -> Response {
    if let Err(error) = valid_source(&app, &ctx) {
        return failure(error);
    }
    match app.logins.confirm(
        body["id"].as_str().unwrap_or(""),
        body["scanSecret"].as_str().unwrap_or(""),
        &ctx,
        edition(&app),
        body["decision"].as_str().unwrap_or(""),
    ) {
        Ok(value) => Json(value).into_response(),
        Err(error) => failure(error),
    }
}
pub async fn qr_poll(State(app): State<Shared>, Json(body): Json<Value>) -> Response {
    match app.logins.poll(
        &app,
        body["id"].as_str().unwrap_or(""),
        body["pollSecret"].as_str().unwrap_or(""),
        false,
    ) {
        Ok(value) => Json(value).into_response(),
        Err(error) => failure(error),
    }
}
pub async fn qr_ack(State(app): State<Shared>, Json(body): Json<Value>) -> Response {
    match app.logins.poll(
        &app,
        body["id"].as_str().unwrap_or(""),
        body["pollSecret"].as_str().unwrap_or(""),
        true,
    ) {
        Ok(value) => Json(value).into_response(),
        Err(error) => failure(error),
    }
}

#[cfg(test)]
#[path = "login_tests.rs"]
mod tests;

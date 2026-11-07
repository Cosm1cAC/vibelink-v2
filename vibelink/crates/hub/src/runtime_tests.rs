use super::*;
use axum::http::{Method, Request as HttpRequest};

fn fixture() -> (PathBuf, Shared, String) {
    let dir = std::env::temp_dir().join(format!("vibelink_runtime_{}", auth::random_token()));
    let (auth, admin) = auth::Auth::load(dir.clone());
    let tenants = tenants::BetaTenantStore::load(dir.clone(), &auth);
    let app = Arc::new(App {
        auth: Arc::new(auth),
        tenants: Arc::new(tenants),
        beta_mode: false,
        bots: bots::BotFleetStore::load(dir.clone()),
        approvals: approvals::ApprovalGate::load(dir.clone()),
        voice: voice::VoiceService::load(dir.clone()),
        deepseek: deepseek::Runtime::load(dir.clone()),
        data: dir.clone(),
        tenant_id: None,
        tenant_apps: Mutex::new(HashMap::new()),
        store: Mutex::new(Store::default()),
        policy: Mutex::new(load_policy(&dir)),
        sessions: Mutex::new(SessionPlane::default()),
        http: deepseek::client(),
    });
    (dir, app, admin)
}

async fn request(
    app: Shared,
    token: &str,
    method: Method,
    path: &str,
    body: Value,
) -> (StatusCode, Value) {
    let router = api_router(app.clone()).layer(middleware::from_fn_with_state(app, auth_mw));
    let req = HttpRequest::builder()
        .method(method)
        .uri(path)
        .header("x-mesh-token", token)
        .header("content-type", "application/json")
        .body(Body::from(body.to_string()))
        .unwrap();
    let response = router.oneshot(req).await.unwrap();
    let status = response.status();
    let bytes = axum::body::to_bytes(response.into_body(), 1024 * 1024)
        .await
        .unwrap();
    (
        status,
        serde_json::from_slice(&bytes).unwrap_or(Value::Null),
    )
}

#[tokio::test]
async fn tenants_cannot_share_bots_sessions_or_access_development_resources() {
    let (dir, app, admin) = fixture();
    let (_, a) = app.auth.issue("tenant:beta_01:client", auth::Scope::Client);
    let (_, b) = app.auth.issue("tenant:beta_02:client", auth::Scope::Client);
    let (_, data) = request(app.clone(), &a, Method::GET, "/api/bots", Value::Null).await;
    assert!(
        data["bots"]
            .as_array()
            .unwrap()
            .iter()
            .all(|bot| bot["engine"] == "deepseek")
    );
    assert!(
        data["templates"]
            .as_array()
            .unwrap()
            .iter()
            .all(|tpl| tpl.get("defaultEngine").is_none())
    );
    let (status, _) = request(
        app.clone(),
        &a,
        Method::POST,
        "/api/bots",
        json!({"id":"bot_cto","name":"Tenant A","engine":"codex"}),
    )
    .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    let (status, _) = request(
        app.clone(),
        &a,
        Method::POST,
        "/api/bots",
        json!({"id":"bot_cto","name":"Tenant A"}),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    for token in [&b, &admin] {
        let (_, data) = request(
            app.clone(),
            token,
            Method::GET,
            "/api/bots/bot_cto",
            Value::Null,
        )
        .await;
        assert!(!data.to_string().contains("Tenant A"));
    }
    for path in [
        "/api/terminal/pc",
        "/api/workspace/pc/tree",
        "/api/fleet/action",
        "/api/cindy/join-link",
    ] {
        let (status, _) = request(app.clone(), &a, Method::GET, path, Value::Null).await;
        assert_eq!(status, StatusCode::FORBIDDEN, "{path}");
    }
    let (status, session) = request(
        app.clone(),
        &a,
        Method::POST,
        "/api/sessions/pc/create",
        json!({"agent":"deepseek","botId":"bot_cto","text":""}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{session}");
    let path = format!(
        "/api/sessions/pc/deepseek/{}/messages",
        session["id"].as_str().unwrap()
    );
    let (status, _) = request(app.clone(), &a, Method::GET, &path, Value::Null).await;
    assert_eq!(status, StatusCode::OK);
    let (status, _) = request(app.clone(), &b, Method::GET, &path, Value::Null).await;
    assert_eq!(status, StatusCode::BAD_GATEWAY);
    let (status, _) = request(
        app.clone(),
        &a,
        Method::POST,
        "/api/sessions/pc/create",
        json!({"agent":"codex","text":""}),
    )
    .await;
    assert_eq!(status, StatusCode::BAD_GATEWAY);
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn legacy_migration_preserves_owner_credentials_without_copying_to_beta() {
    let dir = std::env::temp_dir().join(format!("vibelink_migration_{}", auth::random_token()));
    fs::create_dir_all(&dir).unwrap();
    fs::write(dir.join("token.txt"), "private-owner").unwrap();
    fs::write(dir.join("tokens.json"), "[]").unwrap();
    model_policy::migrate_legacy(&dir).unwrap();
    assert_eq!(
        fs::read_to_string(dir.join("dev/token.txt")).unwrap(),
        "private-owner"
    );
    assert!(!dir.join("beta/token.txt").exists());
    assert!(dir.join("dev/tokens.json").exists());
    fs::write(dir.join("dev/token.txt"), "rotated").unwrap();
    model_policy::migrate_legacy(&dir).unwrap();
    assert_eq!(
        fs::read_to_string(dir.join("dev/token.txt")).unwrap(),
        "rotated"
    );
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn model_change_clears_session_without_changing_role_or_memory() {
    let (dir, app, _) = fixture();
    let before = app.bots.get_bot("bot_cto").unwrap();
    app.bots.upsert_bot(&json!({"id":"bot_cto","name":"My dispatcher","activeSessionId":"old-cli-session","sessionIds":["old-cli-session"]})).unwrap();
    app.bots
        .upsert_bot(&json!({"id":"bot_cto","name":"My dispatcher","engine":"deepseek"}))
        .unwrap();
    let after = app.bots.get_bot("bot_cto").unwrap();
    assert_eq!(after.name, "My dispatcher");
    assert_eq!(after.role_prompt, before.role_prompt);
    assert_eq!(after.memories, before.memories);
    assert!(after.active_session_id.is_empty());
    assert!(after.session_ids.is_empty());
    fs::remove_dir_all(dir).unwrap();
}

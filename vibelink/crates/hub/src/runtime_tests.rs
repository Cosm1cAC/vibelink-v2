use super::*;
use axum::http::{Method, Request as HttpRequest};

pub(super) fn fixture() -> (PathBuf, Shared, String) {
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
        bindings: agent_sessions::Sessions::load(dir.clone()),
        usage: Arc::new(model_usage::UsageStore::load(dir.clone()).unwrap()),
        grants: Arc::new(model_gateway::Grants::default()),
        magpie: model_gateway::upstream(),
        data: dir.clone(),
        tenant_id: None,
        tenant_apps: Mutex::new(HashMap::new()),
        store: Mutex::new(Store::default()),
        policy: Mutex::new(load_policy(&dir)),
        sessions: Arc::new(Mutex::new(SessionPlane::default())),
        http: model_gateway::client(),
    });
    (dir, app, admin)
}

#[tokio::test]
async fn magpie_gateway_meters_both_wire_protocols_and_cannot_be_spoofed() {
    let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address=listener.local_addr().unwrap();
    let mock=Router::new().route("/v1/responses",post(|headers:axum::http::HeaderMap,Json(body):Json<Value>|async move {
        assert_eq!(headers["authorization"],"Bearer magpie");
        assert_eq!(body["model"],model_policy::DEEPSEEK_MODEL);
        assert_ne!(body["user"],"another-user");
        assert_eq!(body["store"],false);
        if body["stream"]==true {
            ([("content-type","text/event-stream")],"data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":12,\"output_tokens\":3,\"total_tokens\":15}}}\n\n").into_response()
        } else {Json(json!({"status":"completed","seenUser":body["user"],"usage":{"input_tokens":10,"output_tokens":2,"total_tokens":12}})).into_response()}
    })).route("/v1/chat/completions",post(|Json(body):Json<Value>|async move {
        assert_eq!(body["stream_options"]["include_usage"],true);
        ([("content-type","text/event-stream")],"data: {\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":4,\"total_tokens\":24,\"prompt_tokens_details\":{\"cached_tokens\":8}}}\n\ndata: [DONE]\n\n")
    }));
    let task=tokio::spawn(async move{axum::serve(listener,mock).await.unwrap()});
    let(dir,mut app,admin)=fixture();Arc::get_mut(&mut app).unwrap().magpie=format!("http://{address}/v1");
    let(_,a)=app.auth.issue("tenant:beta_01:client",auth::Scope::Client);
    let(_,b)=app.auth.issue("tenant:beta_02:client",auth::Scope::Client);
    let(status,value)=request(app.clone(),&a,Method::POST,"/api/llm/v1/responses",json!({"model":model_policy::DEEPSEEK_MODEL,"input":"test","user":"another-user","max_output_tokens":10})).await;
    assert_eq!(status,StatusCode::OK);assert_eq!(value["seenUser"],"beta_01");
    let(status,_)=request(app.clone(),&a,Method::POST,"/api/llm/v1/responses",json!({"model":model_policy::DEEPSEEK_MODEL,"input":"test","stream":true,"max_output_tokens":10})).await;assert_eq!(status,StatusCode::OK);
    let(status,_)=request(app.clone(),&b,Method::POST,"/api/llm/v1/chat/completions",json!({"model":model_policy::DEEPSEEK_MODEL,"messages":[],"stream":true,"max_tokens":10})).await;assert_eq!(status,StatusCode::OK);
    let(_,a_usage)=request(app.clone(),&a,Method::GET,"/api/usage",Value::Null).await;assert_eq!(a_usage["totalTokens"],27);assert_eq!(a_usage["usedRequests"],2);
    let(_,b_usage)=request(app.clone(),&b,Method::GET,"/api/usage",Value::Null).await;assert_eq!(b_usage["totalTokens"],24);assert_eq!(b_usage["cachedTokens"],8);
    let(status,_)=request(app.clone(),&a,Method::GET,"/api/usage/users",Value::Null).await;assert_eq!(status,StatusCode::FORBIDDEN);
    let(status,all)=request(app.clone(),&admin,Method::GET,"/api/usage/users",Value::Null).await;assert_eq!(status,StatusCode::OK);assert_eq!(all["users"].as_array().unwrap().len(),2);
    let grant=app.grants.issue(model_usage::Identity{user_id:"beta_02".into(),label:"b".into(),beta:true});
    let(status,_)=request(app.clone(),&grant,Method::POST,"/api/sessions/pc/create",json!({"agent":"codex"})).await;assert_eq!(status,StatusCode::UNAUTHORIZED);
    let(status,_)=request(app.clone(),&grant,Method::POST,"/api/llm/v1/responses",json!({"input":"test","max_output_tokens":10})).await;assert_eq!(status,StatusCode::OK);
    let(status,_)=request(app.clone(),&a,Method::POST,"/api/llm/v1/responses",json!({"model":"gpt-5","input":"test"})).await;assert_eq!(status,StatusCode::FORBIDDEN);
    let(status,_)=request(app.clone(),&admin,Method::POST,"/api/usage/limits",json!({"userId":"beta_01","limits":{"dailyRequests":2,"dailyTokens":10000,"dailyInputBytes":100000,"concurrentRequests":2,"requestsPerMinute":30}})).await;assert_eq!(status,StatusCode::OK);
    let(status,_)=request(app.clone(),&a,Method::POST,"/api/llm/v1/responses",json!({"input":"test","max_output_tokens":10})).await;assert_eq!(status,StatusCode::TOO_MANY_REQUESTS);
    task.abort();fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
async fn predictable_seat_labels_cannot_be_used_to_impersonate_users() {
    let (dir,app,_)=fixture();
    let seats=app.tenants.list_summary();
    let short=seats[0]["shortCode"].as_str().unwrap();
    let secret=seats[0]["inviteCode"].as_str().unwrap();
    let(status,_)=request(app.clone(),"",Method::POST,"/api/beta/activate",json!({"code":short})).await;
    assert_eq!(status,StatusCode::FORBIDDEN);
    let(status,value)=request(app.clone(),"",Method::POST,"/api/beta/activate",json!({"code":secret})).await;
    assert_eq!(status,StatusCode::OK);assert_eq!(value["tenantId"],"beta_01");
    assert!(value["token"].as_str().is_some_and(|t|t.len()>=32));
    fs::remove_dir_all(dir).unwrap();
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
            .all(|bot| bot["engine"] == "codex" && bot["model"] == model_policy::DEEPSEEK_MODEL)
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
        json!({"id":"bot_cto","name":"Tenant A","model":"gpt-5"}),
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
    let plane=app.sessions.clone();
    let worker=tokio::spawn(async move {
        loop {
            let request={plane.lock().unwrap().queue.get_mut("pc").and_then(|q|q.pop())};
            if let Some(req)=request {
                assert_eq!(req["kind"],"codex.runtime.create");
                assert_eq!(req["payload"]["codexRuntime"]["model"],model_policy::DEEPSEEK_MODEL);
                assert!(req["payload"]["codexRuntime"]["beta"].as_bool().unwrap());
                let tx=plane.lock().unwrap().waiters.remove(req["id"].as_str().unwrap()).unwrap();
                tx.send(Ok(json!({"id":"agent-owned-session","agent":"codex"}))).unwrap();break;
            }
            tokio::task::yield_now().await;
        }
    });
    let (status,session)=request(app.clone(),&a,Method::POST,"/api/sessions/pc/create",json!({"agent":"codex","botId":"bot_cto","text":""})).await;
    assert_eq!(status,StatusCode::OK,"{session}");worker.await.unwrap();
    let tenant_app=app.tenant_apps.lock().unwrap().get("beta_01").unwrap().clone();
    assert_eq!(tenant_app.bindings.get("agent-owned-session").unwrap().agent,"codex");
    let path="/api/sessions/pc/codex/agent-owned-session/messages";
    let(status,_)=request(app.clone(),&b,Method::GET,path,Value::Null).await;assert_eq!(status,StatusCode::BAD_GATEWAY);
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
        .upsert_bot(&json!({"id":"bot_cto","name":"My dispatcher","model":model_policy::DEEPSEEK_MODEL}))
        .unwrap();
    let after = app.bots.get_bot("bot_cto").unwrap();
    assert_eq!(after.name, "My dispatcher");
    assert_eq!(after.role_prompt, before.role_prompt);
    assert_eq!(after.memories, before.memories);
    assert!(after.active_session_id.is_empty());
    assert!(after.session_ids.is_empty());
    fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
async fn paired_clients_cannot_read_or_interrupt_another_users_agent_session() {
    let (dir, app, _) = fixture();
    let (_, a) = app.auth.issue("client-a", auth::Scope::Client);
    let (_, b) = app.auth.issue("client-b", auth::Scope::Client);
    let owner = model_gateway::identity(&app.auth.verify(&a).unwrap());
    app.bindings.bind("", agent_sessions::Binding {
        id: "private-session".into(), agent: "codex".into(), device: "pc".into(),
        model: model_policy::DEEPSEEK_MODEL.into(), scope: agent_sessions::scope(&owner, model_policy::DEEPSEEK_MODEL),
        owner, bot_id: String::new(), cwd: String::new(), title: String::new(),
    });
    for (method, path) in [
        (Method::GET, "/api/sessions/pc/codex/private-session/messages"),
        (Method::POST, "/api/sessions/pc/private-session/interrupt"),
    ] {
        let (status, result) = request(app.clone(), &b, method, path, json!({"agent":"codex"})).await;
        assert_eq!(status, StatusCode::BAD_GATEWAY, "{result}");
        assert!(result.to_string().contains("another user"), "{result}");
    }
    assert!(app.sessions.lock().unwrap().queue.is_empty());
    fs::remove_dir_all(dir).unwrap();
}

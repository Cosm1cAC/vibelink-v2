use super::*;
use crate::{
    auth::Scope,
    runtime_tests::{fixture, request},
};
use axum::http::Method;
use std::sync::Arc;

#[tokio::test]
async fn independent_sessions_keep_principal_and_logout_is_per_device() {
    let (dir, app, admin) = fixture();
    let (status, one) = request(
        app.clone(),
        &admin,
        Method::POST,
        "/api/auth/session",
        json!({"deviceName":"Desktop","userId":"attacker"}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{one}");
    assert_eq!(one["session"]["userId"], "owner");
    let token = one["token"].as_str().unwrap();
    assert_ne!(token, admin);
    assert!(one["session"]["expiresAt"].as_u64().unwrap() > now_ms());
    let (_, two) = request(
        app.clone(),
        token,
        Method::POST,
        "/api/auth/session",
        json!({"deviceName":"Phone"}),
    )
    .await;
    assert_eq!(one["session"]["userId"], two["session"]["userId"]);
    assert_ne!(one["session"]["sessionId"], two["session"]["sessionId"]);
    let (status, _) = request(app.clone(), token, Method::GET, "/api/tokens", Value::Null).await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    let (status, _) = request(
        app.clone(),
        token,
        Method::POST,
        "/api/auth/logout",
        json!({}),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert!(app.auth.verify(token).is_none());
    assert!(app.auth.verify(two["token"].as_str().unwrap()).is_some());
    assert!(app.auth.verify(&admin).is_some());
    let (reload, _) = auth::Auth::load(dir.clone());
    assert_eq!(reload.issuer(), app.auth.issuer());
    assert!(reload.verify(token).is_none());
    let raw = std::fs::read_to_string(dir.join("tokens.json")).unwrap();
    assert!(!raw.contains(token));
    std::fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
async fn legacy_mobile_identity_and_its_work_survive_exchange() {
    let (dir, app, _) = fixture();
    let (view, old) = app.auth.issue("old phone", Scope::Client);
    let (_, one) = request(app.clone(), &old, Method::GET, "/api/auth/me", Value::Null).await;
    assert_eq!(one["session"]["userId"], format!("client_{}", view.id));
    assert_eq!(one["session"]["expiresAt"], 0);
    let (_, next) = request(
        app.clone(),
        &old,
        Method::POST,
        "/api/auth/session",
        json!({}),
    )
    .await;
    assert_eq!(next["session"]["userId"], one["session"]["userId"]);
    assert_eq!(
        model_gateway::identity(&app.auth.verify(next["token"].as_str().unwrap()).unwrap()).user_id,
        format!("client_{}", view.id)
    );
    assert!(app.auth.verify(&old).is_some());
    std::fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
async fn qr_requires_both_secrets_explicit_approval_and_ack_without_leaking_credentials_to_phone() {
    let (dir, app, admin) = fixture();
    let (_, start) = request(
        app.clone(),
        "",
        Method::POST,
        "/api/auth/qr/start",
        json!({"deviceName":"Laptop"}),
    )
    .await;
    let link = reqwest::Url::parse(start["link"].as_str().unwrap()).unwrap();
    let params: HashMap<_, _> = link.query_pairs().into_owned().collect();
    assert_eq!(params["purpose"], "desktop_login");
    assert!(
        !start["link"]
            .as_str()
            .unwrap()
            .contains(start["pollSecret"].as_str().unwrap())
    );
    let scan = json!({"id":start["id"],"scanSecret":params["s"]});
    let poll = json!({"id":start["id"],"pollSecret":start["pollSecret"]});
    let (_, pending) = request(
        app.clone(),
        "",
        Method::POST,
        "/api/auth/qr/poll",
        poll.clone(),
    )
    .await;
    assert_eq!(pending["status"], "pending");
    let (bad, _) = request(
        app.clone(),
        "",
        Method::POST,
        "/api/auth/qr/poll",
        json!({"id":start["id"],"pollSecret":params["s"]}),
    )
    .await;
    assert_ne!(bad, StatusCode::OK);
    let (_, scanned) = request(
        app.clone(),
        "",
        Method::POST,
        "/api/auth/qr/scan",
        scan.clone(),
    )
    .await;
    assert_eq!(scanned["deviceName"], "Laptop");
    assert_eq!(scanned["issuer"],app.auth.issuer());
    assert_eq!(scanned["hubId"],scanned["issuer"]);
    assert!(scanned.get("token").is_none());
    let mut confirmation = scan;
    confirmation["decision"] = json!("approve");
    let (unauth, _) = request(
        app.clone(),
        "",
        Method::POST,
        "/api/auth/qr/confirm",
        confirmation.clone(),
    )
    .await;
    assert_eq!(unauth, StatusCode::UNAUTHORIZED);
    let (_, approved) = request(
        app.clone(),
        &admin,
        Method::POST,
        "/api/auth/qr/confirm",
        confirmation.clone(),
    )
    .await;
    assert_eq!(approved["status"], "approved");
    assert!(approved.get("token").is_none());
    let (_, grant) = request(
        app.clone(),
        "",
        Method::POST,
        "/api/auth/qr/poll",
        poll.clone(),
    )
    .await;
    assert_eq!(grant["session"]["userId"], "owner");
    let (_, retry) = request(
        app.clone(),
        "",
        Method::POST,
        "/api/auth/qr/poll",
        poll.clone(),
    )
    .await;
    assert_eq!(grant["token"], retry["token"]);
    let (replay, _) = request(
        app.clone(),
        &admin,
        Method::POST,
        "/api/auth/qr/confirm",
        confirmation,
    )
    .await;
    assert_ne!(replay, StatusCode::OK);
    let (_, ack) = request(
        app.clone(),
        "",
        Method::POST,
        "/api/auth/qr/ack",
        poll.clone(),
    )
    .await;
    assert_eq!(ack["status"], "consumed");
    let (_, used) = request(app.clone(), "", Method::POST, "/api/auth/qr/poll", poll).await;
    assert_eq!(used["status"], "consumed");
    assert!(used.get("token").is_none());
    std::fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
async fn beta_login_devices_share_only_their_tenant_and_never_receive_worker_credentials() {
    let (dir, mut app, _) = fixture();
    Arc::get_mut(&mut app).unwrap().beta_mode = true;
    let rows = app.tenants.list_summary();
    let login = json!({"code":rows[0]["inviteCode"],"deviceName":"Phone"});
    let (status, a) = request(
        app.clone(),
        "",
        Method::POST,
        "/api/auth/beta",
        login.clone(),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{a}");
    let (_, again) = request(app.clone(), "", Method::POST, "/api/auth/beta", login).await;
    assert_ne!(a["token"], again["token"]);
    assert_eq!(a["session"]["userId"], again["session"]["userId"]);
    assert_eq!(a["session"]["tenantId"], rows[0]["tenantId"]);
    assert_eq!(a["session"]["edition"], "beta");
    assert!(a.get("agentToken").is_none());
    assert!(!a.to_string().contains("inviteCode"));
    let token = a["token"].as_str().unwrap();
    let (_, me) = request(app.clone(), token, Method::GET, "/api/auth/me", Value::Null).await;
    assert_eq!(a["session"], me["session"]);
    let (status, _) = request(app.clone(), token, Method::GET, "/api/tokens", Value::Null).await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    let (status, _) = request(
        app.clone(),
        token,
        Method::GET,
        "/api/agent/session-requests",
        Value::Null,
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    let (status, _) = request(
        app.clone(),
        "",
        Method::POST,
        "/api/auth/beta",
        json!({"code":rows[0]["shortCode"]}),
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    let (_, start) = request(
        app.clone(),
        "",
        Method::POST,
        "/api/auth/qr/start",
        json!({}),
    )
    .await;
    let link = reqwest::Url::parse(start["link"].as_str().unwrap()).unwrap();
    let params: HashMap<_, _> = link.query_pairs().into_owned().collect();
    let confirm = json!({"id":start["id"],"scanSecret":params["s"],"decision":"approve"});
    let (status, _) = request(
        app.clone(),
        token,
        Method::POST,
        "/api/auth/qr/confirm",
        confirm,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let (_, grant) = request(
        app.clone(),
        "",
        Method::POST,
        "/api/auth/qr/poll",
        json!({"id":start["id"],"pollSecret":start["pollSecret"]}),
    )
    .await;
    assert_eq!(grant["session"]["userId"], a["session"]["userId"]);
    assert_eq!(grant["session"]["issuer"], a["session"]["issuer"]);
    let (status, _) = request(
        app.clone(),
        grant["token"].as_str().unwrap(),
        Method::GET,
        "/api/usage/users",
        Value::Null,
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    std::fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
async fn rejected_expired_or_revoked_qr_cannot_deliver_a_device_session() {
    let (dir, app, _) = fixture();
    let (view, token) = app.auth.issue("Phone", Scope::Client);
    let ctx = app.auth.verify(&token).unwrap();
    for case in ["deny", "expire", "revoke"] {
        let (start, secret) = app.logins.start("Desktop", "dev").unwrap();
        let id = start["id"].as_str().unwrap();
        let poll = start["pollSecret"].as_str().unwrap();
        app.logins
            .confirm(
                id,
                &secret,
                &ctx,
                "dev",
                if case == "deny" { "deny" } else { "approve" },
            )
            .unwrap();
        if case == "expire" {
            app.logins
                .0
                .lock()
                .unwrap()
                .challenges
                .get_mut(id)
                .unwrap()
                .expires = now_ms() - 1;
        }
        if case == "revoke" {
            assert!(app.auth.revoke(&view.id));
        }
        let result = app.logins.poll(&app, id, poll, false).unwrap();
        assert_eq!(
            result["status"],
            if case == "expire" {
                "expired"
            } else {
                "denied"
            }
        );
        assert!(result.get("token").is_none());
    }
    let (start, secret) = app.logins.start("Desktop", "beta").unwrap();
    assert!(
        app.logins
            .confirm(
                start["id"].as_str().unwrap(),
                &secret,
                &ctx,
                "dev",
                "approve"
            )
            .is_err()
    );
    std::fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
async fn device_display_names_cannot_select_a_tenant_or_change_the_authenticated_principal() {
    let (dir, app, admin) = fixture();
    let (old_view, old) = app.auth.issue("Phone", Scope::Client);
    let (_, new) = request(
        app.clone(),
        &old,
        Method::POST,
        "/api/auth/session",
        json!({"deviceName":"tenant:beta_02:client"}),
    )
    .await;
    let ctx = app.auth.verify(new["token"].as_str().unwrap()).unwrap();
    assert!(crate::tenants::extract_tenant_id(&ctx.name).is_none());
    assert_eq!(
        model_gateway::identity(&ctx).user_id,
        format!("client_{}", old_view.id)
    );
    let (status, devices) = request(
        app.clone(),
        new["token"].as_str().unwrap(),
        Method::GET,
        "/api/devices",
        Value::Null,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert!(!devices.to_string().contains("tenantId"));
    for name in [
        "tenant:beta_02:client",
        "  tenant:beta_02:client",
        "ten\tant:beta_02:client",
    ] {
        let (_, secret, _) = app.auth.start_pairing("Phone");
        assert!(app.auth.redeem(&secret, name).is_err(), "{name:?}");
    }
    let (_, secret, _) = app.auth.start_pairing("Phone");
    let (_, phone) = app.auth.redeem(&secret, "Phone").unwrap();
    assert_eq!(
        model_gateway::identity(&app.auth.verify(&phone).unwrap()).user_id,
        "owner"
    );
    assert!(app.auth.verify(&admin).is_some());
    std::fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
async fn public_login_bodies_are_bounded_before_json_parsing() {
    let (dir, app, _) = fixture();
    for path in ["/api/auth/qr/start", "/api/auth/qr/poll", "/api/auth/beta"] {
        let (status, _) = request(
            app.clone(),
            "",
            Method::POST,
            path,
            json!({"unused":"x".repeat(9000)}),
        )
        .await;
        assert_eq!(status, StatusCode::PAYLOAD_TOO_LARGE, "{path}");
    }
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
fn unacknowledged_device_grants_are_revoked_when_the_approving_phone_logs_out() {
    let (dir, app, _) = fixture();
    let (_, phone) = app
        .auth
        .issue_session("Phone", "owner", "Workspace")
        .unwrap();
    let source = app.auth.verify(&phone).unwrap();
    let (start, secret) = app.logins.start("Desktop", "dev").unwrap();
    let id = start["id"].as_str().unwrap();
    let proof = start["pollSecret"].as_str().unwrap();
    app.logins
        .confirm(id, &secret, &source, "dev", "approve")
        .unwrap();
    let grant = app.logins.poll(&app, id, proof, false).unwrap();
    let desktop = grant["token"].as_str().unwrap();
    assert!(app.auth.verify(desktop).is_some());
    app.auth.logout(&source).unwrap();
    assert!(
        app.auth
            .issue_derived_session(&source, "Desktop", "Workspace")
            .is_err()
    );
    let result = app.logins.poll(&app, id, proof, false).unwrap();
    assert_eq!(result["status"], "denied");
    assert!(app.auth.verify(desktop).is_none());
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
fn login_device_limit_is_bounded_and_released_slots_do_not_grow_the_token_file() {
    let (dir, app, _) = fixture();
    let mut first = None;
    for _ in 0..64 {
        let (_, token) = app.auth.issue_session("Desktop", "one", "User").unwrap();
        first.get_or_insert(token);
    }
    assert!(app.auth.issue_session("Desktop", "one", "User").is_err());
    let ctx = app.auth.verify(first.as_deref().unwrap()).unwrap();
    app.auth.logout(&ctx).unwrap();
    let count = app.auth.list().len();
    app.auth
        .issue_session("New Desktop", "one", "User")
        .unwrap();
    assert_eq!(app.auth.list().len(), count);
    assert!(app.auth.verify(first.as_deref().unwrap()).is_none());
    std::fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
async fn a_phone_task_can_be_followed_up_and_cancelled_by_its_qr_authenticated_desktop() {
    let (dir, app, _) = fixture();
    let (_, phone) = app.auth.issue("Legacy Phone", Scope::Client);
    let source = app.auth.verify(&phone).unwrap();
    request(
        app.clone(),
        &phone,
        Method::POST,
        "/api/bot-groups",
        json!({"id":"cross-device","name":"Work","botIds":["bot_cto","bot_dev"]}),
    )
    .await;
    let (status, created) = request(
        app.clone(),
        &phone,
        Method::POST,
        "/api/bot-groups/cross-device/work",
        json!({"requestId":"phone-request","text":"Inspect the implementation"}),
    )
    .await;
    assert_eq!(status, StatusCode::ACCEPTED, "{created}");
    let work = created["work"]["id"].as_str().unwrap();
    let (start, secret) = app.logins.start("Desktop", "dev").unwrap();
    let id = start["id"].as_str().unwrap();
    app.logins
        .confirm(id, &secret, &source, "dev", "approve")
        .unwrap();
    let response = app
        .logins
        .poll(&app, id, start["pollSecret"].as_str().unwrap(), false)
        .unwrap();
    let desktop = response["token"].as_str().unwrap();
    let (status,followup)=request(app.clone(),desktop,Method::POST,"/api/bot-groups/cross-device/work",json!({"requestId":"desktop-request","text":"Also inspect error handling","replyToWorkId":work})).await;
    assert_eq!(status, StatusCode::ACCEPTED, "{followup}");
    assert_eq!(followup["work"]["id"], work);
    assert_eq!(followup["work"]["inputs"].as_array().unwrap().len(), 2);
    let (status, _) = request(
        app.clone(),
        desktop,
        Method::POST,
        &format!("/api/bot-groups/cross-device/work/{work}/actions"),
        json!({"action":"cancel"}),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    std::fs::remove_dir_all(dir).unwrap();
}

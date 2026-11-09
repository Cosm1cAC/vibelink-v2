use super::*;
use std::sync::Arc;
fn fixture() -> (PathBuf, Store, Scope) {
    let dir = std::env::temp_dir().join(format!(
        "vibelink_automation_{}",
        crate::auth::random_token()
    ));
    let store = Store::load(dir.clone()).unwrap();
    (
        dir,
        store,
        Scope {
            issuer: "issuer-a".into(),
            edition: "dev".into(),
            user: "owner".into(),
            group: "group-a".into(),
        },
    )
}
fn work(revision: u64, status: &str) -> Observation {
    Observation {
        id: "work-1".into(),
        user: "owner".into(),
        group: "group-a".into(),
        revision,
        status: status.into(),
    }
}
fn rule(expected: u64) -> Command {
    Command::SaveRule {
        id: "followup".into(),
        label: "Review result".into(),
        statuses: vec!["completed".into(), "failed".into(), "needs_input".into()],
        expected,
    }
}
fn mode(mode: &str, expected: u64) -> Command {
    Command::SetMode {
        mode: mode.into(),
        expected,
    }
}
fn decisions(snapshot: &Value) -> &Vec<Value> {
    snapshot["decisions"].as_array().unwrap()
}
#[test]
fn baseline_and_new_rules_never_suggest_existing_history() {
    let (dir, store, scope) = fixture();
    let old = [work(8, "completed")];
    let first = store.snapshot(scope.clone(), &old).unwrap();
    assert_eq!(first["policy"], json!({"mode":"observe","revision":0}));
    assert!(decisions(&first).is_empty());
    let saved = store.command(scope.clone(), rule(0), &old).unwrap();
    assert!(decisions(&saved).is_empty());
    drop(store);
    let store = Store::load(dir.clone()).unwrap();
    store.scan(&old).unwrap();
    assert!(decisions(&store.snapshot(scope.clone(), &old).unwrap()).is_empty());
    let next = [work(9, "completed")];
    store.scan(&next).unwrap();
    let snapshot = store.snapshot(scope, &next).unwrap();
    assert_eq!(decisions(&snapshot).len(), 1);
    assert_eq!(snapshot["decisions"][0]["workRevision"], 9);
    fs::remove_dir_all(dir).unwrap();
}
#[test]
fn replay_restart_and_concurrent_scans_make_one_decision() {
    let (dir, store, scope) = fixture();
    let queued = [work(1, "queued")];
    store.command(scope.clone(), rule(0), &queued).unwrap();
    let store = Arc::new(store);
    let end = [work(2, "completed")];
    let threads: Vec<_> = (0..8)
        .map(|_| {
            let s = store.clone();
            let end = end.clone();
            std::thread::spawn(move || s.scan(&end).unwrap())
        })
        .collect();
    for thread in threads {
        thread.join().unwrap();
    }
    let snapshot = store.snapshot(scope.clone(), &end).unwrap();
    assert_eq!(decisions(&snapshot).len(), 1);
    assert_eq!(snapshot["decisions"][0]["reason"], "matched");
    let before = fs::read(dir.join("automation.json")).unwrap();
    store.scan(&end).unwrap();
    assert_eq!(
        fs::read(dir.join("automation.json")).unwrap(),
        before,
        "idle scan must not rewrite timestamps"
    );
    drop(store);
    let reloaded = Store::load(dir.clone()).unwrap();
    assert_eq!(reloaded.snapshot(scope, &end).unwrap(), snapshot);
    fs::remove_dir_all(dir).unwrap();
}
#[test]
fn off_interval_is_suppressed_and_reenable_does_not_backfill() {
    let (dir, store, scope) = fixture();
    store
        .command(scope.clone(), rule(0), &[work(1, "queued")])
        .unwrap();
    store
        .command(scope.clone(), mode("off", 1), &[work(1, "queued")])
        .unwrap();
    let on = store
        .command(scope.clone(), mode("observe", 2), &[work(2, "completed")])
        .unwrap();
    assert_eq!(decisions(&on).len(), 1);
    assert_eq!(on["decisions"][0]["reason"], "off");
    assert_eq!(on["decisions"][0]["outcome"], "suppressed");
    let next = store.snapshot(scope, &[work(3, "failed")]).unwrap();
    assert_eq!(
        decisions(&next)
            .iter()
            .filter(|d| d["outcome"] == "suggested")
            .count(),
        1
    );
    fs::remove_dir_all(dir).unwrap();
}
#[test]
fn versions_are_immutable_revoked_audits_remain_and_replacement_has_new_baseline() {
    let (dir, store, scope) = fixture();
    store
        .command(scope.clone(), rule(0), &[work(1, "queued")])
        .unwrap();
    store
        .command(
            scope.clone(),
            Command::RevokeRule {
                id: "followup".into(),
                expected: 1,
            },
            &[work(2, "completed")],
        )
        .unwrap();
    let revoked = store
        .snapshot(scope.clone(), &[work(2, "completed")])
        .unwrap();
    assert_eq!(revoked["decisions"][0]["reason"], "revoked");
    assert!(revoked["rules"][0]["revokedAt"].as_u64().unwrap() > 0);
    let replaced = store
        .command(scope.clone(), rule(2), &[work(3, "failed")])
        .unwrap();
    assert_eq!(replaced["rules"].as_array().unwrap().len(), 2);
    assert_eq!(replaced["rules"][1]["version"], 2);
    assert!(decisions(&replaced).iter().all(|d| d["ruleVersion"] == 1));
    let next = store.snapshot(scope, &[work(4, "needs_input")]).unwrap();
    assert_eq!(
        decisions(&next)
            .iter()
            .filter(|d| d["ruleVersion"] == 2 && d["reason"] == "matched")
            .count(),
        1
    );
    assert!(Store::load(dir.clone()).is_ok());
    fs::remove_dir_all(dir).unwrap();
}
#[test]
fn compare_and_swap_accepts_one_writer_and_same_mode_increments() {
    let (dir, store, scope) = fixture();
    let store = Arc::new(store);
    store.snapshot(scope.clone(), &[]).unwrap();
    let threads: Vec<_> = (0..2)
        .map(|_| {
            let s = store.clone();
            let scope = scope.clone();
            std::thread::spawn(move || s.command(scope, mode("observe", 0), &[]))
        })
        .collect();
    let results: Vec<_> = threads.into_iter().map(|t| t.join().unwrap()).collect();
    assert_eq!(results.iter().filter(|r| r.is_ok()).count(), 1);
    assert_eq!(
        results
            .iter()
            .filter(|r| matches!(r, Err(Error::Conflict(1))))
            .count(),
        1
    );
    assert_eq!(
        store
            .command(scope.clone(), mode("observe", 1), &[])
            .unwrap()["policy"]["revision"],
        2
    );
    assert_eq!(
        Store::load(dir.clone())
            .unwrap()
            .snapshot(scope, &[])
            .unwrap()["policy"]["revision"],
        2
    );
    fs::remove_dir_all(dir).unwrap();
}
#[test]
fn failed_persist_rolls_back_rule_cursor_decision_and_revision() {
    let (dir, store, scope) = fixture();
    store
        .command(scope.clone(), rule(0), &[work(1, "queued")])
        .unwrap();
    let before = fs::read(dir.join("automation.json")).unwrap();
    fs::create_dir_all(dir.join("automation.tmp")).unwrap();
    assert!(matches!(
        store.scan(&[work(2, "completed")]),
        Err(Error::Persistence(_))
    ));
    assert!(matches!(
        store.command(scope.clone(), mode("off", 1), &[work(2, "completed")]),
        Err(Error::Persistence(_))
    ));
    assert_eq!(fs::read(dir.join("automation.json")).unwrap(), before);
    {
        let disk = store.inner.lock().unwrap();
        let e = &disk.scopes[&scope.key()];
        assert_eq!(e.revision, 1);
        assert_eq!(e.cursor["work-1"].revision, 1);
        assert!(e.decisions.is_empty());
        assert_eq!(e.mode, "observe");
    }
    fs::remove_dir(dir.join("automation.tmp")).unwrap();
    store.scan(&[work(2, "completed")]).unwrap();
    assert_eq!(
        decisions(&store.snapshot(scope, &[work(2, "completed")]).unwrap()).len(),
        1
    );
    fs::remove_dir_all(dir).unwrap();
}
#[test]
fn capacity_retains_pending_cursor_and_off_and_revoke_remain_available() {
    let (dir, store, scope) = fixture();
    store
        .command(scope.clone(), rule(0), &[work(1, "queued")])
        .unwrap();
    {
        let mut disk = store.inner.lock().unwrap();
        let e = disk.scopes.get_mut(&scope.key()).unwrap();
        for i in 0..MAX_EVENTS {
            e.events.insert(
                format!("test-{i}"),
                Event {
                    id: format!("test-{i}"),
                    work_id: "work-old".into(),
                    work_revision: i as u64,
                    status: "completed".into(),
                    at: 1,
                },
            );
        }
    }
    let end = [work(2, "completed")];
    let snap = store.snapshot(scope.clone(), &end).unwrap();
    assert_eq!(snap["observer"]["error"], "capacity");
    assert!(decisions(&snap).is_empty());
    let still_paused = store.snapshot(scope.clone(), &[work(3, "queued")]).unwrap();
    assert_eq!(still_paused["observer"]["error"], "capacity");
    assert_eq!(
        store.inner.lock().unwrap().scopes[&scope.key()].cursor["work-1"].revision,
        1
    );
    assert_eq!(
        store.command(scope.clone(), mode("off", 1), &end).unwrap()["policy"]["mode"],
        "off"
    );
    let revoked = store
        .command(
            scope.clone(),
            Command::RevokeRule {
                id: "followup".into(),
                expected: 2,
            },
            &end,
        )
        .unwrap();
    assert_eq!(revoked["policy"]["revision"], 3);
    assert!(matches!(
        store.command(scope.clone(), mode("observe", 3), &end),
        Err(Error::Capacity)
    ));
    assert_eq!(store.inner.lock().unwrap().scopes[&scope.key()].mode, "off");
    fs::remove_dir_all(dir).unwrap();
}
#[test]
fn unsupported_auto_invalid_rules_and_regression_fail_closed() {
    let (dir, store, scope) = fixture();
    store
        .command(scope.clone(), rule(0), &[work(5, "queued")])
        .unwrap();
    let before = fs::read(dir.join("automation.json")).unwrap();
    assert!(matches!(
        store.command(scope.clone(), mode("auto", 1), &[]),
        Err(Error::Invalid(_))
    ));
    assert!(
        store
            .command(
                scope.clone(),
                Command::SaveRule {
                    id: "bad".into(),
                    label: "Bad".into(),
                    statuses: vec!["running".into()],
                    expected: 1
                },
                &[]
            )
            .is_err()
    );
    assert!(store.scan(&[work(4, "completed")]).is_err());
    assert!(store.scan(&[work(5, "completed")]).is_err());
    assert_eq!(fs::read(dir.join("automation.json")).unwrap(), before);
    fs::remove_dir_all(dir).unwrap();
}
#[test]
fn issuer_edition_principal_and_group_are_all_isolated() {
    let (dir, store, scope) = fixture();
    store.command(scope.clone(), rule(0), &[]).unwrap();
    for other in [
        Scope {
            issuer: "issuer-b".into(),
            ..scope.clone()
        },
        Scope {
            edition: "beta".into(),
            ..scope.clone()
        },
        Scope {
            user: "other".into(),
            ..scope.clone()
        },
        Scope {
            group: "other".into(),
            ..scope.clone()
        },
    ] {
        let snap = store.snapshot(other, &[work(2, "completed")]).unwrap();
        assert!(snap["rules"].as_array().unwrap().is_empty());
        assert!(decisions(&snap).is_empty());
    }
    assert_eq!(
        store.snapshot(scope, &[]).unwrap()["rules"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    fs::remove_dir_all(dir).unwrap();
}
#[test]
fn corrupt_or_forged_ledger_is_rejected_without_overwrite() {
    let (dir, store, scope) = fixture();
    store.command(scope, rule(0), &[]).unwrap();
    let original = fs::read(dir.join("automation.json")).unwrap();
    for mutation in ["auto", "forged_decision", "unknown_format"] {
        let mut disk: Value = serde_json::from_slice(&original).unwrap();
        let key = disk["scopes"]
            .as_object()
            .unwrap()
            .keys()
            .next()
            .unwrap()
            .clone();
        match mutation {
            "auto" => disk["scopes"][&key]["mode"] = json!("auto"),
            "forged_decision" => {
                disk["scopes"][&key]["decisions"]["fake"] = json!({"id":"fake","eventId":"fake","workId":"work-1","workRevision":3,"status":"completed","ruleId":"followup","ruleVersion":1,"at":1,"outcome":"suggested","reason":"matched"});
            }
            _ => disk["format"] = json!(99),
        }
        let bytes = serde_json::to_vec(&disk).unwrap();
        fs::write(dir.join("automation.json"), &bytes).unwrap();
        assert!(Store::load(dir.clone()).is_err());
        assert_eq!(fs::read(dir.join("automation.json")).unwrap(), bytes);
    }
    fs::write(dir.join("automation.json"), b"{broken").unwrap();
    assert!(Store::load(dir.clone()).is_err());
    fs::remove_dir_all(dir).unwrap();
}
#[tokio::test]
async fn authenticated_api_cannot_override_identity_or_dispatch_work() {
    use crate::StatusCode;
    use crate::runtime_tests::{fixture, request};
    use axum::http::Method;
    let (dir, app, admin) = fixture();
    let (_, client) = app.auth.issue("phone-a", crate::auth::Scope::Client);
    let (_, agent) = app.auth.issue("worker", crate::auth::Scope::Agent);
    let (status, _) = request(
        app.clone(),
        &admin,
        Method::POST,
        "/api/bot-groups",
        json!({"id":"group-a","name":"Observe test","botIds":["bot_cto","bot_dev"]}),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let path = "/api/bot-groups/group-a/automation";
    assert_eq!(
        request(app.clone(), "", Method::GET, path, Value::Null)
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        request(app.clone(), &agent, Method::GET, path, Value::Null)
            .await
            .0,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        request(
            app.clone(),
            &admin,
            Method::POST,
            path,
            json!({"action":"setMode","mode":"auto","expectedRevision":0})
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        request(
            app.clone(),
            &admin,
            Method::POST,
            path,
            json!({"action":"setMode","mode":"observe","expectedRevision":0,"owner":"other"})
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );
    let saved=request(app.clone(),&admin,Method::POST,path,json!({"action":"saveRule","ruleId":"followup","label":"Review result","statuses":["completed"],"expectedRevision":0})).await;
    assert_eq!(saved.0, StatusCode::OK, "{}", saved.1);
    assert_eq!(saved.1["groupId"], "group-a");
    assert_eq!(saved.1["policy"]["revision"], 1);
    assert_eq!(
        request(
            app.clone(),
            &admin,
            Method::POST,
            path,
            json!({"action":"setMode","mode":"off","expectedRevision":0})
        )
        .await
        .0,
        StatusCode::CONFLICT
    );
    let own = request(app.clone(), &client, Method::GET, path, Value::Null).await;
    assert_eq!(own.0, StatusCode::OK);
    assert!(own.1["rules"].as_array().unwrap().is_empty());
    assert_eq!(
        request(
            app.clone(),
            &client,
            Method::POST,
            path,
            json!({"action":"setMode","mode":"observe","expectedRevision":0,"owner":"owner"})
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );
    assert!(app.work.list("group-a").is_empty());
    assert!(app.store.lock().unwrap().commands.is_empty());
    let usage = request(app.clone(), &admin, Method::GET, "/api/usage", Value::Null)
        .await
        .1;
    assert_eq!(usage["usedRequests"], 0);
    let health = request(app.clone(), "", Method::GET, "/api/health", Value::Null)
        .await
        .1;
    assert_eq!(health["version"], "0.6.1");
    assert_eq!(health["automation"]["dispatch"], false);
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn recent_projection_does_not_discard_full_audit_history() {
    let (dir, store, scope) = fixture();
    store
        .command(scope.clone(), rule(0), &[work(1, "queued")])
        .unwrap();
    for revision in 2..=202 {
        store.scan(&[work(revision, "completed")]).unwrap();
    }
    let snapshot = store
        .snapshot(scope.clone(), &[work(202, "completed")])
        .unwrap();
    assert_eq!(decisions(&snapshot).len(), 200);
    assert_eq!(snapshot["capacity"]["decisions"], 201);
    assert_eq!(
        store.inner.lock().unwrap().scopes[&scope.key()]
            .decisions
            .len(),
        201
    );
    let reloaded = Store::load(dir.clone()).unwrap();
    assert_eq!(
        reloaded.inner.lock().unwrap().scopes[&scope.key()]
            .decisions
            .len(),
        201
    );
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn reserved_disk_budget_keeps_off_and_revocation_available() {
    let (dir, store, scope) = fixture();
    store.command(scope.clone(), rule(0), &[]).unwrap();
    store
        .transaction(true, |disk| {
            let e = disk.scopes.get_mut(&scope.key()).unwrap();
            e.cursor.insert(
                "large-legacy-cursor".into(),
                Cursor {
                    revision: 0,
                    status: "x".repeat(MAX_BYTES - CONTROL_RESERVE),
                },
            );
            Ok(())
        })
        .unwrap();
    assert!(matches!(
        store.command(scope.clone(), mode("observe", 1), &[]),
        Err(Error::Capacity)
    ));
    assert_eq!(
        store.command(scope.clone(), mode("off", 1), &[]).unwrap()["policy"]["mode"],
        "off"
    );
    assert_eq!(
        store
            .command(
                scope,
                Command::RevokeRule {
                    id: "followup".into(),
                    expected: 2
                },
                &[]
            )
            .unwrap()["policy"]["revision"],
        3
    );
    assert!(Store::load(dir.clone()).is_ok());
    fs::remove_dir_all(dir).unwrap();
}
#[test]
fn exhausted_revision_rejects_without_wrapping_or_mutating() {
    let (dir, store, scope) = fixture();
    store.command(scope.clone(), rule(0), &[]).unwrap();
    store
        .transaction(true, |disk| {
            disk.scopes.get_mut(&scope.key()).unwrap().revision = u64::MAX;
            Ok(())
        })
        .unwrap();
    let before = fs::read(dir.join("automation.json")).unwrap();
    assert!(matches!(
        store.command(scope, mode("off", u64::MAX), &[]),
        Err(Error::Invalid(_))
    ));
    assert_eq!(fs::read(dir.join("automation.json")).unwrap(), before);
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn realistic_byte_capacity_pauses_45000_decisions_without_blocking_controls() {
    let (dir, store, scope) = fixture();
    let queued: Vec<_> = (0..500)
        .map(|i| Observation {
            id: format!("work-{i}"),
            ..work(1, "queued")
        })
        .collect();
    for i in 0..90 {
        store
            .command(
                scope.clone(),
                Command::SaveRule {
                    id: format!("rule-{i}"),
                    label: format!("Rule {i}"),
                    statuses: vec!["completed".into()],
                    expected: i,
                },
                &queued,
            )
            .unwrap();
    }
    let baseline_bytes = fs::metadata(dir.join("automation.json")).unwrap().len();
    let complete: Vec<_> = queued
        .iter()
        .map(|w| Observation {
            revision: 2,
            status: "completed".into(),
            ..w.clone()
        })
        .collect();
    // Controls must work even before any scanner has discovered/latching byte capacity.
    let immediate_off = store
        .command(scope.clone(), mode("off", 90), &complete)
        .unwrap();
    assert_eq!(immediate_off["policy"]["mode"], "off");
    assert!(decisions(&immediate_off).is_empty());
    let immediate_revoke = store
        .command(
            scope.clone(),
            Command::RevokeRule {
                id: "rule-0".into(),
                expected: 91,
            },
            &complete,
        )
        .unwrap();
    assert_eq!(immediate_revoke["policy"]["revision"], 92);
    assert!(
        store.inner.lock().unwrap().scopes[&scope.key()]
            .cursor
            .values()
            .all(|c| c.revision == 1)
    );
    assert!(matches!(store.scan(&complete), Err(Error::Capacity)));
    let paused = store.snapshot(scope.clone(), &complete).unwrap();
    assert_eq!(paused["observer"]["error"], "capacity");
    assert!(decisions(&paused).is_empty());
    let before = store.inner.lock().unwrap().scopes[&scope.key()].clone();
    assert_eq!(before.events.len(), 0);
    assert!(before.cursor.values().all(|c| c.revision == 1));
    let off = store
        .command(scope.clone(), mode("off", 92), &complete)
        .unwrap();
    assert_eq!(off["policy"]["mode"], "off");
    let revoked = store
        .command(
            scope.clone(),
            Command::RevokeRule {
                id: "rule-1".into(),
                expected: 93,
            },
            &complete,
        )
        .unwrap();
    assert_eq!(revoked["policy"]["revision"], 94);
    let after = store.inner.lock().unwrap().scopes[&scope.key()].clone();
    assert_eq!(after.events, before.events);
    assert_eq!(after.decisions, before.decisions);
    assert_eq!(after.cursor, before.cursor);
    assert!(
        fs::metadata(dir.join("automation.json")).unwrap().len() < baseline_bytes + 1024,
        "audit may not consume reserve"
    );
    let reloaded = Store::load(dir.clone()).unwrap();
    assert_eq!(
        reloaded.snapshot(scope, &complete).unwrap()["observer"]["error"],
        "capacity"
    );
    fs::remove_dir_all(dir).unwrap();
}

#[tokio::test]
async fn tenant_http_scopes_and_failed_commits_are_not_admin_overrides() {
    use crate::StatusCode;
    use crate::runtime_tests::{fixture, request};
    use axum::http::Method;
    let (dir, app, admin) = fixture();
    let (_, a) = app
        .auth
        .issue("tenant:beta_01:client", crate::auth::Scope::Client);
    let (_, b) = app
        .auth
        .issue("tenant:beta_02:client", crate::auth::Scope::Client);
    for token in [&a, &b] {
        assert_eq!(
            request(
                app.clone(),
                token,
                Method::POST,
                "/api/bot-groups",
                json!({"id":"shared-id","name":"Own group","botIds":["bot_cto"]})
            )
            .await
            .0,
            StatusCode::OK
        );
    }
    let path = "/api/bot-groups/shared-id/automation";
    assert_eq!(request(app.clone(),&a,Method::POST,path,json!({"action":"saveRule","ruleId":"own","label":"Own rule","statuses":["failed"],"expectedRevision":0})).await.0,StatusCode::OK);
    let other = request(app.clone(), &b, Method::GET, path, Value::Null).await;
    assert_eq!(other.0, StatusCode::OK);
    assert!(other.1["rules"].as_array().unwrap().is_empty());
    assert_eq!(
        request(app.clone(), &admin, Method::GET, path, Value::Null)
            .await
            .0,
        StatusCode::NOT_FOUND
    );
    let local = "/api/bot-groups/local/automation";
    request(
        app.clone(),
        &admin,
        Method::POST,
        "/api/bot-groups",
        json!({"id":"local","name":"Local","botIds":["bot_cto"]}),
    )
    .await;
    request(app.clone(), &admin, Method::GET, local, Value::Null).await;
    fs::create_dir_all(dir.join("automation.tmp")).unwrap();
    let failure = request(
        app.clone(),
        &admin,
        Method::POST,
        local,
        json!({"action":"setMode","mode":"off","expectedRevision":0}),
    )
    .await;
    assert_eq!(failure.0, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(failure.1["code"], "persistence_failed");
    assert!(
        !failure
            .1
            .to_string()
            .contains(&dir.to_string_lossy().to_string())
    );
    fs::remove_dir(dir.join("automation.tmp")).unwrap();
    let unchanged = request(app.clone(), &admin, Method::GET, local, Value::Null)
        .await
        .1;
    assert_eq!(unchanged["policy"], json!({"mode":"observe","revision":0}));
    let written = request(
        app.clone(),
        &admin,
        Method::POST,
        local,
        json!({"action":"setMode","mode":"observe","expectedRevision":0}),
    )
    .await;
    assert_eq!(written.0, StatusCode::OK);
    assert_eq!(written.1["policy"]["revision"], 1);
    fs::remove_dir_all(dir).unwrap();
}

use super::*;
use std::{
    process::Command,
    sync::{
        Barrier,
        atomic::{AtomicUsize, Ordering},
    },
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

struct TempData(PathBuf);
static NEXT_TEMP: AtomicUsize = AtomicUsize::new(0);
impl TempData {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "vibelink-operation-test-{}-{}-{}",
            std::process::id(),
            NEXT_TEMP.fetch_add(1, Ordering::SeqCst),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&path).unwrap();
        Self(path)
    }
}
impl Drop for TempData {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn request() -> Value {
    json!({
        "operationId":"gw_work_step_create", "text":"semantic user message", "cwd":"/project",
        "botId":"chief", "model":"auto/deepseek-v4.1-flash", "session":"temporary-session",
        "botContext":{"systemPrompt":"role instruction", "toolPolicy":{"write":false}},
        "files":[{"name":"input.txt", "data":"file content"}],
        "codexRuntime":{"scope":"beta_one", "model":"auto/deepseek-v4.1-flash", "beta":true,
            "readOnly":true, "token":"short-lived-gateway-secret", "gateway":"http://old-hub/api/llm/v1"}
    })
}

#[test]
fn failed_action_preserves_sanitized_diagnosis_without_acceptance_or_reexecution() {
    let data = TempData::new();
    let payload = request();
    let error = execute(
        &data.0,
        "diagnostic_scope",
        "codex.create",
        &payload,
        || {
            Err(
                "group_workspace_changed with short-lived-gateway-secret and semantic user message"
                    .into(),
            )
        },
    )
    .unwrap_err();
    assert!(error.starts_with("operation_uncertain:"));
    assert!(error.contains("group_workspace_changed"));
    assert!(error.contains("<redacted>"));
    assert!(!error.contains("short-lived-gateway-secret"));
    assert!(!error.contains("semantic user message"));
    let dir = operation_dir(&data.0, "diagnostic_scope", "gw_work_step_create");
    assert!(!dir.join("receipt.json").exists());
    let persisted = fs::read_to_string(dir.join("failure.json")).unwrap();
    assert!(!persisted.contains("short-lived-gateway-secret"));
    assert!(!persisted.contains("semantic user message"));
    let repeated = execute(
        &data.0,
        "diagnostic_scope",
        "codex.create",
        &payload,
        || panic!("diagnostic must not permit replay"),
    )
    .unwrap_err();
    assert_eq!(error, repeated);
    assert_eq!(
        lookup(&data.0, "diagnostic_scope", &payload).unwrap()["status"],
        "uncertain"
    );
}

#[test]
fn persists_intent_before_acceptance_and_replays_the_exact_temporary_session_result() {
    let data = TempData::new();
    let payload = request();
    let calls = AtomicUsize::new(0);
    let result = json!({"id":"temporary-session", "agent":"codex", "running":false});
    let received = execute(&data.0, "beta_one", "codex.create", &payload, || {
        calls.fetch_add(1, Ordering::SeqCst);
        let intent: Intent = serde_json::from_slice(
            &fs::read(
                operation_dir(&data.0, "beta_one", "gw_work_step_create").join("intent.json"),
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(intent.kind, "codex.create");
        assert_eq!(intent.operation_id, "gw_work_step_create");
        Ok(result.clone())
    })
    .unwrap();
    assert_eq!(received, result);
    let mut retry = payload;
    retry["codexRuntime"]["token"] = json!("rotated-gateway-capability");
    retry["codexRuntime"]["gateway"] = json!("https://restarted-hub/api/llm/v1");
    assert_eq!(
        execute(&data.0, "beta_one", "codex.create", &retry, || {
            calls.fetch_add(1, Ordering::SeqCst);
            panic!("completed acceptance must not dispatch again");
        })
        .unwrap(),
        result
    );
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    for entry in fs::read_dir(operation_dir(&data.0, "beta_one", "gw_work_step_create")).unwrap() {
        let persisted = fs::read_to_string(entry.unwrap().path()).unwrap();
        assert!(!persisted.contains("short-lived-gateway-secret"));
        assert!(!persisted.contains("semantic user message"));
        assert!(!persisted.contains("role instruction"));
        assert!(!persisted.contains("old-hub"));
    }
}

#[test]
fn semantic_changes_and_different_rpc_kind_are_refused() {
    let data = TempData::new();
    let payload = request();
    execute(&data.0, "beta_one", "codex.create", &payload, || {
        Ok(json!({"id":"accepted"}))
    })
    .unwrap();
    for pointer in [
        "/text",
        "/session",
        "/botId",
        "/model",
        "/cwd",
        "/botContext/systemPrompt",
        "/files/0/data",
        "/codexRuntime/model",
    ] {
        let mut changed = payload.clone();
        *changed.pointer_mut(pointer).unwrap() = json!("different semantic content");
        let error = execute(&data.0, "beta_one", "codex.create", &changed, || {
            panic!("mismatched operation must not dispatch")
        })
        .unwrap_err();
        assert!(
            error.starts_with("operation_content_mismatch:"),
            "{pointer}: {error}"
        );
    }
    assert!(
        execute(&data.0, "beta_one", "codex.prompt", &payload, || panic!(
            "kind changed"
        ))
        .unwrap_err()
        .starts_with("operation_content_mismatch:")
    );
}

#[test]
fn independent_runtime_scopes_do_not_share_receipts() {
    let data = TempData::new();
    let payload = request();
    for scope in ["beta_one", "beta_two"] {
        let expected = json!({"id":format!("session-{scope}")});
        assert_eq!(
            execute(&data.0, scope, "codex.create", &payload, || Ok(
                expected.clone()
            ))
            .unwrap(),
            expected
        );
    }
    assert_eq!(
        execute(&data.0, "beta_one", "codex.create", &payload, || panic!(
            "scope one must replay its own receipt"
        ))
        .unwrap(),
        json!({"id":"session-beta_one"})
    );
}

#[test]
fn concurrent_same_id_waits_and_executes_once() {
    let data = TempData::new();
    let calls = Arc::new(AtomicUsize::new(0));
    let start = Arc::new(Barrier::new(12));
    let handles: Vec<_> = (0..12)
        .map(|_| {
            let (path, calls, start) = (data.0.clone(), calls.clone(), start.clone());
            thread::spawn(move || {
                start.wait();
                execute(&path, "beta_one", "codex.prompt", &request(), || {
                    calls.fetch_add(1, Ordering::SeqCst);
                    thread::sleep(Duration::from_millis(40));
                    Ok(json!({"ok":true,"id":"stable-session","queued":true}))
                })
                .unwrap()
            })
        })
        .collect();
    for handle in handles {
        assert_eq!(
            handle.join().unwrap(),
            json!({"ok":true,"id":"stable-session","queued":true})
        );
    }
    assert_eq!(calls.load(Ordering::SeqCst), 1);
}

#[test]
fn failed_or_corrupt_operations_are_uncertain_and_never_replayed() {
    let data = TempData::new();
    let payload = request();
    assert!(
        execute(&data.0, "beta_one", "codex.create", &payload, || Err(
            "CLI timed out after starting".into()
        ))
        .unwrap_err()
        .starts_with("operation_uncertain:")
    );
    assert!(
        execute(&data.0, "beta_one", "codex.create", &payload, || panic!(
            "timeout can already have executed tools"
        ))
        .unwrap_err()
        .starts_with("operation_uncertain:")
    );
    let corrupt = operation_dir(&data.0, "beta_two", "gw_work_step_create");
    fs::create_dir_all(&corrupt).unwrap();
    fs::write(corrupt.join("intent.json"), b"{partial").unwrap();
    assert!(
        execute(&data.0, "beta_two", "codex.create", &payload, || panic!(
            "partial intent is not permission to retry"
        ))
        .unwrap_err()
        .starts_with("operation_uncertain:")
    );
}

#[test]
fn a_missing_intent_or_failed_receipt_commit_never_restarts_an_accepted_action() {
    let data = TempData::new();
    let payload = request();
    let accepted = operation_dir(&data.0, "accepted", "gw_work_step_create");
    execute(&data.0, "accepted", "codex.create", &payload, || {
        Ok(json!({"id":"real-session"}))
    })
    .unwrap();
    fs::remove_file(accepted.join("intent.json")).unwrap();
    assert!(
        execute(&data.0, "accepted", "codex.create", &payload, || panic!(
            "a surviving receipt proves this action must not run again"
        ))
        .unwrap_err()
        .starts_with("operation_uncertain:")
    );

    let pending = operation_dir(&data.0, "commit-failed", "gw_work_step_create");
    assert!(
        execute(&data.0, "commit-failed", "codex.create", &payload, || {
            fs::create_dir(pending.join("receipt.pending")).unwrap();
            Ok(json!({"id":"already-started-session"}))
        })
        .unwrap_err()
        .starts_with("operation_uncertain:")
    );
    assert!(
        execute(
            &data.0,
            "commit-failed",
            "codex.create",
            &payload,
            || panic!("receipt I/O failure must not repeat a CLI launch")
        )
        .unwrap_err()
        .starts_with("operation_uncertain:")
    );
}

#[test]
fn unavailable_intent_storage_prevents_any_dispatch() {
    let data = TempData::new();
    let invalid_root = data.0.join("file-instead-of-data-dir");
    fs::write(&invalid_root, b"occupied").unwrap();
    assert!(
        execute(
            &invalid_root,
            "beta_one",
            "codex.create",
            &request(),
            || panic!("CLI cannot start before durable intent storage")
        )
        .unwrap_err()
        .starts_with("operation_store_unavailable:")
    );
}

#[test]
fn operation_lookup_is_read_only_scoped_and_cannot_dispatch_an_unknown_id() {
    let data = TempData::new();
    let payload = request();
    assert_eq!(
        lookup(&data.0, "beta_one", &payload).unwrap(),
        json!({"status":"missing"})
    );
    assert!(!data.0.join("operation_receipts").exists());
    let accepted = json!({"id":"accepted-session", "aliasOf":"temporary-session"});
    execute(&data.0, "beta_one", "codex.create", &payload, || {
        Ok(accepted.clone())
    })
    .unwrap();
    assert_eq!(
        lookup(&data.0, "beta_one", &payload).unwrap(),
        json!({"status":"accepted", "result":accepted})
    );
    assert_eq!(
        lookup(&data.0, "beta_two", &payload).unwrap(),
        json!({"status":"missing"})
    );
    assert!(
        execute(&data.0, "beta_uncertain", "codex.create", &payload, || Err(
            "lost result".into()
        ))
        .is_err()
    );
    assert_eq!(
        lookup(&data.0, "beta_uncertain", &payload).unwrap(),
        json!({"status":"uncertain"})
    );
}

#[test]
fn runtime_operation_lookup_uses_the_production_scope_without_preparing_a_cli() {
    let data = TempData::new();
    let mut payload = request();
    payload["codexRuntime"]["scope"] = json!("receipt_api_only_test");
    // No gateway grant is needed because lookup must not configure or start a CLI.
    payload["codexRuntime"]["token"] = json!("");
    let accepted = json!({"id":"runtime-scoped-session"});
    execute(
        &data.0,
        "receipt_api_only_test",
        "codex.create",
        &payload,
        || Ok(accepted.clone()),
    )
    .unwrap();
    assert_eq!(
        crate::handle_session_request("codex.runtime.operation", &payload, &data.0).unwrap(),
        json!({"status":"accepted", "result":accepted})
    );
    payload["codexRuntime"]["scope"] = json!("receipt_another_tenant");
    assert_eq!(
        crate::handle_session_request("codex.runtime.operation", &payload, &data.0).unwrap(),
        json!({"status":"missing"})
    );
}

#[test]
fn legacy_calls_have_no_durable_side_effects_and_malformed_ids_do_not_dispatch() {
    let data = TempData::new();
    let calls = AtomicUsize::new(0);
    for expected in [0, 1] {
        assert_eq!(
            execute(
                &data.0,
                "dev_gpt",
                "codex.create",
                &json!({"text":"legacy"}),
                || Ok(json!(calls.fetch_add(1, Ordering::SeqCst)))
            )
            .unwrap(),
            json!(expected)
        );
    }
    assert!(!data.0.join("operation_receipts").exists());
    for id in [json!(null), json!(""), json!("../escape"), json!(3)] {
        assert!(
            execute(
                &data.0,
                "dev_gpt",
                "codex.create",
                &json!({"operationId":id}),
                || panic!("invalid operationId must not dispatch")
            )
            .unwrap_err()
            .starts_with("invalid_operation_id:")
        );
    }
}

fn probe(data: &Path, mode: &str) -> std::process::Output {
    let mut command = Command::new(std::env::current_exe().unwrap());
    command
        .args([
            "--exact",
            "operation_receipts::tests::subprocess_probe",
            "--ignored",
            "--nocapture",
        ])
        .env("VIBELINK_RECEIPT_TEST_DATA", data)
        .env("VIBELINK_RECEIPT_TEST_MODE", mode);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
    }
    command.output().unwrap()
}

#[test]
fn committed_acceptance_survives_a_new_process_without_redispatch() {
    let data = TempData::new();
    let first = probe(&data.0, "commit");
    assert!(
        first.status.success(),
        "{}",
        String::from_utf8_lossy(&first.stderr)
    );
    let second = probe(&data.0, "replay");
    assert!(
        second.status.success(),
        "{}",
        String::from_utf8_lossy(&second.stderr)
    );
    assert_eq!(
        fs::read_to_string(data.0.join("accepted.json"))
            .unwrap()
            .trim(),
        r#"{"id":"temporary-cross-process-session","queued":true}"#
    );
    assert!(data.0.join("executed-once").is_file());
}

#[test]
fn abrupt_process_exit_after_intent_cannot_replay() {
    let data = TempData::new();
    let first = probe(&data.0, "crash");
    assert_eq!(first.status.code(), Some(47));
    assert!(
        operation_dir(&data.0, "beta_process", "gw_work_step_create")
            .join("intent.json")
            .is_file()
    );
    let second = probe(&data.0, "uncertain");
    assert!(
        second.status.success(),
        "{}",
        String::from_utf8_lossy(&second.stderr)
    );
    assert!(!data.0.join("accepted.json").exists());
}

#[test]
#[ignore = "Child-process receipt probe, invoked only by the durable receipt tests"]
fn subprocess_probe() {
    let data = PathBuf::from(std::env::var_os("VIBELINK_RECEIPT_TEST_DATA").expect("probe data"));
    let mode = std::env::var("VIBELINK_RECEIPT_TEST_MODE").unwrap();
    let result = execute(
        &data,
        "beta_process",
        "codex.prompt",
        &request(),
        || match mode.as_str() {
            "commit" => {
                new_private_file(&data.join("executed-once"))
                    .unwrap()
                    .sync_all()
                    .unwrap();
                Ok(json!({"id":"temporary-cross-process-session","queued":true}))
            }
            "crash" => std::process::exit(47),
            _ => panic!("a new process must never redispatch an existing operation"),
        },
    );
    if mode == "uncertain" {
        assert!(result.unwrap_err().starts_with("operation_uncertain:"));
    } else {
        fs::write(
            data.join("accepted.json"),
            serde_json::to_vec(&result.unwrap()).unwrap(),
        )
        .unwrap();
    }
}

#[test]
fn sha256_matches_existing_hub_standard_vectors() {
    assert_eq!(
        hex_hash(b""),
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    );
    assert_eq!(
        hex_hash(b"abc"),
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    );
}

//! Opt-in integration check using the real official CLI and a disposable metered Hub.
#[test]
#[ignore = "Requires VIBELINK_CODEX_SMOKE_REQUEST containing a disposable Hub agent request"]
fn real_codex_deepseek_session_can_resume_through_metered_gateway() {
    use serde_json::{Value, json};
    let file =
        std::env::var("VIBELINK_CODEX_SMOKE_REQUEST").expect("Set the disposable request file");
    let request: Value = serde_json::from_slice(&std::fs::read(&file).unwrap()).unwrap();
    let mut payload = request["payload"].clone();
    payload["text"] = json!("Reply exactly OK. Do not use tools.");
    let runtime = crate::codex_runtime::Runtime::parse(&payload).unwrap();

    assert_eq!(runtime.model, crate::codex_runtime::DEEPSEEK);
    if std::env::var("VIBELINK_CODEX_SMOKE_EXPECT_SANDBOX_REJECTION").as_deref() == Ok("1") {
        assert!(runtime.beta);
        let error =
            crate::handle_session_request("codex.runtime.create", &payload, &std::env::temp_dir())
                .expect_err("Unsupported worker must reject tenant inference");
        assert!(
            (error.contains("cannot enforce tenant filesystem isolation") || error.contains("codex exited before starting thread")),
            "{error}"
        );
        return;
    }
    let first =
        crate::handle_session_request("codex.runtime.create", &payload, &std::env::temp_dir())
            .expect("Official CLI create failed");
    let id = first["id"].as_str().unwrap().to_string();
    let wait = |payload: &Value, previous: Option<&str>| {
        for step in 0..1800 {
            let result = crate::handle_session_request(
                "codex.runtime.messages",
                payload,
                &std::env::temp_dir(),
            )
            .unwrap();
            let reply = result["data"].as_array().and_then(|list| {
                list.iter().find(|m| {
                    m["type"] == "assistant"
                        && previous.is_none_or(|id| m["id"].as_str() != Some(id))
                        && m.pointer("/time/completed")
                            .and_then(Value::as_u64)
                            .unwrap_or(0)
                            > 0
                })
            });
            if let Some(reply) = reply {
                let text = reply["content"]
                    .as_array()
                    .map(|parts| {
                        parts
                            .iter()
                            .filter_map(|p| p["text"].as_str())
                            .collect::<Vec<_>>()
                            .join("\n")
                    })
                    .unwrap_or_default();
                if text.contains("OK") {
                    return reply["id"].as_str().unwrap_or("").to_string();
                }
            }
            assert!(
                step < 30
                    || crate::agents::live_running(payload["session"].as_str().unwrap())
                        != Some(false),
                "CLI exited without its expected completed reply"
            );
            std::thread::sleep(std::time::Duration::from_millis(100));
        }
        panic!("No completed DeepSeek response was returned by the official CLI");
    };
    payload["session"] = json!(&id);
    let first_reply = wait(&payload, None);
    payload["text"] = json!("Once more, reply exactly OK. Do not use tools.");
    let second =
        crate::handle_session_request("codex.runtime.prompt", &payload, &std::env::temp_dir())
            .expect("Official CLI resume failed");
    assert_eq!(second["id"], id);
    for _ in 0..1800 {
        if crate::agents::live_running(&id) != Some(true) {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
    let second_reply = wait(&payload, Some(&first_reply));
    assert_ne!(first_reply, second_reply);
    assert!(runtime.home().join("sessions").is_dir());
    assert!(
        !runtime.home().join("auth.json").exists(),
        "DeepSeek must not import ChatGPT credentials"
    );
}

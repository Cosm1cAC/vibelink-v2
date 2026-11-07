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
            (error.contains("cannot enforce tenant filesystem isolation")
                || error.contains("codex exited before starting thread")),
            "{error}"
        );
        return;
    }
    #[cfg(windows)]
    if runtime.beta {
        verify_windows_mxc_boundary(&runtime);
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

/// Exercise the OS boundary with the exact production CLI home and child env.
#[cfg(windows)]
fn verify_windows_mxc_boundary(runtime: &crate::codex_runtime::Runtime) {
    use std::{fs, net::TcpListener, process::Command};
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let port = listener.local_addr().unwrap().port();
    let mut peer = runtime.clone();
    peer.scope.push_str("_peer");
    fs::create_dir_all(peer.home()).unwrap();
    let peer_file = peer.home().join("private-probe.txt");
    fs::write(&peer_file, "synthetic peer data").unwrap();
    for read_only in [false, true] {
        let mut r = runtime.clone();
        r.read_only = read_only;
        r.prepare().unwrap();
        let inside = r.workspace().join("inside-probe.txt");
        let written = r.workspace().join(if read_only {
            "readonly-write-probe.txt"
        } else {
            "write-probe.txt"
        });
        let outside = r.home().parent().unwrap().join("outside-probe.txt");
        fs::write(&inside, "INSIDE").unwrap();
        fs::write(&outside, "synthetic host data").unwrap();
        let script = format!(
            r#"$ErrorActionPreference='Stop';
if ((Get-Content -LiteralPath '{inside}' -Raw).Trim() -ne 'INSIDE') {{exit 2}};
foreach ($p in @('{outside}','{peer}','{config}')) {{try {{$null=Get-Content -LiteralPath $p -ErrorAction Stop;Write-Output 'READ_LEAK';exit 3}}catch {{Write-Output 'READ_BLOCKED'}}}};
try {{Set-Content -LiteralPath '{written}' -Value 'CREATED' -ErrorAction Stop;Write-Output 'WRITE_OK'}} catch {{Write-Output 'WRITE_BLOCKED'}};
& curl.exe --version | Out-Null; if ($LASTEXITCODE -ne 0) {{exit 5}}; & curl.exe --noproxy '*' --connect-timeout 1 --max-time 2 --silent http://127.0.0.1:{port}/ | Out-Null;
Write-Output 'BOUNDARY_OK';exit 0"#,
            inside = inside.display(),
            outside = outside.display(),
            peer = peer_file.display(),
            config = r.home().join("config.toml").display(),
            written = written.display()
        );
        let exe = crate::agents::find_codex_exe().expect("Official CLI required");
        let mut configured = Command::new(&exe);
        r.configure(&mut configured).unwrap();
        let mut cmd = Command::new(exe);
        cmd.env_clear();
        for (key, value) in configured.get_envs() {
            if let Some(value) = value {
                cmd.env(key, value);
            }
        }
        let output = cmd
            .args(["sandbox", "-P", "vibelink_beta", "-C"])
            .arg(r.workspace())
            .args(["powershell.exe", "-NoProfile", "-Command", &script])
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "MXC boundary probe refused: {}",
            String::from_utf8_lossy(&output.stderr)
        );
        let text = String::from_utf8_lossy(&output.stdout);
        assert_eq!(text.matches("READ_BLOCKED").count(), 3);
        assert!(text.contains("BOUNDARY_OK"));
        assert!(text.contains(if read_only {
            "WRITE_BLOCKED"
        } else {
            "WRITE_OK"
        }));
        assert_eq!(written.exists(), !read_only);
        assert!(
            listener.accept().is_err(),
            "Tool connected to the host network"
        );
    }
    runtime.prepare().unwrap();
}

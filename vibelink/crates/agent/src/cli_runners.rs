//! Real per-user CLI processes. No subscription inference proxies or shared OpenCode service.
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    fs,
    io::{BufRead, BufReader, Read, Write},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{Arc, Mutex, OnceLock},
};
use vibelink_protocol::now_ms;
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Session {
    id: String,
    #[serde(default)]
    job_id: String,
    #[serde(default)]
    running: bool,
    native_id: String,
    engine: String,
    scope: String,
    model: String,
    cwd: String,
    title: String,
    messages: Vec<Value>,
    error: String,
    updated: u64,
}
struct Running {
    child: Mutex<Child>,
    job: crate::cli_process::Job,
}
type Children = Mutex<HashMap<String, Arc<Running>>>;
static START_LOCK: Mutex<()> = Mutex::new(());
static CHILDREN: OnceLock<Children> = OnceLock::new();
fn children() -> &'static Children {
    CHILDREN.get_or_init(Mutex::default)
}
fn key(id: &str) -> String {
    format!("{}_{id}", crate::codex_runtime::current().scope)
}
fn root(engine: &str) -> PathBuf {
    crate::codex_runtime::current()
        .home()
        .parent()
        .unwrap()
        .join(engine)
}
fn file(engine: &str, id: &str) -> Result<PathBuf, String> {
    if id.is_empty()
        || !id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-'))
    {
        return Err("Invalid CLI session ID".into());
    }
    Ok(root(engine).join("bindings").join(format!("{id}.json")))
}
fn persist(s: &Session) -> Result<(), String> {
    let path = file(&s.engine, &s.id)?;
    fs::create_dir_all(path.parent().unwrap()).map_err(|e| e.to_string())?;
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, serde_json::to_vec(s).map_err(|e| e.to_string())?)
        .map_err(|e| e.to_string())?;
    fs::rename(tmp, path).map_err(|e| e.to_string())
}
fn load(engine: &str, id: &str) -> Result<Session, String> {
    let s: Session = serde_json::from_slice(
        &fs::read(file(engine, id)?)
            .map_err(|_| "CLI session not found in this user/runner scope")?,
    )
    .map_err(|_| "Corrupt CLI session binding")?;
    let r = crate::codex_runtime::current();
    if s.engine != engine || s.scope != r.scope || s.model != r.model {
        return Err("CLI session belongs to another runner/model/user".into());
    }
    Ok(s)
}
fn executable(engine: &str) -> Result<PathBuf, String> {
    let home =
        std::env::var(if cfg!(windows) { "USERPROFILE" } else { "HOME" }).unwrap_or_default();
    let candidates = match engine {
        "opencode2" => vec![
            PathBuf::from(&home)
                .join("AppData/Roaming/npm/node_modules/@opencode/cli/bin/opencode.exe"),
            PathBuf::from(&home).join(".opencode/bin/opencode2"),
            PathBuf::from(&home).join(".opencode/bin/opencode"),
            PathBuf::from("/usr/local/bin/opencode"),
            PathBuf::from("/opt/homebrew/bin/opencode"),
        ],
        "claude" => vec![PathBuf::from(&home).join(if cfg!(windows) {
            ".local/bin/claude.exe"
        } else {
            ".local/bin/claude"
        })],
        "gemini" => vec![PathBuf::from(&home).join("AppData/Roaming/npm/gemini.cmd")],
        _ => return Err("Unsupported CLI runner".into()),
    };
    for p in candidates {
        if p.is_file() && (engine != "opencode2" || is_opencode2(&p)) {
            return Ok(p);
        }
    }
    for dir in std::env::split_paths(&std::env::var_os("PATH").unwrap_or_default()) {
        for name in if cfg!(windows) {
            vec![format!("{engine}.exe"), format!("{engine}.cmd")]
        } else {
            vec![engine.into()]
        } {
            let p = dir.join(name);
            if p.is_file() {
                return Ok(p);
            }
        }
    }
    Err(format!(
        "Install the official {engine} CLI before using this runner"
    ))
}
fn is_opencode2(path: &Path) -> bool {
    let mut cmd = Command::new(path);
    cmd.arg("--version");
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x08000000);
    }
    cmd.output()
        .ok()
        .filter(|o| o.status.success())
        .is_some_and(|o| {
            String::from_utf8_lossy(&o.stdout)
                .split_whitespace()
                .any(|part| part.trim_start_matches('v').starts_with("2."))
        })
}
fn base_env(cmd: &mut Command, home: &Path) {
    cmd.env_clear();
    for k in [
        "SYSTEMROOT",
        "WINDIR",
        "COMSPEC",
        "PATH",
        "PATHEXT",
        "TEMP",
        "TMP",
        "SystemDrive",
        "HTTP_PROXY",
        "HTTPS_PROXY",
        "ALL_PROXY",
        "NO_PROXY",
    ] {
        if let Some(v) = std::env::var_os(k) {
            cmd.env(k, v);
        }
    }
    cmd.env("HOME", home)
        .env("USERPROFILE", home)
        .env("APPDATA", home.join("roaming"))
        .env("LOCALAPPDATA", home.join("local"))
        .env("XDG_CONFIG_HOME", home.join("config"))
        .env("XDG_DATA_HOME", home.join("data"))
        .env("XDG_STATE_HOME", home.join("state"))
        .env("XDG_CACHE_HOME", home.join("cache"));
}
fn configure_subscription_profile(cmd: &mut Command, engine: &str) -> PathBuf {
    let official = crate::subscription_policy::cli_home()
        .parent()
        .unwrap()
        .join(engine);
    base_env(cmd, &official);
    cmd.env(
        if engine == "claude" {
            "CLAUDE_CONFIG_DIR"
        } else {
            "GEMINI_CLI_HOME"
        },
        &official,
    );
    official
}
fn opencode_config(gateway: &str, read_only: bool) -> Value {
    let mut rules = vec![json!({"action":"*","resource":"*","effect":"deny"})];
    for action in ["read", "glob", "grep"] {
        rules.push(json!({"action":action,"resource":"*","effect":"allow"}));
    }
    if !read_only {
        for action in ["edit", "shell"] {
            rules.push(json!({"action":action,"resource":"*","effect":"allow"}));
        }
    }
    let mut policies = vec![
        json!({"action":"provider.use","resource":"*","effect":"deny"}),
        json!({"action":"provider.use","resource":"vibelink","effect":"allow"}),
        json!({"action":"plugin.use","resource":"*","effect":"deny"}),
        json!({"action":"mcp.use","resource":"*","effect":"deny"}),
    ];
    if read_only {
        for action in ["edit:*", "shell:*", "subagent:*", "execute:*"] {
            policies.push(json!({"action":"tool.use","resource":action,"effect":"deny"}));
        }
    }
    json!({"model":"vibelink/auto/deepseek-v4.1-flash","small_model":"vibelink/auto/deepseek-v4.1-flash","enabled_providers":["vibelink"],"providers":{"vibelink":{"name":"VibeLink DeepSeek","env":["VIBELINK_MODEL_GATEWAY_TOKEN"],"package":"@opencode/ai/providers/openai-compatible","settings":{"baseURL":gateway},"models":{"auto/deepseek-v4.1-flash":{"name":"DeepSeek"}}}},"permissions":rules,"policies":policies,"update":"disable","snapshots":false})
}
fn command(engine: &str, s: &Session, text: &str) -> Result<(Command, Option<Vec<u8>>), String> {
    let r = crate::codex_runtime::current();
    let home = root(engine);
    fs::create_dir_all(&home).map_err(|e| e.to_string())?;
    if r.beta {
        if engine != "opencode2" {
            return Err("Beta requires the isolated OpenCode2 worker".into());
        }
        let mut cmd = Command::new("wsl.exe");
        base_env(&mut cmd, &home);
        cmd.args(["-d", "Ubuntu", "-u", "root", "--", &worker_driver()]);
        return Ok((cmd,Some(serde_json::to_vec(&json!({"version":1,"scope":r.scope,"jobId":s.job_id,"model":r.model,"gateway":r.gateway,"token":r.token,"session":if s.native_id.is_empty(){Value::Null}else{json!(s.native_id)},"prompt":text,"readOnly":r.read_only,"files":[]})).map_err(|e|e.to_string())?)));
    }
    let mut cmd = Command::new(executable(engine)?);
    base_env(&mut cmd, &home);
    cmd.current_dir(&s.cwd);
    match engine {
        "opencode2" => {
            if r.gateway.is_empty() || r.token.is_empty() {
                return Err("DeepSeek requires a per-user Hub metering grant".into());
            }
            let cfg = home.join("config/opencode");
            fs::create_dir_all(&cfg).map_err(|e| e.to_string())?;
            fs::write(
                cfg.join("opencode.json"),
                serde_json::to_vec(&opencode_config(&r.gateway, r.read_only)).unwrap(),
            )
            .map_err(|e| e.to_string())?;
            cmd.env("OPENCODE_CONFIG_PROJECT_DISABLE", "true")
                .env("OPENCODE_DISABLE_CLAUDE_CODE", "true")
                .env(
                    "OPENCODE_CONFIG_CONTENT",
                    serde_json::to_string(&opencode_config(&r.gateway, r.read_only)).unwrap(),
                );
            cmd.env("VIBELINK_MODEL_GATEWAY_TOKEN", &r.token).args([
                "run",
                "--standalone",
                "--format",
                "json",
                "--model",
                "vibelink/auto/deepseek-v4.1-flash",
            ]);
            if !s.native_id.is_empty() {
                cmd.args(["--session", &s.native_id]);
            }
            cmd.arg(text);
        }
        "claude" => {
            // Login is performed only in this dedicated official subscription profile.
            // Never inherit ~/.claude/settings.json: its token may belong to a proxy.
            let official = configure_subscription_profile(&mut cmd, "claude");
            fs::create_dir_all(&official).map_err(|_| "Cannot prepare official Claude profile")?;
            cmd.env("CLAUDE_CONFIG_DIR", &official).args([
                "--print",
                "--verbose",
                "--output-format",
                "stream-json",
                "--setting-sources",
                "",
                "--settings",
                "{}",
                "--strict-mcp-config",
                "--permission-mode",
                if r.read_only { "plan" } else { "acceptEdits" },
            ]);
            if !s.native_id.is_empty() {
                cmd.args(["--resume", &s.native_id]);
            }
            if r.model != "claude" {
                cmd.args(["--model", &r.model]);
            }
            cmd.arg(text);
        }
        "gemini" => {
            let official = configure_subscription_profile(&mut cmd, "gemini");
            fs::create_dir_all(&official).map_err(|_| "Cannot prepare official Gemini profile")?;
            crate::gemini_policy::prepare(&mut cmd, &official, Path::new(&s.cwd))?;
            cmd.args([
                "--output-format",
                "stream-json",
                "--approval-mode",
                if r.read_only { "plan" } else { "default" },
            ]);
            if !s.native_id.is_empty() {
                cmd.args(["--resume", &s.native_id]);
            }
            if r.model != "gemini" {
                cmd.args(["--model", &r.model]);
            }
            cmd.args(["--prompt", text]);
        }
        _ => return Err("Unsupported CLI".into()),
    };
    Ok((cmd, None))
}
fn native_id(event: &Value) -> Option<&str> {
    event["sessionID"]
        .as_str()
        .or_else(|| event["session_id"].as_str())
}
fn assistant_text(engine: &str, event: &Value) -> String {
    if engine == "opencode2" && event["type"] == "text" {
        return event
            .pointer("/part/text")
            .and_then(Value::as_str)
            .unwrap_or("")
            .into();
    }
    if engine == "claude" && event["type"] == "assistant" {
        return event
            .pointer("/message/content")
            .and_then(Value::as_array)
            .map(|a| {
                a.iter()
                    .filter_map(|p| p["text"].as_str())
                    .collect::<Vec<_>>()
                    .join("\n")
            })
            .unwrap_or_default();
    }
    if engine == "gemini" && event["type"] == "message" && event["role"] == "assistant" {
        return event["content"].as_str().unwrap_or("").into();
    }
    String::new()
}
fn start(engine: &str, id: &str, payload: &Value) -> Result<Value, String> {
    let _start = START_LOCK
        .lock()
        .map_err(|_| "CLI start lock unavailable")?;
    let text = payload["text"].as_str().unwrap_or("");
    let mut s = load(engine, id)?;
    if s.running {
        return Err(
            "Previous CLI turn remains active or uncertain; reconcile/cancel before resuming"
                .into(),
        );
    }
    s.job_id = crate::agents::new_uuid_v4();
    if children().lock().unwrap().contains_key(&key(id)) {
        return Err("A turn is already running in this CLI session".into());
    }
    let mut actual_text = payload
        .pointer("/botContext/systemPrompt")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .map(|system| format!("{system}\n\n{text}"))
        .unwrap_or_else(|| text.into());
    if !crate::codex_runtime::current().beta {
        let files = crate::agents::save_uploaded_files(&s.cwd, payload.get("files"), true);
        if !files.is_empty() {
            actual_text.push_str(&format!(
                "\n\nAttached input files: {}",
                serde_json::to_string(&files).unwrap()
            ));
        }
    }
    let (mut cmd, mut input) = command(engine, &s, &actual_text)?;
    if let Some(bytes) = &mut input {
        let mut request: Value =
            serde_json::from_slice(bytes).map_err(|_| "Invalid worker request")?;
        request["files"] = payload.get("files").cloned().unwrap_or(json!([]));
        *bytes = serde_json::to_vec(&request).unwrap();
    }
    cmd.stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .stdin(if input.is_some() {
            Stdio::piped()
        } else {
            Stdio::null()
        });
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x08000000);
    }
    let started = now_ms();
    s.messages.push(json!({"id":format!("{id}-user-{started}"),"type":"user","status":"completed","content":[{"type":"text","text":crate::agents::extract_clean_user_prompt(text)}],"time":{"created":started,"completed":started}}));
    s.error.clear();
    s.updated = started;
    s.running = true;
    persist(&s)?;
    let (mut child, job) = match crate::cli_process::Job::spawn(&mut cmd) {
        Ok(value) => value,
        Err(error) => {
            s.running = false;
            s.error = error.clone();
            let _ = persist(&s);
            return Err(error);
        }
    };
    if let Some(input) = input {
        if let Some(mut stdin) = child.stdin.take() {
            stdin
                .write_all(&input)
                .map_err(|_| "Cannot deliver isolated worker request")?;
        }
    }
    let stdout = child.stdout.take().unwrap();
    let stderr = child.stderr.take().unwrap();
    let child = Arc::new(Running {
        child: Mutex::new(child),
        job,
    });
    children().lock().unwrap().insert(key(id), child.clone());
    let child_key = key(id);
    let engine_copy = engine.to_string();
    let engine = engine_copy.clone();
    crate::codex_runtime::spawn(move || {
        let err_reader = std::thread::spawn(move || {
            let mut b = Vec::new();
            let _ = stderr.take(131072).read_to_end(&mut b);
            String::from_utf8_lossy(&b).to_string()
        });
        let mut output = String::new();
        let mut turn_error = String::new();
        for line in BufReader::new(stdout).lines().map_while(Result::ok) {
            if let Ok(event) = serde_json::from_str::<Value>(&line) {
                if let Some(native) = native_id(&event) {
                    s.native_id = native.into();
                    let _ = persist(&s);
                }
                output.push_str(&assistant_text(&engine, &event));
                if event["type"] == "error" || event["is_error"] == true {
                    turn_error = event["result"]
                        .as_str()
                        .or_else(|| event.pointer("/error/message").and_then(Value::as_str))
                        .or_else(|| event["error"].as_str())
                        .unwrap_or("CLI reported a model execution error")
                        .into();
                }
            }
        }
        let status = child.child.lock().unwrap().wait();
        let stderr = err_reader.join().unwrap_or_default();
        let mut quiescent = child.job.quiesce();
        if crate::codex_runtime::current().beta {
            quiescent = worker_control("status", &s)
                .is_ok_and(|state| state["known"] == true && state["quiescent"] == true);
        }
        let success =
            status.as_ref().is_ok_and(|s| s.success()) && turn_error.is_empty() && quiescent;
        let completed = now_ms();
        s.updated = completed;
        s.running = !quiescent;
        if !success {
            s.error = crate::operation_receipts::diagnostic_cause(
                if !turn_error.is_empty() {
                    turn_error.trim()
                } else if stderr.trim().is_empty() {
                    "CLI turn failed"
                } else {
                    stderr.trim()
                },
                &json!({"token":crate::codex_runtime::current().token}),
            );
        }
        s.messages.push(json!({"id":format!("{}-assistant-{started}",s.id),"type":"assistant","status":if success{"completed"}else{"error"},"content":[{"type":"text","text":output}],"time":{"created":started,"completed":if success{completed}else{0}}}));
        let _ = persist(&s);
        children().lock().unwrap().remove(&child_key);
    });
    Ok(
        json!({"id":id,"agent":engine_copy,"model":crate::codex_runtime::current().model,"running":true}),
    )
}
fn worker_driver() -> String {
    #[cfg(test)]
    if let Ok(path) = std::env::var("VIBELINK_CLI_SMOKE_WORKER") {
        if path.starts_with("/opt/vibelink-worker/tests/")
            && path.ends_with("/driver.py")
            && !path.contains("..")
        {
            return path;
        }
    }
    "/opt/vibelink-worker/driver.py".into()
}
fn worker_control(operation: &str, s: &Session) -> Result<Value, String> {
    let mut cmd = Command::new("wsl.exe");
    base_env(&mut cmd, &root(&s.engine));
    cmd.args(["-d", "Ubuntu", "-u", "root", "--", &worker_driver()])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x08000000);
    }
    let mut child = cmd
        .spawn()
        .map_err(|_| "Isolated worker control unavailable")?;
    child
        .stdin
        .take()
        .unwrap()
        .write_all(
            &serde_json::to_vec(
                &json!({"version":1,"operation":operation,"scope":s.scope,"jobId":s.job_id}),
            )
            .unwrap(),
        )
        .map_err(|_| "Cannot contact isolated worker")?;
    let result = child
        .wait_with_output()
        .map_err(|_| "Isolated worker status unavailable")?;
    if !result.status.success() {
        return Err("Isolated worker cannot prove this job quiescent".into());
    }
    serde_json::from_slice(&result.stdout).map_err(|_| "Invalid isolated worker status".into())
}
pub fn handle(engine: &str, operation: &str, payload: &Value) -> Result<Value, String> {
    let id = payload["session"].as_str().unwrap_or("");
    let r = crate::codex_runtime::current();
    match operation {
        "workspace" => crate::agents::codex_workspace(payload),
        "workspace.prepare" => crate::agents::codex_workspace_prepare(payload),
        "create" => {
            let id = format!("{}_{}", engine, crate::agents::new_uuid_v4());
            let cwd = if r.beta {
                r.workspace()
            } else {
                let p = payload["cwd"]
                    .as_str()
                    .filter(|p| !p.is_empty())
                    .unwrap_or(".");
                PathBuf::from(p)
            };
            fs::create_dir_all(&cwd).map_err(|e| e.to_string())?;
            let cwd = fs::canonicalize(cwd)
                .map_err(|e| e.to_string())?
                .to_string_lossy()
                .to_string();
            r.validate_group_cwd(&cwd)?;
            let text = payload["text"].as_str().unwrap_or("");
            let s = Session {
                id: id.clone(),
                job_id: String::new(),
                running: false,
                native_id: String::new(),
                engine: engine.into(),
                scope: r.scope.clone(),
                model: r.model.clone(),
                cwd,
                title: text.chars().take(60).collect(),
                messages: vec![],
                error: String::new(),
                updated: now_ms(),
            };
            persist(&s)?;
            if text.is_empty() {
                Ok(json!({"id":id,"agent":engine,"model":r.model}))
            } else {
                start(engine, &id, payload)
            }
        }
        "prompt" => start(engine, id, payload),
        "messages" => {
            let s = load(engine, id)?;
            let mut out = json!({"data":s.messages.iter().rev().cloned().collect::<Vec<_>>(),"running":children().lock().unwrap().contains_key(&key(id)),"nativeSessionId":s.native_id});
            if !s.error.is_empty() {
                out["turnError"] = json!({"code":"cli_turn_failed","message":s.error,"sessionId":id,"userText":s.messages.iter().rev().find(|m|m["type"]=="user").and_then(|m|m.pointer("/content/0/text")).and_then(Value::as_str).unwrap_or(""),"startedAt":s.updated});
            }
            Ok(out)
        }
        "lifecycle" => {
            let s = load(engine, id)?;
            let active = children().lock().unwrap().contains_key(&key(id));
            if active {
                return Ok(json!({"known":true,"running":true,"quiescent":false}));
            }
            if s.running {
                if r.beta {
                    return worker_control("status", &s);
                }
                return Ok(json!({"known":false,"running":false,"quiescent":false}));
            }
            Ok(json!({"known":true,"running":false,"quiescent":true}))
        }
        "interrupt" => {
            let mut s = load(engine, id)?;
            if r.beta {
                let ack = worker_control("cancel", &s)?;
                if ack["type"] != "vibelink_worker_cancel" {
                    return Err("Invalid worker cancellation acknowledgement".into());
                }
                let mut result = worker_control("status", &s)?;
                if children().lock().unwrap().contains_key(&key(id)) {
                    result["known"] = json!(true);
                }
                result["ok"] = json!(true);
                if result["known"] == true
                    && result["quiescent"] == true
                    && !children().lock().unwrap().contains_key(&key(id))
                {
                    s.running = false;
                    s.error = "CLI turn cancelled".into();
                    persist(&s)?;
                }
                return Ok(result);
            }
            if let Some(child) = children().lock().unwrap().get(&key(id)).cloned() {
                child.job.terminate();
                return Ok(json!({"ok":true,"known":true}));
            }
            Ok(json!({"ok":true,"known":!s.running}))
        }
        "list" => {
            let mut sessions = Vec::new();
            if let Ok(entries) = fs::read_dir(root(engine).join("bindings")) {
                for p in entries.flatten() {
                    if let Ok(bytes) = fs::read(p.path()) {
                        if let Ok(s) = serde_json::from_slice::<Session>(&bytes) {
                            if s.scope == r.scope && s.engine == engine && s.model == r.model {
                                sessions.push(json!({"id":s.id,"title":s.title,"cwd":s.cwd,"model":s.model,"agent":engine,"updated":s.updated}));
                            }
                        }
                    }
                }
            }
            Ok(json!({"agents":[{"id":engine,"name":engine,"sessions":sessions}]}))
        }
        _ => Err("Unsupported CLI session operation".into()),
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn provider_is_metered_v2_not_retired_v1() {
        let c = opencode_config("http://127.0.0.1:1234/api/llm/v1", true);
        assert_eq!(
            c.pointer("/providers/vibelink/settings/baseURL").unwrap(),
            "http://127.0.0.1:1234/api/llm/v1"
        );
        assert!(
            c["policies"]
                .as_array()
                .unwrap()
                .iter()
                .any(|r| r["resource"] == "shell:*" && r["effect"] == "deny")
        );
        assert!(c.get("provider").is_none());
    }
    #[test]
    fn cli_stream_preserves_native_session() {
        let v = json!({"type":"text","sessionID":"ses_native","part":{"text":"OK"}});
        assert_eq!(native_id(&v), Some("ses_native"));
        assert_eq!(assistant_text("opencode2", &v), "OK");
    }
}

#[cfg(test)]
#[test]
#[ignore = "Requires VIBELINK_CLI_SMOKE_REQUEST with a disposable metered request"]
fn real_cli_create_and_resume() {
    let path = std::env::var("VIBELINK_CLI_SMOKE_REQUEST").unwrap();
    let mut p: Value = serde_json::from_slice(&fs::read(path).unwrap()).unwrap();
    p["text"] = json!("Reply exactly OK. Do not use tools.");
    let first =
        crate::handle_session_request("cli.runtime.create", &p, &std::env::temp_dir()).unwrap();
    let id = first["id"].as_str().unwrap().to_string();
    p["session"] = json!(id);
    let wait = |p: &Value, count: usize| {
        for _ in 0..1800 {
            let messages =
                crate::handle_session_request("cli.runtime.messages", p, &std::env::temp_dir())
                    .unwrap();
            assert!(
                messages.get("turnError").is_none(),
                "{}",
                messages["turnError"]
            );
            let replies: Vec<_> = messages["data"]
                .as_array()
                .unwrap()
                .iter()
                .filter(|m| {
                    m["type"] == "assistant"
                        && m.pointer("/time/completed")
                            .and_then(Value::as_u64)
                            .unwrap_or(0)
                            > 0
                })
                .collect();
            if replies.len() >= count {
                assert!(
                    replies
                        .last()
                        .unwrap()
                        .pointer("/content/0/text")
                        .and_then(Value::as_str)
                        .unwrap_or("")
                        .contains("OK")
                );
                return;
            }
            std::thread::sleep(std::time::Duration::from_millis(100));
        }
        panic!("Timed out waiting for CLI result");
    };
    wait(&p, 1);
    let runtime = crate::codex_runtime::Runtime::parse(&p).unwrap();
    let native = crate::codex_runtime::with(runtime.clone(), || {
        load(&runtime.engine, &id).unwrap().native_id
    });
    assert!(!native.is_empty());
    p["text"] = json!("Once again reply exactly OK. Do not use tools.");
    let resumed =
        crate::handle_session_request("cli.runtime.prompt", &p, &std::env::temp_dir()).unwrap();
    assert_eq!(resumed["id"], id);
    wait(&p, 2);
    assert_eq!(
        native,
        crate::codex_runtime::with(runtime.clone(), || load(&runtime.engine, &id)
            .unwrap()
            .native_id)
    );
    println!(
        "Real {} CLI create/resume passed; native session preserved",
        runtime.engine
    );
}

#[cfg(test)]
mod binding_tests {
    use super::*;
    #[test]
    fn journal_cannot_cross_runner_model_or_owner_and_restart_never_fakes_quiescence() {
        let scope = format!(
            "unit_cli_{}",
            crate::agents::new_uuid_v4().replace('-', "_")
        );
        let runtime = crate::codex_runtime::Runtime {
            engine: "opencode2".into(),
            scope: scope.clone(),
            model: crate::codex_runtime::DEEPSEEK.into(),
            ..Default::default()
        };
        crate::codex_runtime::with(runtime.clone(), || {
            let id = "opencode2_owned";
            let s = Session {
                id: id.into(),
                job_id: crate::agents::new_uuid_v4(),
                running: true,
                native_id: "ses_native".into(),
                engine: "opencode2".into(),
                scope: scope.clone(),
                model: runtime.model.clone(),
                cwd: ".".into(),
                title: "test".into(),
                messages: vec![],
                error: String::new(),
                updated: 1,
            };
            persist(&s).unwrap();
            let state = handle("opencode2", "lifecycle", &json!({"session":id})).unwrap();
            assert_eq!(state["quiescent"], false);
            assert_eq!(state["known"], false);
            assert!(
                start("opencode2", id, &json!({"text":"must never dispatch"}))
                    .unwrap_err()
                    .contains("uncertain")
            );
            assert!(load("claude", id).is_err());
            let mut other = runtime.clone();
            other.model = "gpt-6".into();
            crate::codex_runtime::with(other, || assert!(load("opencode2", id).is_err()));
            let mut other = runtime.clone();
            other.scope.push_str("_peer");
            crate::codex_runtime::with(other, || assert!(load("opencode2", id).is_err()));
            assert!(file("opencode2", "../../other").is_err());
        });
    }
    #[test]
    fn subscribed_clis_share_the_embedded_login_profile_not_a_model_scope() {
        for engine in ["claude", "gemini"] {
            let mut cmd = Command::new("unused");
            let expected = crate::subscription_policy::cli_home()
                .parent()
                .unwrap()
                .join(engine);
            assert_eq!(configure_subscription_profile(&mut cmd, engine), expected);
            let key = if engine == "claude" {
                "CLAUDE_CONFIG_DIR"
            } else {
                "GEMINI_CLI_HOME"
            };
            for name in ["HOME", "USERPROFILE", key] {
                assert!(
                    cmd.get_envs()
                        .any(|(k, v)| k == name && v == Some(expected.as_os_str()))
                );
            }
        }
    }
    #[test]
    fn official_and_metered_processes_do_not_inherit_provider_or_admin_secrets() {
        let mut cmd = Command::new("unused");
        base_env(&mut cmd, Path::new("profile"));
        let vars: Vec<_> = cmd
            .get_envs()
            .filter_map(|(k, v)| v.map(|_| k.to_string_lossy().to_string()))
            .collect();
        assert!(!vars.iter().any(|k| k.starts_with("ANTHROPIC_")
            || k == "OPENAI_API_KEY"
            || k == "VIBELINK_TOKEN"
            || k == "GEMINI_API_KEY"
            || k == "GOOGLE_API_KEY"));
        assert!(vars.contains(&"HOME".into()));
    }
}

#[cfg(test)]
#[test]
#[ignore = "Requires isolated WSL worker and temporary metered Hub request"]
fn real_isolated_cli_cancel_is_quiescent_and_cannot_cross_scope() {
    let request = std::env::var("VIBELINK_CLI_SMOKE_REQUEST").unwrap();
    let mut p: Value = serde_json::from_slice(&fs::read(request).unwrap()).unwrap();
    p["codexRuntime"]["token"] = json!("cancel-probe-invalid-grant");
    p["text"] = json!("Reply exactly OK. Do not use tools.");
    let r = crate::codex_runtime::Runtime::parse(&p).unwrap();
    assert!(r.beta);
    let created =
        crate::handle_session_request("cli.runtime.create", &p, &std::env::temp_dir()).unwrap();
    p["session"] = created["id"].clone();
    let mut peer = p.clone();
    peer["codexRuntime"]["scope"] = json!(format!("{}_peer", r.scope));
    assert!(
        crate::handle_session_request("cli.runtime.interrupt", &peer, &std::env::temp_dir())
            .is_err()
    );
    let result =
        crate::handle_session_request("cli.runtime.interrupt", &p, &std::env::temp_dir()).unwrap();
    assert_eq!(result["known"], true);
    for _ in 0..150 {
        let state =
            crate::handle_session_request("cli.runtime.lifecycle", &p, &std::env::temp_dir())
                .unwrap();
        if state["quiescent"] == true {
            assert_eq!(state["known"], true);
            println!("Scoped WSL cancel quiescent; peer scope rejected");
            return;
        }
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
    panic!("Cancelled worker could not prove quiescence");
}

#![cfg_attr(windows, windows_subsystem = "windows")]
//! mesh agent - heartbeat + whitelisted actions. No async runtime, ~5 MB RSS.
//!
//!   VIBELINK_HUB=http://192.168.137.1:18995
//!   VIBELINK_TOKEN=<hub token>
//!   VIBELINK_DEVICE=laptop
mod agents;
mod codex_runtime;
mod subscription_policy;
mod pty_and_worktree;
mod roots;
mod schedule;
mod opencode;

use vibelink_protocol::{now_ms, DeviceReport, Latency, MemInfo, ProxyState};
use std::{
    collections::BTreeMap,
    io::{Read, Write},
    net::{IpAddr, SocketAddr, TcpStream, ToSocketAddrs},
    process::{Command, Stdio},
    sync::OnceLock,
    thread::{self, sleep},
    time::{Duration, Instant},
};

const AGENT_VERSION: &str = "0.2.9";
const PUSH: Duration = Duration::from_secs(5);
const SLOW: Duration = Duration::from_secs(30);

const WIN_PS: &str = r"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe";
const RESTARTABLE: &[&str] = &["WProxy", "WProxyHysteria", "PcProxyFailover", "PcLanGateway", "PhoneConfigHttp", "VibelinkAgent"];

// ---------------- tiny HTTP client (plain HTTP, Connection: close) ----------------
struct Endpoint {
    host: String,
    port: u16,
}

fn parse_endpoint(base: &str) -> Result<Endpoint, String> {
    let rest = base.trim_end_matches('/');
    let rest = rest.strip_prefix("http://").ok_or("only http:// hubs are supported")?;
    let (host, port) = match rest.rsplit_once(':') {
        Some((h, p)) => (h.to_string(), p.parse::<u16>().map_err(|e| e.to_string())?),
        None => (rest.to_string(), 80),
    };
    Ok(Endpoint { host, port })
}

fn http_call(ep: &Endpoint, method: &str, path: &str, token: &str, body: Option<&str>) -> Result<(u16, String), String> {
    let addr: SocketAddr = (ep.host.as_str(), ep.port)
        .to_socket_addrs()
        .map_err(|e| e.to_string())?
        .next()
        .ok_or("resolve failed")?;
    let mut stream = TcpStream::connect_timeout(&addr, Duration::from_secs(5)).map_err(|e| e.to_string())?;
    stream.set_read_timeout(Some(Duration::from_secs(15))).ok();
    stream.set_write_timeout(Some(Duration::from_secs(10))).ok();

    let payload = body.unwrap_or("");
    let req = format!(
        "{method} {path} HTTP/1.1\r\nHost: {}:{}\r\nx-mesh-token: {token}\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{payload}",
        ep.host,
        ep.port,
        payload.len()
    );
    stream.write_all(req.as_bytes()).map_err(|e| e.to_string())?;

    let mut raw = Vec::new();
    stream.read_to_end(&mut raw).map_err(|e| e.to_string())?;
    let text = String::from_utf8_lossy(&raw).to_string();
    let (head, body) = text.split_once("\r\n\r\n").ok_or("malformed response")?;
    let mut lines = head.lines();
    let status: u16 = lines
        .next()
        .and_then(|l| l.split_whitespace().nth(1))
        .and_then(|s| s.parse().ok())
        .ok_or("bad status line")?;
    let chunked = head.to_ascii_lowercase().contains("transfer-encoding: chunked");
    let body = if chunked { dechunk(body) } else { body.to_string() };
    Ok((status, body))
}

fn dechunk(s: &str) -> String {
    let mut out = String::new();
    let bytes = s.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        let line_end = match s[i..].find("\r\n") {
            Some(p) => i + p,
            None => break,
        };
        let size = usize::from_str_radix(s[i..line_end].trim(), 16).unwrap_or(0);
        if size == 0 {
            break;
        }
        let start = line_end + 2;
        let end = (start + size).min(bytes.len());
        out.push_str(&s[start..end]);
        i = end + 2;
    }
    out
}

// ---------------- collectors ----------------
fn no_window_cmd(program: &str) -> Command {
    let mut c = Command::new(program);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        c.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    c
}

fn run(cmd: &str, args: &[&str]) -> String {
    no_window_cmd(cmd)
        .args(args)
        .output()
        .map(|o| String::from_utf8_lossy(&o.stdout).to_string())
        .unwrap_or_default()
}

fn ips() -> Vec<String> {
    let mut out = Vec::new();
    if let Ok(list) = if_addrs::get_if_addrs() {
        for a in list {
            if let IpAddr::V4(v4) = a.ip() {
                if !v4.is_loopback() {
                    out.push(v4.to_string());
                }
            }
        }
    }
    out.sort();
    out.dedup();
    out
}

fn gateway() -> Option<String> {
    if cfg!(windows) {
        for line in run("route", &["print", "-4", "0.0.0.0"]).lines() {
            let f: Vec<&str> = line.split_whitespace().collect();
            if f.len() >= 3 && f[0] == "0.0.0.0" && f[1] == "0.0.0.0" {
                return Some(f[2].to_string());
            }
        }
        None
    } else {
        for line in run("route", &["-n", "get", "default"]).lines() {
            if let Some(rest) = line.trim().strip_prefix("gateway:") {
                return Some(rest.trim().to_string());
            }
        }
        None
    }
}

fn system_proxy() -> ProxyState {
    if cfg!(windows) {
        let key = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings";
        let enable = run("reg", &["query", key, "/v", "ProxyEnable"]);
        let enabled = enable.trim().ends_with("0x1");
        let server = run("reg", &["query", key, "/v", "ProxyServer"]);
        let value = server
            .lines()
            .find(|l| l.contains("ProxyServer"))
            .and_then(|l| l.split_whitespace().last())
            .map(|s| s.to_string())
            .filter(|s| s != "ProxyServer");
        ProxyState { enabled, server: value }
    } else {
        // macOS: `scutil --proxy` prints "Key : Value" lines
        let out = run("scutil", &["--proxy"]);
        let mut enabled = false;
        let mut host: Option<String> = None;
        let mut port: Option<String> = None;
        for line in out.lines() {
            let mut it = line.splitn(2, ':');
            let key = it.next().unwrap_or("").trim();
            let val = it.next().unwrap_or("").trim();
            match key {
                "HTTPSEnable" => enabled = val == "1",
                "HTTPSProxy" => host = Some(val.to_string()),
                "HTTPSPort" => port = Some(val.to_string()),
                _ => {}
            }
        }
        let server = if enabled {
            match (host, port) {
                (Some(h), Some(p)) => Some(format!("{h}:{p}")),
                (Some(h), None) => Some(h),
                _ => None,
            }
        } else {
            None
        };
        ProxyState { enabled, server }
    }
}

fn port_open(port: u16) -> bool {
    let addr: SocketAddr = ([127, 0, 0, 1], port).into();
    TcpStream::connect_timeout(&addr, Duration::from_millis(300)).is_ok()
}

fn probe(proxy: &ProxyState) -> Option<Latency> {
    let server = proxy.server.as_ref()?;
    let (host, port) = server.rsplit_once(':')?;
    let port: u16 = port.parse().ok()?;
    let addr: SocketAddr = (host, port).to_socket_addrs().ok()?.next()?;

    let t0 = Instant::now();
    let mut stream = TcpStream::connect_timeout(&addr, Duration::from_secs(2)).ok()?;
    let tcp_ms = t0.elapsed().as_millis() as u64;

    stream.set_read_timeout(Some(Duration::from_secs(6))).ok();
    let t1 = Instant::now();
    let req = "CONNECT www.google.com:443 HTTP/1.1\r\nHost: www.google.com:443\r\n\r\n";
    stream.write_all(req.as_bytes()).ok()?;
    let mut buf = [0u8; 128];
    let n = stream.read(&mut buf).ok()?;
    let head = String::from_utf8_lossy(&buf[..n]);
    let connect_ms = if head.starts_with("HTTP/1.1 200") || head.starts_with("HTTP/1.0 200") {
        Some(t1.elapsed().as_millis() as u64)
    } else {
        None
    };
    Some(Latency { target: server.clone(), tcp_ms: Some(tcp_ms), connect_ms })
}

fn meminfo() -> (MemInfo, u64) {
    let mut sys = sysinfo::System::new();
    sys.refresh_memory();
    let total = sys.total_memory() / 1024 / 1024;
    let free = sys.available_memory() / 1024 / 1024;
    (MemInfo { free, total }, sysinfo::System::uptime())
}

// ---------------- actions ----------------
fn refresh_wininet() {
    let script = r#"
$sig='[DllImport("wininet.dll",SetLastError=true)]public static extern bool InternetSetOption(IntPtr h,int o,IntPtr b,int l);'
$t=Add-Type -MemberDefinition $sig -Name MeshWininet -Namespace Mesh -PassThru
[void]$t::InternetSetOption([IntPtr]::Zero,39,[IntPtr]::Zero,0)
[void]$t::InternetSetOption([IntPtr]::Zero,37,[IntPtr]::Zero,0)
"#;
    let _ = no_window_cmd(WIN_PS)
        .args(["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script])
        .output();
}

fn set_proxy(server: &str) -> Result<String, String> {
    if cfg!(windows) {
        let key = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings";
        let a = no_window_cmd("reg")
            .args(["add", key, "/v", "ProxyServer", "/t", "REG_SZ", "/d", server, "/f"])
            .output()
            .map_err(|e| e.to_string())?;
        if !a.status.success() {
            return Err(String::from_utf8_lossy(&a.stderr).to_string());
        }
        let b = no_window_cmd("reg")
            .args(["add", key, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "1", "/f"])
            .output()
            .map_err(|e| e.to_string())?;
        if !b.status.success() {
            return Err(String::from_utf8_lossy(&b.stderr).to_string());
        }
        refresh_wininet();
        return Ok(format!("proxy -> {server}"));
    }

    if cfg!(target_os = "macos") {
        let (host, port) = server.rsplit_once(':').ok_or("server must be host:port")?;
        let services = mac_services();
        if services.is_empty() {
            return Err("no enabled network service".into());
        }
        let mut ok = 0;
        for svc in &services {
            let mut all = true;
            for args in [
                vec!["-setwebproxy", svc, host, port],
                vec!["-setsecurewebproxy", svc, host, port],
                vec!["-setwebproxystate", svc, "on"],
                vec!["-setsecurewebproxystate", svc, "on"],
            ] {
                let out = Command::new("networksetup").args(&args).output();
                all &= out.map(|o| o.status.success()).unwrap_or(false);
            }
            if all {
                ok += 1;
            }
        }
        return Ok(format!("proxy -> {server} on {ok}/{} services", services.len()));
    }

    Err("set-proxy: unsupported platform".into())
}

fn clear_proxy() -> Result<String, String> {
    if cfg!(windows) {
        let key = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings";
        let a = no_window_cmd("reg")
            .args(["add", key, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "0", "/f"])
            .output()
            .map_err(|e| e.to_string())?;
        if !a.status.success() {
            return Err(String::from_utf8_lossy(&a.stderr).to_string());
        }
        refresh_wininet();
        return Ok("proxy disabled".into());
    }

    if cfg!(target_os = "macos") {
        let services = mac_services();
        let mut ok = 0;
        for svc in &services {
            let mut all = true;
            for args in [
                vec!["-setwebproxystate", svc, "off"],
                vec!["-setsecurewebproxystate", svc, "off"],
            ] {
                let out = Command::new("networksetup").args(&args).output();
                all &= out.map(|o| o.status.success()).unwrap_or(false);
            }
            if all {
                ok += 1;
            }
        }
        return Ok(format!("proxy disabled on {ok}/{} services", services.len()));
    }

    Err("clear-proxy: unsupported platform".into())
}

/// Enabled network services, excluding Tailscale (its own traffic must not go
/// through the proxy or the hub link itself would break).
fn mac_services() -> Vec<String> {
    let out = run("networksetup", &["-listallnetworkservices"]);
    out.lines()
        .skip(1)
        .map(|l| l.trim())
        .filter(|l| !l.is_empty() && !l.starts_with('*') && *l != "Tailscale")
        .map(|l| l.to_string())
        .collect()
}

fn restart_service(name: &str) -> Result<String, String> {
    if !RESTARTABLE.contains(&name) {
        return Err(format!("not whitelisted: {name}"));
    }

    if cfg!(windows) {
        let _ = no_window_cmd("schtasks").args(["/End", "/TN", name]).output();
        sleep(Duration::from_millis(1500));
        let out = no_window_cmd("schtasks").args(["/Run", "/TN", name]).output().map_err(|e| e.to_string())?;
        return if out.status.success() {
            Ok(format!("restarted {name}"))
        } else {
            Err(String::from_utf8_lossy(&out.stderr).to_string())
        };
    }

    if cfg!(target_os = "macos") {
        let label = match name {
            "VibelinkAgent" | "vibelink-agent" => "ai.vibelink.agent",
            "VibelinkHub" | "vibelink-hub" => "ai.vibelink.hub",
            other => return Err(format!("no launchd label for {other}")),
        };
        let uid = run("id", &["-u"]).trim().to_string();
        let out = Command::new("launchctl")
            .args(["kickstart", "-k", &format!("gui/{uid}/{label}")])
            .output()
            .map_err(|e| e.to_string())?;
        return if out.status.success() {
            Ok(format!("restarted {label}"))
        } else {
            Err(String::from_utf8_lossy(&out.stderr).to_string())
        };
    }

    Err("restart-service: unsupported platform".into())
}

// ---------------- session plane ----------------

static LOG_READY: std::sync::Once = std::sync::Once::new();

fn log_path() -> std::path::PathBuf {
    let data = data_dir();
    data.parent().map(|p| p.join("agent.log")).unwrap_or_else(|| data.join("agent.log"))
}

/// 自己写日志：不靠 cmd 的 `>>` 重定向，避免子进程（opencode）继承句柄把日志锁死，
/// 锁死过一次就再也起不来（Windows 上无法追加到被占用的文件）。
fn log_line(args: std::fmt::Arguments) {
    let line = format!("{args}");
    println!("{line}");
    LOG_READY.call_once(|| {
        let p = log_path();
        if let Some(dir) = p.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        if let Ok(m) = std::fs::metadata(&p) {
            if m.len() > 4 * 1024 * 1024 {
                let _ = std::fs::rename(&p, p.with_extension("log.1"));
            }
        }
    });
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(log_path()) {
        let _ = writeln!(f, "[{}] {line}", now_ms());
    }
}

macro_rules! log {
    ($($arg:tt)*) => { log_line(format_args!($($arg)*)) };
}

fn data_dir() -> std::path::PathBuf {
    if let Ok(d) = std::env::var("VIBELINK_DATA") {
        return std::path::PathBuf::from(d);
    }
    if cfg!(windows) {
        let base = std::env::var("LOCALAPPDATA").unwrap_or_else(|_| ".".into());
        std::path::PathBuf::from(base).join("vibelink-mesh").join("data")
    } else {
        let home = std::env::var("HOME").unwrap_or_else(|_| ".".into());
        std::path::PathBuf::from(home)
            .join("Library")
            .join("Application Support")
            .join("vibelink-mesh")
            .join("data")
    }
}

/// 把各来源的会话拼成 agent → sessions 的结构，给手机端做「先进 agent 再进会话」。
fn build_sessions(open: Result<serde_json::Value, String>, active: Option<serde_json::Value>) -> serde_json::Value {
    let mut out: Vec<serde_json::Value> = Vec::new();
    // /api/session/active 回 {"data":{"ses_x":{"type":"running"}}}，key 就是正在跑的会话 id
    let active_ids: std::collections::HashSet<String> = active
        .as_ref()
        .and_then(|v| v.get("data"))
        .and_then(|d| d.as_object())
        .map(|o| o.keys().cloned().collect())
        .unwrap_or_default();

    let (sessions, note) = match open {
        Ok(v) => {
            let arr = v.get("data").and_then(|d| d.as_array()).cloned().unwrap_or_default();
            let mapped: Vec<serde_json::Value> = arr
                .iter()
                .map(|s| {
                    let id = s.get("id").and_then(|x| x.as_str()).unwrap_or("");
                    let model = match (
                        s.pointer("/model/providerID").and_then(|x| x.as_str()),
                        s.pointer("/model/id").and_then(|x| x.as_str()),
                    ) {
                        (Some(p), Some(m)) => format!("{p}/{m}"),
                        (None, Some(m)) => m.to_string(),
                        _ => String::new(),
                    };
                    serde_json::json!({
                        "id": id,
                        "title": s.get("title").and_then(|x| x.as_str()).unwrap_or(""),
                        "cwd": s.pointer("/location/directory").and_then(|x| x.as_str()).unwrap_or(""),
                        "updated": s.pointer("/time/updated").and_then(|x| x.as_u64()).unwrap_or(0),
                        "model": model,
                        "running": active_ids.contains(id),
                    })
                })
                .collect();
            let mut mapped = mapped;
            mapped.truncate(300);
            (mapped, serde_json::Value::Null)
        }
        Err(e) => (Vec::new(), serde_json::Value::String(e)),
    };
    let mut oc = serde_json::json!({ "id": "opencode", "name": "opencode", "sessions": sessions });
    if !note.is_null() {
        oc["note"] = note;
    }
    out.push(oc);

    out.push(serde_json::json!({
        "id": "codex",
        "name": "Codex",
        "sessions": agents::codex_sessions(),
    }));
    out.push(serde_json::json!({
        "id": "claude",
        "name": "Claude Code",
        "sessions": agents::claude_sessions(),
    }));
    out.push(agents::antigravity());

    serde_json::json!({ "agents": out })
}
fn to_json(s: String) -> Result<serde_json::Value, String> {
    serde_json::from_str(&s).map_err(|e| format!("bad json from opencode: {e}"))
}

fn is_beta_worker() -> bool {
    std::env::args().any(|a| a == "--beta-worker")
}

fn handle_session_request(kind: &str, payload: &serde_json::Value, data: &std::path::Path) -> Result<serde_json::Value, String> {
    if kind.starts_with("codex.runtime.") && payload.get("codexRuntime").is_none() {return Err("Runtime binding required".into());}
    let normalized=kind.strip_prefix("codex.runtime.").map(|name|format!("codex.{name}"));
    let kind=normalized.as_deref().unwrap_or(kind);
    let runtime = codex_runtime::Runtime::parse(payload)?;
    if is_beta_worker() && (!kind.starts_with("codex.") || !runtime.beta || runtime.model != codex_runtime::DEEPSEEK) {
        return Err("Public worker requires an isolated DeepSeek Codex binding".into());
    }
    codex_runtime::with(runtime, || handle_scoped_session_request(kind,payload,data))
}

fn handle_scoped_session_request(kind: &str, payload: &serde_json::Value, data: &std::path::Path) -> Result<serde_json::Value, String> {
    let sid = payload.get("session").and_then(|x| x.as_str()).unwrap_or("").to_string();
    let text = payload.get("text").and_then(|x| x.as_str()).unwrap_or("");
    let cwd = payload.get("cwd").and_then(|x| x.as_str()).unwrap_or("");
    let cursor = payload.get("cursor").and_then(|x| x.as_str()).unwrap_or("");
    let open = |f: &dyn Fn(&opencode::Client) -> Result<String, String>| {
        let c = opencode::Client::ensure(data)?;
        f(&c)
    };
    if kind.starts_with("antigravity.") || kind.starts_with("claude.") || kind.starts_with("opencode.") {
        return Err("Runtime disabled: use official Codex CLI or DeepSeek API".into());
    }
    match kind {
        "sessions.list" | "codex.list" => Ok(serde_json::json!({"agents":[{"id":"codex","name":"Codex","sessions":agents::codex_sessions()}]})),
        // hub 的委派任务用它判断一轮是否跑完：{ running: bool, known: bool }
        "session.status" => {
            let agent = payload.get("agent").and_then(|x| x.as_str()).unwrap_or("");
            if agent == "opencode" {
                let active = open(&|c| c.active()).and_then(to_json)?;
                let running = active.as_object().map(|o| o.contains_key(&sid)).unwrap_or(false)
                    || active.as_array().map(|a| a.iter().any(|v| v.as_str() == Some(sid.as_str()))).unwrap_or(false);
                Ok(serde_json::json!({ "running": running, "known": true }))
            } else {
                let live = agents::live_running(&sid);
                Ok(serde_json::json!({ "running": live.unwrap_or(false), "known": live.is_some() }))
            }
        }
        "codex.messages" => agents::codex_messages(&sid, cursor),
        "codex.prompt" => {
            let runtime = codex_runtime::current();
            let isolated_cwd = if runtime.beta { runtime.prepare()?; runtime.workspace().to_string_lossy().to_string() } else { cwd.to_string() };
            let (eff_cwd, eff_text) = if runtime.beta { (isolated_cwd, if text.is_empty(){String::new()}else{format!("{}\n\n{}",payload.pointer("/botContext/systemPrompt").and_then(serde_json::Value::as_str).unwrap_or(""),text)}) } else { agents::prepare_session_request(&isolated_cwd, text, payload) };
            agents::codex_prompt(&sid, &eff_text, if eff_cwd.is_empty() { None } else { Some(eff_cwd.as_str()) }, payload.get("files"))
        }
        "codex.interrupt" => agents::codex_interrupt(&sid),
        "codex.create" => {
            let runtime = codex_runtime::current();
            let isolated_cwd = if runtime.beta { runtime.prepare()?; runtime.workspace().to_string_lossy().to_string() } else { cwd.to_string() };
            let (eff_cwd, eff_text) = if runtime.beta { (isolated_cwd, if text.is_empty(){String::new()}else{format!("{}\n\n{}",payload.pointer("/botContext/systemPrompt").and_then(serde_json::Value::as_str).unwrap_or(""),text)}) } else { agents::prepare_session_request(&isolated_cwd, text, payload) };
            agents::codex_create(&eff_cwd, &eff_text, payload.get("files"))
        }
        "claude.messages" => agents::claude_messages(&sid, cursor),
        "claude.prompt" => {
            let (eff_cwd, eff_text) = agents::prepare_session_request(cwd, text, payload);
            agents::claude_prompt(&sid, &eff_text, if eff_cwd.is_empty() { None } else { Some(eff_cwd.as_str()) }, payload.get("files"))
        }
        "claude.interrupt" => agents::claude_interrupt(&sid),
        "claude.create" => {
            let (eff_cwd, eff_text) = agents::prepare_session_request(cwd, text, payload);
            agents::claude_create(&eff_cwd, &eff_text, payload.get("files"))
        }
        "antigravity.messages" => agents::antigravity_messages(&sid, cursor),
        "antigravity.prompt" => {
            let (eff_cwd, eff_text) = agents::prepare_session_request(cwd, text, payload);
            agents::antigravity_prompt(&sid, &eff_text, if eff_cwd.is_empty() { None } else { Some(eff_cwd.as_str()) }, payload.get("files"))
        }
        "antigravity.interrupt" => agents::antigravity_interrupt(&sid),
        "antigravity.create" => {
            let (eff_cwd, eff_text) = agents::prepare_session_request(cwd, text, payload);
            agents::antigravity_create(&eff_cwd, &eff_text, payload.get("files"))
        }
        "desktop.frame" => {
            let w = payload.get("width").and_then(|x| x.as_u64()).unwrap_or(1280) as u32;
            let last_hash = payload.get("lastHash").and_then(|x| x.as_str());
            agents::desktop_frame_with_hash(w, last_hash)
        }
        "desktop.input" => agents::desktop_input(payload),
        "desktop.lease" => Ok(agents::desktop_lease(payload)),
        "terminal.list" => Ok(pty_and_worktree::terminal_list()),
        "terminal.action" => pty_and_worktree::terminal_action(payload),
        "worktree.list" => {
            let repo = payload.get("repoPath").or_else(|| payload.get("cwd")).and_then(|x| x.as_str());
            Ok(pty_and_worktree::worktree_list(repo))
        }
        "worktree.action" => pty_and_worktree::worktree_action(payload),
        "scheduler.list" => Ok(agents::scheduler_list()),
        "scheduler.action" => agents::scheduler_action(payload),
        "plugins.list" => Ok(agents::plugins_list()),
        "plugins.action" => agents::plugins_action(payload),
        "workspace.tree" => {
            let depth = payload.get("depth").and_then(|x| x.as_u64()).unwrap_or(2) as usize;
            agents::workspace_tree(cwd, depth)
        }
        "workspace.read" => {
            let fpath = payload.get("path").and_then(|x| x.as_str()).unwrap_or("");
            agents::workspace_read(fpath)
        }
        "workspace.upload" => {
            let into_sub = payload.get("subdir").and_then(|x| x.as_bool()).unwrap_or(false);
            agents::workspace_upload(cwd, payload.get("files"), into_sub)
        }
        "workspace.git" => agents::workspace_git(cwd),
        "opencode.list" => open(&|c| c.list()).and_then(to_json),
        "opencode.messages" => {
            let cursor = payload.get("cursor").and_then(|x| x.as_str()).unwrap_or("");
            open(&|c| c.messages(&sid, if cursor.is_empty() { None } else { Some(cursor) })).and_then(to_json)
        }
        "opencode.prompt" => {
            let (eff_cwd, eff_text) = agents::prepare_session_request(cwd, text, payload);
            let files = payload.get("files").cloned();
            let saved = agents::save_uploaded_files(&eff_cwd, files.as_ref(), true);
            let mut text2 = if eff_text.trim().is_empty() && !saved.is_empty() {
                "请查看并处理我上传的文件。".to_string()
            } else {
                eff_text
            };
            if !saved.is_empty() {
                let paths: Vec<String> = saved
                    .iter()
                    .map(|(p, name, mime, _)| format!("- {name} ({mime}): `{p}`"))
                    .collect();
                text2 = format!("{text2}\n\n[Uploaded Workspace Files]:\n{}", paths.join("\n"));
            }
            let sid2 = sid.clone();
            let data2 = data.to_path_buf();
            let inline_files = files.filter(|f| f.to_string().len() < 16_000);
            // 后台发给 opencode，立刻返回给手机，避免阻塞
            thread::spawn(move || {
                if let Ok(c) = opencode::Client::ensure(&data2) {
                    let _ = c.prompt(&sid2, &text2, inline_files.as_ref());
                }
            });
            Ok(serde_json::json!({ "ok": true, "id": sid }))
        }
        "opencode.interrupt" => open(&|c| c.interrupt(&sid)).and_then(to_json),
        "opencode.create" => {
            let (eff_cwd, eff_text) = agents::prepare_session_request(cwd, text, payload);
            let title = if text.trim().is_empty() {
                "新 opencode 会话".to_string()
            } else {
                agents::trim_title(text, 60)
            };
            // 和其他引擎一样夹在工作区白名单里
            let cwd_c = crate::roots::clamp_cwd(&eff_cwd, "");
            let cwd = cwd_c.as_str();
            let raw = open(&|c| c.create(cwd, &title)).and_then(to_json)?;
            let new_id = raw
                .pointer("/data/id")
                .or_else(|| raw.get("id"))
                .and_then(|x| x.as_str())
                .unwrap_or("")
                .to_string();
            if new_id.is_empty() {
                return Err(format!("opencode.create returned no id: {raw}"));
            }
            let dir = raw
                .pointer("/data/location/directory")
                .and_then(|x| x.as_str())
                .unwrap_or(cwd)
                .to_string();
            if !eff_text.trim().is_empty() {
                let id_for_prompt = new_id.clone();
                let text_for_prompt = eff_text;
                let data2 = data.to_path_buf();
                thread::spawn(move || {
                    if let Ok(c) = opencode::Client::ensure(&data2) {
                        let _ = c.prompt(&id_for_prompt, &text_for_prompt, None);
                    }
                });
            }
            Ok(serde_json::json!({
                "id": new_id,
                "agent": "opencode",
                "cwd": dir,
                "title": title,
                "running": !text.trim().is_empty(),
            }))
        }
        other => Err(format!("unknown kind {other}")),
    }
}

fn session_worker(base: String, token: String, device: String) {
    let Ok(ep) = parse_endpoint(&base) else { return };
    let data = data_dir();
    loop {
        let path = format!("/api/agent/session-requests?device={}", urlencode(&device));
        if let Ok((200, body)) = http_call(&ep, "GET", &path, &token, None) {
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(&body) {
                if let Some(list) = v.get("requests").and_then(|r| r.as_array()) {
                    for req in list.clone() {
                        let base2 = base.clone();
                        let token2 = token.clone();
                        let data2 = data.clone();
                        thread::spawn(move || {
                            let Ok(ep2) = parse_endpoint(&base2) else { return };
                            let id = req.get("id").and_then(|x| x.as_str()).unwrap_or("").to_string();
                            let kind = req.get("kind").and_then(|x| x.as_str()).unwrap_or("").to_string();
                            let payload = req.get("payload").cloned().unwrap_or(serde_json::json!({}));
                            let res = handle_session_request(&kind, &payload, &data2);
                            let result = match res {
                                Ok(v) => serde_json::json!({ "id": id, "ok": true, "payload": v }),
                                Err(e) => serde_json::json!({ "id": id, "ok": false, "error": e }),
                            };
                            let _ = http_call(&ep2, "POST", "/api/agent/session-result", &token2, Some(&result.to_string()));
                        });
                    }
                }
            }
        }
        sleep(Duration::from_millis(400));
    }
}
// ---------------- self update ----------------

fn target_id() -> String {
    format!("{}-{}", std::env::consts::OS, std::env::consts::ARCH)
}

/// 和 http_call 一样，但正文按字节收（下载二进制用）。
fn http_get_bytes(ep: &Endpoint, path: &str, token: &str) -> Result<(u16, Vec<u8>), String> {
    let addr: SocketAddr = (ep.host.as_str(), ep.port)
        .to_socket_addrs()
        .map_err(|e| e.to_string())?
        .next()
        .ok_or("resolve failed")?;
    let mut stream = TcpStream::connect_timeout(&addr, Duration::from_secs(5)).map_err(|e| e.to_string())?;
    stream.set_read_timeout(Some(Duration::from_secs(90))).ok();
    let req = format!(
        "GET {path} HTTP/1.1\r\nHost: {}:{}\r\nx-mesh-token: {token}\r\nconnection: close\r\n\r\n",
        ep.host, ep.port
    );
    stream.write_all(req.as_bytes()).map_err(|e| e.to_string())?;
    let mut raw = Vec::new();
    stream.read_to_end(&mut raw).map_err(|e| e.to_string())?;
    let pos = raw
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .ok_or("malformed response")?;
    let head = String::from_utf8_lossy(&raw[..pos]).to_string();
    let status: u16 = head
        .lines()
        .next()
        .and_then(|l| l.split_whitespace().nth(1))
        .and_then(|s| s.parse().ok())
        .ok_or("bad status line")?;
    let body = raw[pos + 4..].to_vec();
    Ok((status, body))
}

fn self_update_check(ep: &Endpoint, token: &str) {
    let path = format!("/api/agent/latest?target={}", target_id());
    let Ok((200, body)) = http_call(ep, "GET", &path, token, None) else {
        return;
    };
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&body) else {
        return;
    };
    let version = v.get("version").and_then(|x| x.as_str()).unwrap_or("");
    if version.is_empty() || version <= AGENT_VERSION {
        return;
    }
    let file = v.get("path").and_then(|x| x.as_str()).unwrap_or("");
    let want = v.get("size").and_then(|x| x.as_u64()).unwrap_or(0);
    if file.is_empty() {
        return;
    }
    let Ok((code, bytes)) = http_get_bytes(ep, &format!("{file}?t={token}"), token) else {
        return;
    };
    if code != 200 || bytes.len() < 4096 {
        return;
    }
    if want > 0 && bytes.len() as u64 != want {
        log!("[agent] update: size mismatch {} != {want}", bytes.len());
        return;
    }
    // 魔数不对就别动自己，宁可不升级也别把机器搞砖
    let magic_ok = if cfg!(windows) {
        bytes.starts_with(b"MZ")
    } else {
        bytes.starts_with(b"\x7fELF")
            || bytes.starts_with(&[0xcf, 0xfa, 0xed, 0xfe])
            || bytes.starts_with(&[0xca, 0xfe, 0xba, 0xbe])
    };
    if !magic_ok {
        log!("[agent] update: downloaded file is not an executable");
        return;
    }
    let Ok(exe) = std::env::current_exe() else { return };
    let name = exe
        .file_name()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "vibelink-agent".into());
    let new = exe.with_file_name(format!("{name}.new"));
    if std::fs::write(&new, &bytes).is_err() {
        return;
    }
    match apply_update(&exe, &new) {
        Ok(()) => {
            log!("[agent] updating {AGENT_VERSION} -> {version}, restarting");
            std::process::exit(0);
        }
        Err(e) => {
            log!("[agent] update failed: {e}");
            let _ = std::fs::remove_file(&new);
        }
    }
}

/// 换二进制要等自己退出（Windows 上运行中的 exe 是锁着的），
/// 所以交给一个后台小脚本：等我死、替换、重启。
fn apply_update(exe: &std::path::Path, new: &std::path::Path) -> Result<(), String> {
    let pid = std::process::id();
    if cfg!(windows) {
        let script = std::env::temp_dir().join("vibelink-self-update.cmd");
        let body = format!(
            "@echo off\r\n\
set \"EXE={exe}\"\r\n\
set \"NEW={new}\"\r\n\
:wait\r\n\
tasklist /FI \"PID eq {pid}\" 2>nul | find \"{pid}\" >nul\r\n\
if not errorlevel 1 (\r\n\
  timeout /t 1 /nobreak >nul\r\n\
  goto wait\r\n\
)\r\n\
copy /y \"%EXE%\" \"%EXE%.bak\" >nul\r\n\
move /y \"%NEW%\" \"%EXE%\" >nul\r\n\
schtasks /run /tn VibelinkAgent >nul 2>&1\r\n\
if not errorlevel 1 goto done\r\n\
timeout /t 2 /nobreak >nul\r\n\
schtasks /run /tn VibelinkAgent >nul 2>&1\r\n\
if not errorlevel 1 goto done\r\n\
timeout /t 2 /nobreak >nul\r\n\
schtasks /run /tn VibelinkAgent >nul 2>&1\r\n\
if not errorlevel 1 goto done\r\n\
start /b \"\" \"%EXE%\"\r\n\
:done\r\n\
del \"%~f0\" & exit /b 0\r\n",
            exe = exe.display(),
            new = new.display(),
            pid = pid
        );
        std::fs::write(&script, body).map_err(|e| e.to_string())?;
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            Command::new("cmd")
                .args(["/c", &script.to_string_lossy()])
                .creation_flags(0x0800_0000)
                .spawn()
                .map_err(|e| e.to_string())?;
        }
        Ok(())
    } else {
        let script = format!(
            "while kill -0 {pid} 2>/dev/null; do sleep 1; done; \
cp -f '{exe}' '{exe}.bak'; mv -f '{new}' '{exe}'; chmod +x '{exe}'; \
if [ -x \"$HOME/bin/vibelink-agent-start.sh\" ]; then \
  nohup \"$HOME/bin/vibelink-agent-start.sh\" >/dev/null 2>&1 & \
else nohup '{exe}' >>\"$HOME/Library/Logs/vibelink-agent.out.log\" 2>&1 & fi",
            pid = pid,
            exe = exe.display(),
            new = new.display()
        );
        Command::new("sh")
            .arg("-c")
            .arg(script)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|e| e.to_string())?;
        Ok(())
    }
}

fn update_worker(base: String, token: String) {
    // 起来先喘 20 秒，别和首轮采集抢网络
    std::thread::sleep(Duration::from_secs(20));
    loop {
        if let Ok(ep) = parse_endpoint(&base) {
            self_update_check(&ep, &token);
        }
        sleep(Duration::from_secs(30 * 60));
    }
}

// ---------------- main loop ----------------
fn ensure_single_instance() {
    #[cfg(windows)]
    unsafe {
        #[link(name = "kernel32")]
        unsafe extern "system" {
            fn CreateMutexW(
                lp_mutex_attributes: *const std::ffi::c_void,
                b_initial_owner: i32,
                lp_name: *const u16,
            ) -> isize;
            fn GetLastError() -> u32;
        }
        let singleton = if is_beta_worker() {
            "Local\\VibelinkAgentBetaSingleton\0"
        } else {
            "Local\\VibelinkAgentSingleton\0"
        };
        let name: Vec<u16> = singleton.encode_utf16().collect();
        let h = CreateMutexW(std::ptr::null(), 1, name.as_ptr());
        if h != 0 && GetLastError() == 183 {
            // ERROR_ALREADY_EXISTS = 183：已有实例在运行，直接退出避免双实例并发轮询与刷屏
            std::process::exit(0);
        }
    }
}

fn main() {
    ensure_single_instance();
    let base = std::env::var("VIBELINK_HUB").unwrap_or_else(|_| "http://127.0.0.1:18995".into());
    let token = std::env::var("VIBELINK_TOKEN").unwrap_or_default();
    let device = std::env::var("VIBELINK_DEVICE").unwrap_or_else(|_| {
        if base.contains("127.0.0.1") || base.contains("localhost") {
            "pc".into()
        } else {
            hostname().to_lowercase()
        }
    });
    if token.is_empty() {
        eprintln!("[agent] VIBELINK_TOKEN is required");
        std::process::exit(1);
    }
    let ep = parse_endpoint(&base).unwrap_or_else(|e| {
        eprintln!("[agent] bad VIBELINK_HUB: {e}");
        std::process::exit(1);
    });
    log!("[agent] {device} -> {base}");
    {
        let base2 = base.clone();
        let token2 = token.clone();
        let device2 = device.clone();
        thread::spawn(move || session_worker(base2, token2, device2));
    }
    {
        let base3 = base.clone();
        let token3 = token.clone();
        if !is_beta_worker() { thread::spawn(move || update_worker(base3, token3)); }
    }
    // 定时任务按时自动执行（以前只能手动「立即执行」）
    if !is_beta_worker() { thread::spawn(schedule::worker); }

    let mut last_slow = Instant::now() - SLOW;
    let mut cached_gw: Option<String> = None;
    let mut cached_proxy = ProxyState::default();
    let mut cached_ports: BTreeMap<String, bool> = BTreeMap::new();
    let mut cached_latency: Option<Latency> = None;

    loop {
        if last_slow.elapsed() >= SLOW {
            last_slow = Instant::now();
            cached_gw = gateway();
            cached_proxy = system_proxy();
            cached_ports.clear();
            for p in [7890u16, 7897, 1082, 4096, 4097, 18995] {
                cached_ports.insert(p.to_string(), port_open(p));
            }
            cached_latency = probe(&cached_proxy);
        }

        let (mem, uptime) = meminfo();
        let opencode = if cached_ports.get("4096").copied().unwrap_or(false) {
            Some(4096)
        } else if cached_ports.get("4097").copied().unwrap_or(false) {
            Some(4097)
        } else {
            None
        };

        let report = DeviceReport {
            id: device.clone(),
            hostname: hostname(),
            platform: format!("{} {}", std::env::consts::OS, os_version()),
            arch: std::env::consts::ARCH.to_string(),
            agent_version: AGENT_VERSION.to_string(),
            ips: ips(),
            gateway: cached_gw.clone(),
            proxy: cached_proxy.clone(),
            ports: cached_ports.clone(),
            opencode,
            latency: cached_latency.clone(),
            mem,
            uptime_sec: uptime,
            note: None,
        };

        match serde_json::to_string(&report) {
            Ok(body) => {
                if let Err(e) = http_call(&ep, "POST", "/api/ingest", &token, Some(&body)) {
                    eprintln!("[agent] ingest: {e}");
                }
            }
            Err(e) => eprintln!("[agent] encode: {e}"),
        }

        // pull commands
        let q = format!("/api/commands?device={}&status=pending", urlencode(&device));
        if let Ok((200, body)) = http_call(&ep, "GET", &q, &token, None) {
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(&body) {
                if let Some(list) = v.get("commands").and_then(|c| c.as_array()) {
                    for cmd in list {
                        let id = cmd.get("id").and_then(|x| x.as_str()).unwrap_or("");
                        let action = cmd.get("action").and_then(|x| x.as_str()).unwrap_or("");
                        let args = cmd.get("args").cloned().unwrap_or(serde_json::json!({}));
                        let _ = http_call(&ep, "POST", &format!("/api/commands/{id}/claim"), &token, Some("{}"));

                        let result = match action {
                            "set-proxy" => set_proxy(args.get("server").and_then(|x| x.as_str()).unwrap_or("")),
                            "clear-proxy" => clear_proxy(),
                            "restart-service" => restart_service(args.get("name").and_then(|x| x.as_str()).unwrap_or("")),
                            "probe" => {
                                last_slow = Instant::now() - SLOW;
                                Ok("probe queued".to_string())
                            }
                            other => Err(format!("unknown action: {other}")),
                        };
                        let ok = result.is_ok();
                        let payload = serde_json::json!({
                            "id": id,
                            "ok": ok,
                            "result": match result { Ok(m) => serde_json::json!({"ok": true, "message": m}), Err(e) => serde_json::json!({"ok": false, "error": e}) }
                        });
                        let _ = http_call(&ep, "POST", "/api/commands/result", &token, Some(&payload.to_string()));
                    }
                }
            }
        }

        sleep(PUSH);
    }
}

fn hostname() -> String {
    static HOST: OnceLock<String> = OnceLock::new();
    HOST.get_or_init(|| {
        std::env::var("COMPUTERNAME")
            .or_else(|_| std::env::var("HOSTNAME"))
            .unwrap_or_else(|_| run("hostname", &[]).trim().to_string())
    })
    .clone()
}

fn os_version() -> String {
    static VER: OnceLock<String> = OnceLock::new();
    VER.get_or_init(|| {
        if cfg!(windows) {
            run("cmd", &["/c", "ver"]).trim().to_string()
        } else {
            run("uname", &["-r"]).trim().to_string()
        }
    })
    .clone()
}

fn urlencode(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => (b as char).to_string(),
            other => format!("%{other:02X}"),
        })
        .collect()
}

#[allow(dead_code)]
fn _now(_: u64) {
    let _ = now_ms();
}
















#[cfg(test)]
mod gateway_smoke;

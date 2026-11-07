//! opencode 客户端：**不再自己起 server**，统一走 `opencode api` CLI。
//!
//! 为什么改：以前 agent 会 spawn 一个自己的 `opencode serve`（4098），和用户正在用的
//! 后台服务（`opencode service`，端口随机）并存，两份 server 共用一个会话库，
//! 重启后 TUI 会出现「Session location unavailable」、手机端发的消息也可能进错实例。
//!
//! `opencode api` 会自动连到当前后台服务（`opencode service status` 那个地址，
//! 也带鉴权），所以 TUI 和 vibelink 从此共用同一个 server。
//! 代价是每次调用要起一个 CLI 进程（约几百毫秒），对手机端的使用频率完全够用。
use std::path::PathBuf;
use std::process::Command;

pub struct Client;

/// percent-encode：cursor 是 base64 JSON，`+ / =` 必须转义后再进 query。
fn enc(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => out.push(b as char),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

fn find_exe() -> Option<PathBuf> {
    let mut candidates: Vec<String> = Vec::new();
    if cfg!(windows) {
        let home = std::env::var("USERPROFILE").unwrap_or_default();
        candidates.push(format!(
            r"{home}\AppData\Roaming\npm\node_modules\@opencode\cli\bin\opencode.exe"
        ));
        candidates.push(format!(r"{home}\AppData\Local\Programs\opencode\opencode.exe"));
    } else {
        let home = std::env::var("HOME").unwrap_or_default();
        candidates.push(format!("{home}/.opencode/bin/opencode"));
        candidates.push("/opt/homebrew/bin/opencode".to_string());
        candidates.push("/usr/local/bin/opencode".to_string());
        candidates.push(format!("{home}/.local/bin/opencode"));
        candidates.push(format!("{home}/.bun/bin/opencode"));
    }
    for c in candidates {
        let p = PathBuf::from(&c);
        if p.exists() {
            return Some(p);
        }
    }
    let path = std::env::var("PATH").unwrap_or_default();
    for dir in std::env::split_paths(&path) {
        #[cfg(windows)]
        {
            let exe = dir.join("opencode.exe");
            if exe.is_file() {
                return Some(exe);
            }
        }
        #[cfg(not(windows))]
        {
            let exe = dir.join("opencode");
            if exe.is_file() {
                return Some(exe);
            }
        }
    }
    None
}

impl Client {
    /// 只检查 CLI 在不在；不再启动任何 server。
    pub fn ensure(_data: &std::path::Path) -> Result<Client, String> {
        if find_exe().is_none() {
            return Err("opencode.exe not found".into());
        }
        Ok(Client)
    }

    fn api(&self, method: &str, path: &str, body: Option<&str>) -> Result<String, String> {
        self.api_in_dir(method, path, body, None)
    }

    fn api_in_dir(&self, method: &str, path: &str, body: Option<&str>, cwd: Option<&str>) -> Result<String, String> {
        let exe = find_exe().ok_or("opencode.exe not found")?;
        let mut cmd = Command::new(exe);
        cmd.args(["api", method, path]);
        if let Some(b) = body {
            cmd.args(["--data", b]);
        }
        if let Some(dir) = cwd.filter(|d| !d.trim().is_empty()) {
            let p = std::path::Path::new(dir);
            if p.is_dir() {
                cmd.current_dir(p);
            }
        }
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
        }
        let out = cmd.output().map_err(|e| e.to_string())?;
        if !out.status.success() {
            let err = String::from_utf8_lossy(&out.stderr).trim().to_string();
            let stdout = String::from_utf8_lossy(&out.stdout).trim().to_string();
            return Err(if err.is_empty() { stdout } else { err });
        }
        Ok(String::from_utf8_lossy(&out.stdout).to_string())
    }

    pub fn create(&self, cwd: &str, title: &str) -> Result<String, String> {
        let mut body = serde_json::json!({});
        if !title.trim().is_empty() {
            body["title"] = serde_json::Value::String(title.trim().to_string());
        }
        if !cwd.trim().is_empty() {
            body["location"] = serde_json::json!({ "directory": cwd.trim() });
        }
        self.api_in_dir("POST", "/api/session", Some(&body.to_string()), Some(cwd))
    }

    pub fn list(&self) -> Result<String, String> {
        self.api("GET", "/api/session", None)
    }

    /// 正在跑的会话：`{"data":{"ses_x":{"type":"running"}}}`，key 就是 session id。
    pub fn active(&self) -> Result<String, String> {
        self.api("GET", "/api/session/active", None)
    }

    pub fn messages(&self, session: &str, cursor: Option<&str>) -> Result<String, String> {
        // 第一页取最新 120 条（order=desc）；翻页时带上 cursor，且不能再带 order。
        let path = match cursor.filter(|c| !c.is_empty()) {
            Some(c) => format!("/api/session/{session}/message?limit=120&cursor={}", enc(c)),
            None => format!("/api/session/{session}/message?limit=120&order=desc"),
        };
        self.api("GET", &path, None)
    }

    pub fn prompt(&self, session: &str, text: &str, files: Option<&serde_json::Value>) -> Result<String, String> {
        let mut body = serde_json::json!({ "text": text });
        if let Some(f) = files {
            body["files"] = f.clone();
        }
        self.api("POST", &format!("/api/session/{session}/prompt"), Some(&body.to_string()))
    }

    pub fn interrupt(&self, session: &str) -> Result<String, String> {
        self.api("POST", &format!("/api/session/{session}/interrupt"), Some("{}"))
    }
}

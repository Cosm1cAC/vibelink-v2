//! 多 agent 会话发现。
//!
//! 现在支持三种来源：
//!   - opencode：走本地 `opencode serve` 的 HTTP API（由 opencode.rs 负责）
//!   - Codex    ：`~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl`（首行 session_meta + 消息行）
//!   - Claude   ：`~/.claude/projects/<slug>/<uuid>.jsonl`
//! Antigravity 是 VS Code 分支，会话在 state.vscdb 里；本机还没产生过会话时返回空并带说明。
use serde_json::{json, Value};
use std::fs::{self, File};
use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;

fn home() -> PathBuf {
    let key = if cfg!(windows) { "USERPROFILE" } else { "HOME" };
    PathBuf::from(std::env::var(key).unwrap_or_default())
}

fn mtime_ms(p: &Path) -> u64 {
    fs::metadata(p)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Codex 会把 AGENTS.md / 系统提示 / 环境信息当成 user 消息塞进 rollout，这些不算对话。
fn is_noise(text: &str) -> bool {
    let s = text.trim_start();
    // 只过滤「纯注入」的几类；`# Files mentioned by the user` 这类只是标题，正文要留
    s.starts_with('<')
        || s.starts_with("You are Codex")
        || s.starts_with("# AGENTS.md")
        || s.starts_with("# Instructions")
}

/// 去掉消息开头的标题行（`# Files mentioned by the user:` 之类），保留正文。
fn strip_headers(text: &str) -> String {
    let mut started = false;
    let mut out: Vec<&str> = Vec::new();
    for line in text.lines() {
        if !started {
            let l = line.trim();
            if l.is_empty() || l.starts_with('#') {
                continue;
            }
            started = true;
        }
        out.push(line);
    }
    out.join("\n").trim().to_string()
}
fn trim_title(text: &str, max: usize) -> String {
    // 标题取「最长的那一行」：Codex/Claude 会在正文前塞一堆元信息（图片标记、instructions 链接），
    // 真正的提问通常是最长的一句。
    let line = text
        .lines()
        .map(str::trim)
        .filter(|l| {
            l.chars().count() >= 4
                && !l.starts_with('#')
                && !l.starts_with('<')
                && !l.starts_with("Image attachment")
                && !l.starts_with("Distinguish instructions")
        })
        .max_by_key(|l| l.chars().count())
        .unwrap_or("");
    let mut out = String::new();
    for ch in line.chars() {
        if out.chars().count() >= max {
            out.push('…');
            break;
        }
        out.push(ch);
    }
    out
}

/// 把 "会话目录里的第一条用户消息" 当标题；没有标题时退回目录名。
fn title_from_messages(path: &Path, role_key: &str) -> Option<String> {
    let f = File::open(path).ok()?;
    for line in BufReader::new(f).lines().take(200).flatten() {
        let Ok(v) = serde_json::from_str::<Value>(&line) else { continue };
        let text = v
            .get(role_key)
            .and_then(|p| p.get("content"))
            .and_then(|c| c.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|p| p.get("text").and_then(|t| t.as_str()))
                    .collect::<Vec<_>>()
                    .join(" ")
            })
            .unwrap_or_default();
        if !text.trim().is_empty() && !is_noise(&text) {
            let cleaned = trim_title(&text, 80);
            if !cleaned.is_empty() {
                return Some(cleaned);
            }
        }
    }
    None
}

// ---------------- Codex ----------------

fn codex_root() -> PathBuf {
    home().join(".codex").join("sessions")
}

fn walk_jsonl(dir: &Path, depth: usize, out: &mut Vec<PathBuf>) {
    if depth == 0 {
        return;
    }
    let Ok(rd) = fs::read_dir(dir) else { return };
    for e in rd.flatten() {
        let p = e.path();
        if p.is_dir() {
            walk_jsonl(&p, depth - 1, out);
        } else if p.extension().and_then(|x| x.to_str()) == Some("jsonl") {
            out.push(p);
        }
    }
}

fn codex_meta(path: &Path) -> Option<Value> {
    let f = File::open(path).ok()?;
    let line = BufReader::new(f).lines().next()?.ok()?;
    let v: Value = serde_json::from_str(&line).ok()?;
    if v.get("type").and_then(|x| x.as_str()) != Some("session_meta") {
        return None;
    }
    v.get("payload").cloned()
}

pub fn codex_sessions() -> Vec<Value> {
    let mut files = Vec::new();
    walk_jsonl(&codex_root(), 4, &mut files);
    let mut out = Vec::new();
    for path in files {
        let Some(meta) = codex_meta(&path) else { continue };
        let id = meta
            .get("session_id")
            .or_else(|| meta.get("id"))
            .and_then(|x| x.as_str())
            .unwrap_or("")
            .to_string();
        if id.is_empty() {
            continue;
        }
        let cwd = meta.get("cwd").and_then(|x| x.as_str()).unwrap_or("").to_string();
        let title = title_from_messages(&path, "payload")
            .filter(|t| !t.is_empty())
            .unwrap_or_else(|| {
                cwd.rsplit(['\\', '/']).find(|s| !s.is_empty()).unwrap_or("Codex 会话").to_string()
            });
        out.push(json!({
            "id": id,
            "title": title,
            "cwd": cwd,
            "updated": mtime_ms(&path),
        }));
    }
    out.sort_by(|a, b| {
        b.get("updated").and_then(|x| x.as_u64()).unwrap_or(0)
            .cmp(&a.get("updated").and_then(|x| x.as_u64()).unwrap_or(0))
    });
    out.truncate(300);
    out
}

fn find_codex_file(id: &str) -> Option<PathBuf> {
    let mut files = Vec::new();
    walk_jsonl(&codex_root(), 4, &mut files);
    for path in files {
        if let Some(meta) = codex_meta(&path) {
            let sid = meta
                .get("session_id")
                .or_else(|| meta.get("id"))
                .and_then(|x| x.as_str())
                .unwrap_or("");
            if sid == id {
                return Some(path);
            }
        }
    }
    None
}

fn content_text(payload: &Value) -> String {
    payload
        .get("content")
        .and_then(|c| c.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|p| p.get("text").and_then(|t| t.as_str()))
                .collect::<Vec<_>>()
                .join("\n")
        })
        .unwrap_or_default()
}

pub fn codex_messages(id: &str) -> Result<Value, String> {
    let path = find_codex_file(id).ok_or_else(|| format!("codex session {id} not found"))?;
    let f = File::open(&path).map_err(|e| e.to_string())?;
    let mut data = Vec::new();
    for line in BufReader::new(f).lines().flatten() {
        let Ok(v) = serde_json::from_str::<Value>(&line) else { continue };
        if v.get("type").and_then(|x| x.as_str()) != Some("response_item") {
            continue;
        }
        let Some(p) = v.get("payload") else { continue };
        if p.get("type").and_then(|x| x.as_str()) != Some("message") {
            continue;
        }
        let role = p.get("role").and_then(|x| x.as_str()).unwrap_or("");
        if role != "user" && role != "assistant" {
            continue;
        }
        let raw = content_text(p);
        if raw.trim().is_empty() || (role == "user" && is_noise(&raw)) {
            continue;
        }
        let text = if role == "user" { strip_headers(&raw) } else { raw };
        if text.trim().is_empty() {
            continue;
        }
        data.push(if role == "user" {
            json!({ "type": "user", "text": text, "time": { "created": 0 } })
        } else {
            json!({ "type": "assistant", "content": [{ "type": "text", "text": text }], "time": { "created": 0 } })
        });
    }
    Ok(json!({ "data": data }))
}

// ---------------- Claude Code ----------------

fn claude_root() -> PathBuf {
    home().join(".claude").join("projects")
}

pub fn claude_sessions() -> Vec<Value> {
    let mut out = Vec::new();
    let Ok(projects) = fs::read_dir(claude_root()) else { return out };
    for proj in projects.flatten() {
        let dir = proj.path();
        if !dir.is_dir() {
            continue;
        }
        let Ok(files) = fs::read_dir(&dir) else { continue };
        for f in files.flatten() {
            let path = f.path();
            if path.extension().and_then(|x| x.to_str()) != Some("jsonl") {
                continue;
            }
            let id = path.file_stem().and_then(|x| x.to_str()).unwrap_or("").to_string();
            if id.is_empty() {
                continue;
            }
            let title = title_from_messages(&path, "message")
                .unwrap_or_else(|| id.chars().take(8).collect());
            out.push(json!({
                "id": id,
                "title": title,
                "cwd": dir.file_name().and_then(|x| x.to_str()).unwrap_or(""),
                "updated": mtime_ms(&path),
            }));
        }
    }
    out.sort_by(|a, b| {
        b.get("updated").and_then(|x| x.as_u64()).unwrap_or(0)
            .cmp(&a.get("updated").and_then(|x| x.as_u64()).unwrap_or(0))
    });
    out.truncate(300);
    out
}

pub fn claude_messages(id: &str) -> Result<Value, String> {
    let mut found = None;
    let Ok(projects) = fs::read_dir(claude_root()) else {
        return Err("no claude projects".into());
    };
    'outer: for proj in projects.flatten() {
        let Ok(files) = fs::read_dir(proj.path()) else { continue };
        for f in files.flatten() {
            let p = f.path();
            if p.file_stem().and_then(|x| x.to_str()) == Some(id) {
                found = Some(p);
                break 'outer;
            }
        }
    }
    let path = found.ok_or_else(|| format!("claude session {id} not found"))?;
    let f = File::open(&path).map_err(|e| e.to_string())?;
    let mut data = Vec::new();
    for line in BufReader::new(f).lines().flatten() {
        let Ok(v) = serde_json::from_str::<Value>(&line) else { continue };
        let role = v.get("type").and_then(|x| x.as_str()).unwrap_or("");
        if role != "user" && role != "assistant" {
            continue;
        }
        let msg = v.get("message").cloned().unwrap_or(Value::Null);
        let text = content_text(&msg);
        if text.trim().is_empty() {
            continue;
        }
        data.push(if role == "user" {
            json!({ "type": "user", "text": text, "time": { "created": 0 } })
        } else {
            json!({ "type": "assistant", "content": [{ "type": "text", "text": text }], "time": { "created": 0 } })
        });
    }
    Ok(json!({ "data": data }))
}

// ---------------- Antigravity ----------------

/// VS Code 分支：会话在 `%APPDATA%\Antigravity\User\globalStorage\state.vscdb`（SQLite）。
/// 我们没带 SQLite 解析，先只报告“有没有装 / 有没有数据”，会话列表为空。
pub fn antigravity() -> Value {
    let dir = if cfg!(windows) {
        PathBuf::from(std::env::var("APPDATA").unwrap_or_default()).join("Antigravity")
    } else {
        home().join("Library/Application Support/Antigravity")
    };
    let installed = dir.is_dir();
    let db = dir.join("User").join("globalStorage").join("state.vscdb");
    let note = if !installed {
        "本机没有安装 Antigravity"
    } else if !db.is_file() {
        "Antigravity 已安装，但还没有产生会话记录"
    } else {
        "Antigravity 的会话存在 state.vscdb 里，暂未解析"
    };
    json!({
        "id": "antigravity",
        "name": "Antigravity",
        "note": note,
        "sessions": [],
    })
}








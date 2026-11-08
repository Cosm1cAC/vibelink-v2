//! 多 agent 会话发现与 Cindy 多引擎远程执行（新建会话 + 多引擎续聊 + 中断）。
//!
//! 支持来源：
//!   - opencode：走本地 `opencode api` CLI（由 opencode.rs 负责）
//!   - Codex    ：`~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl` + `codex exec` / `codex exec resume`
//!   - Claude   ：`~/.claude/projects/<slug>/<uuid>.jsonl` + `claude --session-id` / `claude --resume`
//!   - Antigravity：VS Code 分支，会话在 state.vscdb 里
use serde_json::{json, Value};
use std::collections::HashMap;
use std::fs::{self, File};
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::{mpsc, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

fn home() -> PathBuf {
    let key = if cfg!(windows) { "USERPROFILE" } else { "HOME" };
    PathBuf::from(std::env::var(key).unwrap_or_default())
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn mtime_ms(p: &Path) -> u64 {
    fs::metadata(p)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn default_cwd() -> String {
    let d_stack = PathBuf::from(r"D:\AI\VibeLink");
    if d_stack.is_dir() {
        return d_stack.to_string_lossy().to_string();
    }
    let candidate = home().join("Desktop").join("项目").join("VibeLink");
    if candidate.is_dir() {
        return candidate.to_string_lossy().to_string();
    }
    home().to_string_lossy().to_string()
}

/// 根据请求体中的 `botContext` / `botId` / `isolationMode` 自动解析 Per-Bot Git Worktree 目录、
/// 将 Bot 绑定的 Skills 落盘到 `~/.vibelink-cli/dev/codex/skills/<id>/SKILL.md`（对标 Rakazo `builtin-skills.ts`），
/// 并合成七层结构化 Bot 上下文提示词。
pub fn prepare_session_request(cwd: &str, text: &str, body: &Value) -> (String, String) {
    let bot_ctx = body.get("botContext");
    let bot_id = body
        .get("botId")
        .and_then(Value::as_str)
        .or_else(|| bot_ctx.and_then(|c| c.get("botId")).and_then(Value::as_str))
        .unwrap_or("")
        .trim();
    let isolation_mode = body
        .get("isolationMode")
        .and_then(Value::as_str)
        .or_else(|| bot_ctx.and_then(|c| c.get("isolationMode")).and_then(Value::as_str))
        .unwrap_or("shared")
        .trim();
    let base_cwd = resolve_cwd(cwd, None);
    let final_cwd = crate::pty_and_worktree::resolve_bot_cwd(&base_cwd, bot_id, isolation_mode);

    // 将该 Bot 绑定的专属技能剧本落盘至 `~/.vibelink-cli/dev/codex/skills/<id>/SKILL.md`
    if let Some(skills_arr) = bot_ctx.and_then(|c| c.get("skills")).and_then(Value::as_array) {
        let skills_root = crate::codex_runtime::current().home().join("skills");
        for sk in skills_arr {
            let sid = sk.get("id").and_then(Value::as_str).unwrap_or("").trim();
            let sname = sk.get("name").and_then(Value::as_str).unwrap_or("").trim();
            let sdesc = sk.get("description").and_then(Value::as_str).unwrap_or("").trim();
            let swhen = sk.get("whenToUse").and_then(Value::as_str).unwrap_or("").trim();
            let splay = sk.get("playbook").and_then(Value::as_str).unwrap_or("").trim();
            let safe_dir: String = sid
                .chars()
                .filter(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-')
                .collect();
            if !safe_dir.is_empty() && !splay.is_empty() {
                let dir = skills_root.join(&safe_dir);
                let _ = fs::create_dir_all(&dir);
                let skill_md = format!(
                    "---\nname: {sname}\ndescription: {sdesc}\n---\n\n# {sname}\n\n## When to use\n{swhen}\n\n## Playbook\n{splay}\n"
                );
                let _ = fs::write(dir.join("SKILL.md"), skill_md);
            }
        }
    }

    let mut final_text = text.to_string();
    if let Some(sys_prompt) = bot_ctx
        .and_then(|c| c.get("systemPrompt"))
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        if !final_text.contains("<bot_identity>") && !final_text.trim().is_empty() {
            final_text = format!("{sys_prompt}\n\n[User Request]\n{final_text}");
        }
    }
    (final_cwd, final_text)
}

/// 工作目录一律夹在白名单根目录里（见 `roots.rs`）；不在根目录里就退回默认目录。
fn resolve_cwd(cwd: &str, fallback: Option<&str>) -> String {
    let runtime=crate::codex_runtime::current();
    if runtime.beta {
        let root=runtime.workspace();let requested=crate::roots::normalize(Path::new(cwd));
        return if !cwd.is_empty() && crate::roots::within(&requested,&[root.clone()]) {requested.to_string_lossy().into()} else {root.to_string_lossy().into()};
    }
    let def = default_cwd();
    let fb = fallback.map(str::trim).filter(|s| !s.is_empty()).unwrap_or(def.as_str());
    crate::roots::clamp_cwd(cwd, fb)
}

fn new_uuid_v4() -> String {
    use std::sync::atomic::{AtomicU64, Ordering};
    static COUNTER: AtomicU64 = AtomicU64::new(1);
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0x1234_5678_9abc_def0);
    let seq = COUNTER.fetch_add(1, Ordering::Relaxed);
    let pid = std::process::id() as u64;
    let a = nanos.wrapping_mul(6364136223846793005).wrapping_add(seq);
    let b = (nanos ^ (pid << 32) ^ seq).wrapping_mul(1442695040888963407);
    let p1 = (a >> 32) as u32;
    let p2 = ((a >> 16) & 0xffff) as u16;
    let p3 = (((a & 0x0fff) as u16) | 0x4000) as u16;
    let p4 = ((((b >> 48) & 0x3fff) as u16) | 0x8000) as u16;
    let p5 = b & 0x0000_ffff_ffff_ffff;
    format!("{p1:08x}-{p2:04x}-{p3:04x}-{p4:04x}-{p5:012x}")
}

#[derive(Clone, Debug)]
struct LiveSession {
    runtime_scope: String,
    agent: String,
    id: String,
    title: String,
    cwd: String,
    updated: u64,
    started_ms: u64,
    pid: Option<u32>,
    base_msg_count: usize,
    user_text: String,
    assistant_text: String,
    reasoning_text: String,
    running: bool,
    error: Option<String>,
}

static LIVE_SESSIONS: OnceLock<Mutex<HashMap<String, LiveSession>>> = OnceLock::new();
static SESSION_ALIASES: OnceLock<Mutex<HashMap<String, String>>> = OnceLock::new();
static HEADLESS_CREATED_SESSIONS: OnceLock<Mutex<std::collections::HashSet<String>>> = OnceLock::new();

fn live_map() -> &'static Mutex<HashMap<String, LiveSession>> {
    LIVE_SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn alias_map() -> &'static Mutex<HashMap<String, String>> {
    SESSION_ALIASES.get_or_init(|| Mutex::new(HashMap::new()))
}

fn headless_set() -> &'static Mutex<std::collections::HashSet<String>> {
    HEADLESS_CREATED_SESSIONS.get_or_init(|| Mutex::new(std::collections::HashSet::new()))
}

fn mark_headless_created(id: &str) {
    if !id.trim().is_empty() {
        if let Ok(mut set) = headless_set().lock() {
            set.insert(id.trim().to_string());
        }
    }
}

fn is_headless_created(id: &str) -> bool {
    headless_set()
        .lock()
        .ok()
        .map(|s| s.contains(id))
        .unwrap_or(false)
}

fn register_session_alias(alias_id: &str, real_id: &str) {
    let a = alias_id.trim();
    let r = real_id.trim();
    if !a.is_empty() && !r.is_empty() && a != r {
        if let Ok(mut m) = alias_map().lock() {
            m.insert(a.to_string(), r.to_string());
        }
        if is_headless_created(a) {
            mark_headless_created(r);
        }
    }
}

pub fn resolve_session_id(id: &str) -> String {
    let trimmed = id.trim();
    if let Ok(m) = alias_map().lock() {
        if let Some(real) = m.get(trimmed) {
            return real.clone();
        }
    }
    trimmed.to_string()
}

/// 如果消息带有 `<bot_identity>...[User Request]\n` 包装，提取 `[User Request]\n` 之后的真实用户输入。
pub fn extract_clean_user_prompt(text: &str) -> String {
    let trimmed = text.trim();
    for marker in ["[User Request]\n", "[User Request]\r\n"] {
        if let Some(idx) = trimmed.rfind(marker) {
            let after = trimmed[idx + marker.len()..].trim();
            if !after.is_empty() {
                return after.to_string();
            }
        }
    }
    trimmed.to_string()
}

/// 这个会话是不是本 agent 拉起、且还在跑；没登记过返回 None。
pub fn live_running(id: &str) -> Option<bool> {
    let real_id = resolve_session_id(id);
    get_live(&real_id).or_else(|| get_live(id)).map(|l| l.running)
}

fn get_live(id: &str) -> Option<LiveSession> {
    let real_id = resolve_session_id(id);
    let guard = live_map().lock().ok()?;
    guard.get(&real_id).or_else(|| guard.get(id)).cloned()
}

fn kill_pid(pid: u32) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let _ = Command::new("taskkill")
            .args(["/F", "/T", "/PID", &pid.to_string()])
            .creation_flags(0x0800_0000)
            .output();
    }
    #[cfg(not(windows))]
    {
        let _ = Command::new("kill").args(["-9", &pid.to_string()]).output();
    }
}

/// Codex 会把 AGENTS.md / 系统提示 / 环境信息当成 user 消息塞进 rollout，这些不算对话。
fn is_noise(text: &str) -> bool {
    let cleaned = extract_clean_user_prompt(text);
    let s = cleaned.trim_start();
    s.starts_with('<')
        || s.starts_with("You are Codex")
        || s.starts_with("# AGENTS.md")
        || s.starts_with("# Instructions")
}

/// 去掉消息开头的标题行（`# Files mentioned by the user:` 之类），保留正文。
fn strip_headers(text: &str) -> String {
    let cleaned = extract_clean_user_prompt(text);
    let mut started = false;
    let mut out: Vec<&str> = Vec::new();
    for line in cleaned.lines() {
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

pub fn trim_title(text: &str, max: usize) -> String {
    let cleaned = extract_clean_user_prompt(text);
    let line = cleaned
        .lines()
        .map(str::trim)
        .filter(|l| {
            !l.is_empty()
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

fn b64_encode(data: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity((data.len() + 2) / 3 * 4);
    for chunk in data.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = if chunk.len() > 1 { chunk[1] as u32 } else { 0 };
        let b2 = if chunk.len() > 2 { chunk[2] as u32 } else { 0 };
        let n = (b0 << 16) | (b1 << 8) | b2;
        out.push(TABLE[((n >> 18) & 0x3f) as usize] as char);
        out.push(TABLE[((n >> 12) & 0x3f) as usize] as char);
        if chunk.len() > 1 {
            out.push(TABLE[((n >> 6) & 0x3f) as usize] as char);
        } else {
            out.push('=');
        }
        if chunk.len() > 2 {
            out.push(TABLE[(n & 0x3f) as usize] as char);
        } else {
            out.push('=');
        }
    }
    out
}

fn b64_decode(input: &str) -> Result<Vec<u8>, String> {
    let raw = match input.find("base64,") {
        Some(idx) => &input[idx + 7..],
        None => input,
    };
    let mut out = Vec::with_capacity(raw.len() * 3 / 4);
    let mut buf = 0u32;
    let mut bits = 0u32;
    for b in raw.bytes() {
        let val = match b {
            b'A'..=b'Z' => b - b'A',
            b'a'..=b'z' => b - b'a' + 26,
            b'0'..=b'9' => b - b'0' + 52,
            b'+' | b'-' => 62,
            b'/' | b'_' => 63,
            b'=' | b'\r' | b'\n' | b' ' | b'\t' => continue,
            _ => return Err("invalid base64 byte".into()),
        };
        buf = (buf << 6) | (val as u32);
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push(((buf >> bits) & 0xff) as u8);
        }
    }
    Ok(out)
}

fn sanitize_filename(name: &str) -> String {
    let cleaned: String = name
        .chars()
        .map(|c| {
            if c.is_alphanumeric() || matches!(c, '.' | '-' | '_' | '(' | ')') || (c as u32) > 127 {
                c
            } else {
                '_'
            }
        })
        .collect();
    let trimmed = cleaned.trim_matches('.');
    if trimmed.is_empty() {
        "upload.bin".to_string()
    } else {
        trimmed.to_string()
    }
}

/// 将手机/桌面端上传的 `files: [{ filename, mime, data }]` 落盘到 `<cwd>/.vibelink-uploads/`
pub fn save_uploaded_files(
    cwd: &str,
    files: Option<&Value>,
    into_subdir: bool,
) -> Vec<(String, String, String, usize)> {
    let Some(arr) = files.and_then(|v| v.as_array()) else {
        return Vec::new();
    };
    if arr.is_empty() {
        return Vec::new();
    }
    let resolved = resolve_cwd(cwd, None);
    let target_dir = if into_subdir {
        Path::new(&resolved).join(".vibelink-uploads")
    } else {
        PathBuf::from(&resolved)
    };
    let _ = fs::create_dir_all(&target_dir);
    let ts = now_ms();
    let mut saved = Vec::new();
    for (idx, item) in arr.iter().enumerate() {
        let raw_name = item
            .get("filename")
            .or_else(|| item.get("name"))
            .and_then(|x| x.as_str())
            .unwrap_or("attachment.bin");
        let mime = item
            .get("mime")
            .and_then(|x| x.as_str())
            .unwrap_or("application/octet-stream")
            .to_string();
        let data_b64 = item
            .get("data")
            .or_else(|| item.get("base64"))
            .and_then(|x| x.as_str())
            .unwrap_or("");
        if data_b64.is_empty() {
            continue;
        }
        let Ok(bytes) = b64_decode(data_b64) else { continue };
        let safe_name = sanitize_filename(raw_name);
        let file_name = if into_subdir {
            format!("{ts}_{idx}_{safe_name}")
        } else {
            safe_name.clone()
        };
        let full_path = target_dir.join(&file_name);
        if fs::write(&full_path, &bytes).is_ok() {
            saved.push((
                full_path.to_string_lossy().to_string(),
                safe_name,
                mime,
                bytes.len(),
            ));
        }
    }
    saved
}

fn augment_prompt_with_files(text: &str, saved: &[(String, String, String, usize)]) -> String {
    let base = if text.trim().is_empty() {
        if saved.is_empty() {
            String::new()
        } else {
            "请查看并处理我上传的附件文件。".to_string()
        }
    } else {
        text.trim().to_string()
    };
    if saved.is_empty() {
        return base;
    }
    let mut attachment_lines = Vec::new();
    attachment_lines.push("\n\n[Uploaded Attachments in Workspace]:".to_string());
    for (abs_path, name, mime, size) in saved {
        attachment_lines.push(format!(
            "- {name} ({mime}, {size} bytes) -> saved at `{abs_path}` (you can directly inspect/read this file)"
        ));
    }
    format!("{base}{}", attachment_lines.join("\n"))
}

fn simple_unified_diff(old_str: &str, new_str: &str) -> String {
    let mut lines = Vec::new();
    for l in old_str.lines().take(40) {
        lines.push(format!("- {l}"));
    }
    for l in new_str.lines().take(40) {
        lines.push(format!("+ {l}"));
    }
    lines.join("\n")
}

/// 同时提取纯文本摘要与 Cindy 风格的结构化块（text / reasoning / tool / diff / file）
fn content_blocks(payload: &Value) -> (String, Vec<Value>) {
    if let Some(s) = payload.get("content").and_then(|c| c.as_str()) {
        return (s.to_string(), vec![json!({ "type": "text", "text": s })]);
    }
    let mut texts = Vec::new();
    let mut blocks = Vec::new();
    if let Some(arr) = payload.get("content").and_then(|c| c.as_array()) {
        for p in arr {
            let ptype = p.get("type").and_then(|t| t.as_str()).unwrap_or("");
            if ptype == "thinking" || ptype == "reasoning" {
                let think = p
                    .get("thinking")
                    .or_else(|| p.get("text"))
                    .and_then(|t| t.as_str())
                    .unwrap_or("");
                if !think.trim().is_empty() {
                    blocks.push(json!({ "type": "reasoning", "text": think }));
                }
            } else if ptype == "tool_use" {
                let name = p.get("name").and_then(|n| n.as_str()).unwrap_or("tool");
                let input = p.get("input").cloned().unwrap_or(json!({}));
                let file_path = input
                    .get("file_path")
                    .or_else(|| input.get("path"))
                    .or_else(|| input.get("TargetFile"))
                    .or_else(|| input.get("Path"))
                    .and_then(|x| x.as_str())
                    .unwrap_or("");
                let cmd = input
                    .get("command")
                    .or_else(|| input.get("CommandLine"))
                    .or_else(|| input.get("cmd"))
                    .and_then(|x| x.as_str())
                    .unwrap_or("");
                let old_str = input
                    .get("old_string")
                    .or_else(|| input.get("Target"))
                    .and_then(|x| x.as_str())
                    .unwrap_or("");
                let new_str = input
                    .get("new_string")
                    .or_else(|| input.get("Replacement"))
                    .or_else(|| input.get("content"))
                    .and_then(|x| x.as_str())
                    .unwrap_or("");
                let diff = if !old_str.is_empty() || (!new_str.is_empty() && !file_path.is_empty()) {
                    simple_unified_diff(old_str, new_str)
                } else {
                    String::new()
                };
                let summary = if !cmd.is_empty() {
                    format!("{name}: {cmd}")
                } else if !file_path.is_empty() {
                    format!("{name}: {file_path}")
                } else {
                    format!("调用工具: {name}")
                };
                texts.push(format!("`[{summary}]`"));
                blocks.push(json!({
                    "type": "tool",
                    "name": name,
                    "title": summary,
                    "file": file_path,
                    "command": cmd,
                    "diff": diff,
                }));
            } else if let Some(t) = p.get("text").and_then(|t| t.as_str()) {
                if !t.trim().is_empty() {
                    texts.push(t.to_string());
                    blocks.push(json!({ "type": "text", "text": t }));
                }
            }
        }
    }
    (texts.join("\n"), blocks)
}

fn content_text(payload: &Value) -> String {
    content_blocks(payload).0
}

/// 把 "会话目录里的第一条用户消息" 当标题；没有标题时退回目录名。
fn title_from_messages(path: &Path, role_key: &str) -> Option<String> {
    let f = File::open(path).ok()?;
    for line in BufReader::new(f).lines().take(200).flatten() {
        let Ok(v) = serde_json::from_str::<Value>(&line) else { continue };
        let Some(p) = v.get(role_key) else { continue };
        let role = p
            .get("role")
            .or_else(|| v.get("type"))
            .and_then(|x| x.as_str())
            .unwrap_or("");
        if role != "user" {
            continue;
        }
        let text = content_text(p);
        if !text.trim().is_empty() && !is_noise(&text) {
            let cleaned = trim_title(&text, 80);
            if !cleaned.is_empty() {
                return Some(cleaned);
            }
        }
    }
    None
}

fn append_live_turn(id: &str, data: &mut Vec<Value>) {
    let real_id = resolve_session_id(id);
    let Some(live) = get_live(&real_id) else { return };
    let clean_live_user = extract_clean_user_prompt(&live.user_text);
    if live.running {
        let last_is_assistant = data
            .last()
            .and_then(|m| m.get("type"))
            .and_then(Value::as_str)
            == Some("assistant");
        let last_completed = data
            .last()
            .and_then(|m| m.pointer("/time/completed"))
            .and_then(Value::as_u64)
            .unwrap_or(0);
        let disk_has_current_user = !clean_live_user.is_empty()
            && data
                .iter()
                .rev()
                .find(|m| m.get("type").and_then(Value::as_str) == Some("user"))
                .and_then(|m| m.get("text").and_then(Value::as_str))
                .map(|t| {
                    let ct = extract_clean_user_prompt(t);
                    ct == clean_live_user || ct.starts_with(&clean_live_user) || clean_live_user.starts_with(&ct)
                })
                .unwrap_or(false);
        let disk_advanced = data.len() > live.base_msg_count || disk_has_current_user;

        // 若磁盘日志已写入本轮新消息且最后一条 assistant 已带有完成时间戳（completed > 0），
        // 说明底层 CLI 回合实际已经结束（可能因 Windows 子进程如 Gradle Daemon 继承 stdout 句柄导致 wait 未返回），
        // 立即将 live.running 置为 false，绝不把 completed 覆写回 0！
        if disk_advanced && last_is_assistant && last_completed > 0 {
            let last_assistant_created = data
                .last()
                .and_then(|m| m.pointer("/time/created"))
                .and_then(Value::as_u64)
                .unwrap_or(0);
            if data.len() > live.base_msg_count || last_assistant_created + 5_000 >= live.started_ms {
                if let Ok(mut map) = live_map().lock() {
                    if let Some(l) = map.get_mut(&real_id) {
                        l.running = false;
                        l.pid = None;
                    }
                    if real_id != id {
                        if let Some(l) = map.get_mut(id) {
                            l.running = false;
                            l.pid = None;
                        }
                    }
                }
                return;
            }
        }

        // 若超过 120 秒没有任何进展且磁盘仍未产生新步骤，自动解除卡死状态
        if !disk_advanced && now_ms().saturating_sub(live.updated) > 120_000 {
            if let Ok(mut map) = live_map().lock() {
                if let Some(l) = map.get_mut(&real_id) {
                    l.running = false;
                    l.pid = None;
                }
                if real_id != id {
                    if let Some(l) = map.get_mut(id) {
                        l.running = false;
                        l.pid = None;
                    }
                }
            }
            return;
        }

        // 如果磁盘文件尚未写入本轮新消息，先补上本地记录的 user 消息；
        // 若磁盘已实时写入本轮思考/工具步骤，则保留磁盘步骤，不截断、不重复追加！
        if !disk_advanced && !clean_live_user.is_empty() {
            data.push(json!({
                "id": format!("{id}-live-user-{}", live.started_ms),
                "type": "user",
                "text": clean_live_user,
                "time": { "created": live.started_ms }
            }));
        }
        let last_is_assistant_now = data
            .last()
            .and_then(|m| m.get("type"))
            .and_then(Value::as_str)
            == Some("assistant");
        if last_is_assistant_now && data.len() > live.base_msg_count {
            if let Some(last) = data.last_mut() {
                let disk_created = last
                    .pointer("/time/created")
                    .and_then(Value::as_u64)
                    .unwrap_or(live.started_ms);
                let created = if live.started_ms > 0 && disk_created >= live.started_ms {
                    live.started_ms
                } else {
                    disk_created
                };
                last["time"] = json!({ "created": created, "completed": 0 });
                if !live.reasoning_text.trim().is_empty() {
                    if let Some(arr) = last.get_mut("content").and_then(Value::as_array_mut) {
                        let already = arr.iter().any(|b| {
                            b.get("type").and_then(Value::as_str) == Some("reasoning")
                                && b.get("text").and_then(Value::as_str) == Some(live.reasoning_text.as_str())
                        });
                        if !already {
                            arr.push(json!({ "type": "reasoning", "text": live.reasoning_text }));
                        }
                    }
                }
            }
        } else {
            let reasoning = if !live.reasoning_text.trim().is_empty() {
                live.reasoning_text.clone()
            } else {
                "正在分析上下文与制定方案…".to_string()
            };
            let mut content = vec![json!({ "type": "reasoning", "text": reasoning })];
            if !live.assistant_text.trim().is_empty() {
                content.push(json!({ "type": "text", "text": live.assistant_text }));
            }
            data.push(json!({
                "id": format!("{id}-live-assistant-{}", live.started_ms),
                "type": "assistant",
                "content": content,
                "time": { "created": live.started_ms, "completed": 0 }
            }));
        }
    } else if let Some(err) = live.error.filter(|e| !e.trim().is_empty()) {
        if data.is_empty() {
            if !clean_live_user.is_empty() {
                data.push(json!({
                    "id": format!("{id}-err-user-{}", live.started_ms),
                    "type": "user",
                    "text": clean_live_user,
                    "time": { "created": live.started_ms }
                }));
            }
        }
        data.push(json!({
            "id": format!("{id}-err-assistant-{}", live.started_ms),
            "type": "assistant",
            "content": [{ "type": "text", "text": format!("执行出错：{err}") }],
            "time": { "created": live.started_ms, "completed": now_ms() }
        }));
    } else if data.is_empty() && !clean_live_user.is_empty() {
        data.push(json!({
            "id": format!("{id}-init-user-{}", live.started_ms),
            "type": "user",
            "text": clean_live_user,
            "time": { "created": live.started_ms }
        }));
        if !live.assistant_text.trim().is_empty() {
            data.push(json!({
                "id": format!("{id}-init-assistant-{}", live.started_ms),
                "type": "assistant",
                "content": [{ "type": "text", "text": live.assistant_text }],
                "time": { "created": live.started_ms, "completed": live.updated.max(live.started_ms + 1000) }
            }));
        }
    } else if data.len() > live.base_msg_count && live.started_ms > 0 {
        if let Some(last) = data.last_mut() {
            if last.get("type").and_then(Value::as_str) == Some("assistant") {
                let disk_created = last.pointer("/time/created").and_then(Value::as_u64).unwrap_or(0);
                let disk_completed = last.pointer("/time/completed").and_then(Value::as_u64).unwrap_or(0);
                let created = if disk_created == 0 || (disk_created >= live.started_ms && disk_created - live.started_ms <= 600_000) {
                    live.started_ms
                } else {
                    disk_created
                };
                let completed = if disk_completed > created {
                    disk_completed
                } else {
                    live.updated.max(created + 1000)
                };
                last["time"] = json!({ "created": created, "completed": completed });
            }
        }
    }
}

// ---------------- Codex ----------------

fn codex_root() -> PathBuf {
    crate::codex_runtime::current().home().join("sessions")
}

fn official_codex_install(path: &Path) -> bool {
    let resolved = path.canonicalize().unwrap_or_else(|_| path.to_path_buf());
    if resolved.to_string_lossy().replace('\\', "/").contains("/@openai/codex") { return true; }
    // npm's Windows shim sits beside node_modules, rather than inside the package.
    path.parent().is_some_and(|dir| {
        fs::read_to_string(dir.join("node_modules/@openai/codex/package.json")).ok()
            .and_then(|text| serde_json::from_str::<Value>(&text).ok())
            .is_some_and(|manifest| manifest["name"] == "@openai/codex")
            && fs::read_to_string(path).is_ok_and(|shim| shim.contains("@openai/codex"))
    })
}

pub(crate) fn find_codex_exe() -> Option<PathBuf> {
    let mut candidates: Vec<PathBuf> = Vec::new();
    if cfg!(windows) {
        let appdata = std::env::var("APPDATA").unwrap_or_default();
        let userprofile = std::env::var("USERPROFILE").unwrap_or_default();
        candidates.push(PathBuf::from(format!(
            r"{appdata}\npm\node_modules\@openai\codex\node_modules\@openai\codex-win32-x64\vendor\x86_64-pc-windows-msvc\bin\codex.exe"
        )));
        candidates.push(PathBuf::from(format!(
            r"{appdata}\npm\node_modules\@openai\codex\node_modules\@openai\codex-win32-x64\vendor\x86_64-pc-windows-msvc\codex\codex.exe"
        )));
        candidates.push(PathBuf::from(format!(
            r"{appdata}\npm\node_modules\@openai\codex\bin\codex.exe"
        )));
        candidates.push(PathBuf::from(format!(r"{userprofile}\.local\bin\codex.exe")));
        candidates.push(PathBuf::from(format!(r"{appdata}\npm\codex.cmd")));
    } else {
        let h = home();
        candidates.push(h.join(".local/bin/codex"));
        candidates.push(PathBuf::from("/opt/homebrew/bin/codex"));
        candidates.push(PathBuf::from("/usr/local/bin/codex"));
    }
    for c in candidates {
        if c.is_file() && official_codex_install(&c) {
            return Some(c);
        }
    }
    let path = std::env::var("PATH").unwrap_or_default();
    for dir in std::env::split_paths(&path) {
        #[cfg(windows)]
        for name in ["codex.exe", "codex.cmd"] {
            let p = dir.join(name);
            if p.is_file() && official_codex_install(&p) {
                return Some(p);
            }
        }
        #[cfg(not(windows))]
        {
            let p = dir.join("codex");
            if p.is_file() && official_codex_install(&p) {
                return Some(p);
            }
        }
    }
    None
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
    let mut seen = std::collections::HashSet::new();
    let mut out = Vec::new();
    for path in files {
        let Some(meta) = codex_meta(&path) else { continue };
        if meta.get("thread_source").and_then(|x| x.as_str()) == Some("subagent") {
            continue;
        }
        let id = meta
            .get("id")
            .or_else(|| meta.get("session_id"))
            .and_then(|x| x.as_str())
            .unwrap_or("")
            .to_string();
        if id.is_empty() || !seen.insert(id.clone()) {
            continue;
        }
        let cwd = meta.get("cwd").and_then(|x| x.as_str()).unwrap_or("").to_string();
        let live = get_live(&id);
        let running = live.as_ref().map(|l| l.running).unwrap_or(false);
        let updated = mtime_ms(&path).max(live.as_ref().map(|l| l.updated).unwrap_or(0));
        let title = title_from_messages(&path, "payload")
            .filter(|t| !t.is_empty())
            .or_else(|| live.as_ref().map(|l| l.title.clone()).filter(|t| !t.is_empty()))
            .unwrap_or_else(|| {
                cwd.rsplit(['\\', '/']).find(|s| !s.is_empty()).unwrap_or("Codex 会话").to_string()
            });
        out.push(json!({
            "id": id,
            "title": title,
            "cwd": cwd,
            "updated": updated,
            "running": running,
        }));
    }
    if let Ok(map) = live_map().lock() {
        for live in map.values() {
            let resolved_live_id = resolve_session_id(&live.id);
            if live.agent == "codex" && live.runtime_scope == crate::codex_runtime::current().scope && !seen.contains(&live.id) && !seen.contains(&resolved_live_id) {
                seen.insert(live.id.clone());
                out.push(json!({
                    "id": live.id,
                    "title": if live.title.is_empty() { "新 Codex 会话" } else { &live.title },
                    "cwd": live.cwd,
                    "updated": live.updated,
                    "running": live.running,
                }));
            }
        }
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
    let mut fallback = None;
    for path in files {
        if let Some(meta) = codex_meta(&path) {
            let tid = meta.get("id").and_then(|x| x.as_str()).unwrap_or("");
            if tid == id {
                return Some(path);
            }
            let sid = meta.get("session_id").and_then(|x| x.as_str()).unwrap_or("");
            if sid == id && meta.get("thread_source").and_then(|x| x.as_str()) != Some("subagent") {
                fallback = Some(path);
            }
        }
    }
    fallback
}

/// 解析 ISO-8601 时间字符串（如 "2026-11-04T02:07:14Z"、"2026-11-03T12:15:51.806Z"、"2026-11-06T04:01:25+08:00"）为毫秒时间戳。
fn parse_iso8601_ms(s: &str) -> Option<u64> {
    let s = s.trim();
    if s.len() < 19 {
        return None;
    }
    let year: i64 = s.get(0..4)?.parse().ok()?;
    if s.as_bytes().get(4) != Some(&b'-') {
        return None;
    }
    let month: u32 = s.get(5..7)?.parse().ok()?;
    if s.as_bytes().get(7) != Some(&b'-') {
        return None;
    }
    let day: u32 = s.get(8..10)?.parse().ok()?;
    let sep = *s.as_bytes().get(10)?;
    if sep != b'T' && sep != b't' && sep != b' ' {
        return None;
    }
    let hour: i64 = s.get(11..13)?.parse().ok()?;
    if s.as_bytes().get(13) != Some(&b':') {
        return None;
    }
    let min: i64 = s.get(14..16)?.parse().ok()?;
    if s.as_bytes().get(16) != Some(&b':') {
        return None;
    }
    let sec: i64 = s.get(17..19)?.parse().ok()?;
    if !(1..=12).contains(&month)
        || !(1..=31).contains(&day)
        || !(0..=23).contains(&hour)
        || !(0..=59).contains(&min)
        || !(0..=60).contains(&sec)
    {
        return None;
    }

    let mut rest = &s[19..];
    let mut millis: i64 = 0;
    if let Some(after_dot) = rest.strip_prefix('.') {
        let digits_len = after_dot.bytes().take_while(|b| b.is_ascii_digit()).count();
        let frac = &after_dot[..digits_len];
        millis = match frac.len() {
            0 => 0,
            1 => frac.parse::<i64>().ok()? * 100,
            2 => frac.parse::<i64>().ok()? * 10,
            _ => frac[..3].parse::<i64>().ok()?,
        };
        rest = &after_dot[digits_len..];
    }

    let offset_sec: i64 = if rest.is_empty() || rest.eq_ignore_ascii_case("Z") {
        0
    } else if rest.starts_with('+') || rest.starts_with('-') {
        let sign: i64 = if rest.starts_with('-') { -1 } else { 1 };
        let tz = &rest[1..];
        let (tz_h, tz_m) = if tz.len() >= 5 && tz.as_bytes()[2] == b':' {
            (tz.get(0..2)?.parse::<i64>().ok()?, tz.get(3..5)?.parse::<i64>().ok()?)
        } else if tz.len() >= 4 {
            (tz.get(0..2)?.parse::<i64>().ok()?, tz.get(2..4)?.parse::<i64>().ok()?)
        } else if tz.len() == 2 {
            (tz.get(0..2)?.parse::<i64>().ok()?, 0)
        } else {
            return None;
        };
        sign * (tz_h * 3600 + tz_m * 60)
    } else {
        0
    };

    let y = year - if month <= 2 { 1 } else { 0 };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = (y - era * 400) as u32;
    let m = month;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146097 + (doe as i64) - 719468;

    let total_sec = days * 86400 + hour * 3600 + min * 60 + sec - offset_sec;
    let total_ms = total_sec * 1000 + millis;
    if total_ms > 0 {
        Some(total_ms as u64)
    } else {
        None
    }
}

/// 将同一轮用户提问下的连续多个 assistant 步骤（思考 + 多次工具调用 + 阶段汇报 + 最终回答）合并为单条 assistant 消息，
/// 使得手机端像 DeepSeek 一样将整轮思考与工具调用全部收纳进顶部折叠区，仅直接展示最终回答正文。
fn merge_assistant_turns(raw: Vec<Value>) -> Vec<Value> {
    let mut merged: Vec<Value> = Vec::with_capacity(raw.len());
    for item in raw {
        let is_assistant = item.get("type").and_then(Value::as_str) == Some("assistant");
        let prev_is_assistant = merged
            .last()
            .and_then(|p| p.get("type"))
            .and_then(Value::as_str)
            == Some("assistant");

        if is_assistant && prev_is_assistant {
            let prev = merged.last_mut().unwrap();
            let prev_created = prev
                .pointer("/time/created")
                .and_then(Value::as_u64)
                .unwrap_or(0);
            let curr_created = item
                .pointer("/time/created")
                .and_then(Value::as_u64)
                .unwrap_or(prev_created);
            let curr_completed = item
                .pointer("/time/completed")
                .and_then(Value::as_u64)
                .unwrap_or(0);
            let start_ts = if prev_created > 0 { prev_created } else { curr_created };

            let mut combined_blocks = prev
                .get("content")
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default();
            if let Some(curr_blocks) = item.get("content").and_then(Value::as_array) {
                combined_blocks.extend(curr_blocks.iter().cloned());
            }
            // 保持同轮首个步骤的 id 不变，确保手机端流式思考组件不会因 id 跳变而重置动画/滚动状态
            prev["content"] = Value::Array(combined_blocks);
            prev["time"] = json!({
                "created": start_ts,
                "completed": curr_completed,
            });
        } else {
            merged.push(item);
        }
    }

    let merged_len = merged.len();
    // 对每个合并后的 assistant 回合：
    // 1. 历史回合（非最后一条）若曾因中断留下 completed == 0，补齐完成时间戳；
    // 2. 若回合已完成且存在多段 text，将前序阶段提示转为 reasoning；
    // 3. 若当前最后一条回合仍在思考/执行工具（completed == 0），将其中的过渡 text 统一归入 reasoning，使手机端保持「正在思考」实时流视图。
    for i in 0..merged_len {
        let prev_user_created = if i > 0 && merged[i - 1].get("type").and_then(Value::as_str) == Some("user") {
            merged[i - 1].pointer("/time/created").and_then(Value::as_u64).unwrap_or(0)
        } else {
            0
        };
        let item = &mut merged[i];
        if item.get("type").and_then(Value::as_str) != Some("assistant") {
            continue;
        }
        let is_last = i + 1 == merged_len;
        let mut created = item.pointer("/time/created").and_then(Value::as_u64).unwrap_or(0);
        if prev_user_created > 0 && (created == 0 || (created >= prev_user_created && created - prev_user_created <= 600_000)) {
            created = prev_user_created;
            item["time"]["created"] = json!(created);
        }
        let mut completed = item.pointer("/time/completed").and_then(Value::as_u64).unwrap_or(0);
        if !is_last && completed == 0 {
            completed = created.saturating_add(5000);
            item["time"]["completed"] = json!(completed);
        }
        let still_streaming = is_last && completed == 0;

        if let Some(blocks) = item.get_mut("content").and_then(Value::as_array_mut) {
            let text_indices: Vec<usize> = blocks
                .iter()
                .enumerate()
                .filter_map(|(idx, b)| {
                    if b.get("type").and_then(Value::as_str) == Some("text")
                        && !b.get("text").and_then(Value::as_str).unwrap_or("").trim().is_empty()
                    {
                        Some(idx)
                    } else {
                        None
                    }
                })
                .collect();
            if still_streaming {
                for idx in text_indices {
                    blocks[idx]["type"] = json!("reasoning");
                }
            } else if text_indices.len() > 1 {
                let last_text_idx = *text_indices.last().unwrap();
                for idx in text_indices {
                    if idx != last_text_idx {
                        blocks[idx]["type"] = json!("reasoning");
                    }
                }
            }
        }
    }

    merged
}

/// 将按时间正序（旧 → 新）排列的消息列表分页，返回与 opencode 对齐的「新 → 旧」单页数据与 cursor.next。
fn paginate_messages(data: Vec<Value>, cursor: &str) -> Value {
    let data = merge_assistant_turns(data);
    const PAGE_SIZE: usize = 120;
    let total = data.len();
    let end = if cursor.trim().is_empty() {
        total
    } else {
        cursor.trim().parse::<usize>().unwrap_or(0).min(total)
    };
    let start = end.saturating_sub(PAGE_SIZE);
    let mut page = data[start..end].to_vec();
    // RemoteApi.kt 会对返回数组做 list.reversed()（与 opencode 的 order=desc 对齐），这里返回「新 → 旧」
    page.reverse();
    if start > 0 {
        json!({
            "data": page,
            "cursor": { "next": start.to_string() }
        })
    } else {
        json!({
            "data": page
        })
    }
}

fn read_codex_disk_messages(id: &str) -> Vec<Value> {
    let Some(path) = find_codex_file(id) else { return Vec::new() };
    let base_ts = mtime_ms(&path).max(1_000_000);
    let Ok(f) = File::open(&path) else { return Vec::new() };
    let mut data = Vec::new();
    let mut prev_ts = 0u64;
    for (idx, line) in BufReader::new(f).lines().flatten().enumerate() {
        let Ok(v) = serde_json::from_str::<Value>(&line) else { continue };
        let step_ts = v
            .get("timestamp")
            .or_else(|| v.get("created_at"))
            .and_then(Value::as_str)
            .and_then(parse_iso8601_ms)
            .unwrap_or_else(|| if prev_ts > 0 { prev_ts + 1 } else { base_ts + idx as u64 });
        if v.get("type").and_then(|x| x.as_str()) != Some("response_item") {
            continue;
        }
        let Some(p) = v.get("payload") else { continue };
        let ptype = p.get("type").and_then(|x| x.as_str()).unwrap_or("");
        if ptype == "reasoning" {
            let mut r_parts = Vec::new();
            if let Some(t) = p.get("text").and_then(Value::as_str) {
                if !t.trim().is_empty() {
                    r_parts.push(t.trim().to_string());
                }
            }
            if let Some(arr) = p.get("summary").and_then(Value::as_array) {
                for item in arr {
                    if let Some(t) = item.get("text").and_then(Value::as_str) {
                        if !t.trim().is_empty() {
                            r_parts.push(t.trim().to_string());
                        }
                    }
                }
            }
            if !r_parts.is_empty() {
                let msg_id = format!("{id}-rsn-{idx}");
                prev_ts = step_ts;
                data.push(json!({
                    "id": msg_id,
                    "type": "assistant",
                    "content": [{
                        "type": "reasoning",
                        "text": r_parts.join("\n\n"),
                    }],
                    "time": { "created": step_ts, "completed": 0 }
                }));
            }
            continue;
        }
        if ptype == "function_call" || ptype == "custom_tool_call" {
            let name = p.get("name").and_then(|x| x.as_str()).unwrap_or("tool");
            let args = p.get("arguments").or_else(|| p.get("input")).and_then(|x| x.as_str()).unwrap_or("");
            let msg_id = format!("{id}-tool-{idx}");
            prev_ts = step_ts;
            data.push(json!({
                "id": msg_id,
                "type": "assistant",
                "content": [{
                    "type": "tool",
                    "name": name,
                    "title": format!("{name}"),
                    "command": args.chars().take(240).collect::<String>(),
                    "diff": "",
                    "file": "",
                }],
                "time": { "created": step_ts, "completed": 0 }
            }));
            continue;
        }
        if ptype != "message" {
            continue;
        }
        let role = p.get("role").and_then(|x| x.as_str()).unwrap_or("");
        if role != "user" && role != "assistant" {
            continue;
        }
        let (raw, blocks) = content_blocks(p);
        if raw.trim().is_empty() && blocks.is_empty() {
            continue;
        }
        if role == "user" && is_noise(&raw) {
            continue;
        }
        let text = if role == "user" { strip_headers(&raw) } else { raw };
        if text.trim().is_empty() && blocks.is_empty() {
            continue;
        }
        let msg_id = format!("{id}-{idx}");
        let started_ts = if role == "assistant" && prev_ts > 0 && step_ts >= prev_ts && step_ts - prev_ts <= 600_000 {
            prev_ts
        } else {
            step_ts
        };
        prev_ts = step_ts;
        data.push(if role == "user" {
            json!({ "id": msg_id, "type": "user", "text": text, "time": { "created": step_ts } })
        } else {
            let has_tool = blocks.iter().any(|b| b.get("type").and_then(Value::as_str) == Some("tool"));
            let content = if blocks.is_empty() {
                vec![json!({ "type": "text", "text": text })]
            } else {
                blocks
            };
            let completed_ts = if has_tool { 0 } else { step_ts };
            json!({
                "id": msg_id,
                "type": "assistant",
                "content": content,
                "time": { "created": started_ts, "completed": completed_ts }
            })
        });
    }
    data
}

fn ensure_codex_scope(id:&str)->Result<(),String> {
    if get_live(&resolve_session_id(id)).is_some_and(|live|live.runtime_scope!=crate::codex_runtime::current().scope) {return Err("Session belongs to another Codex runtime".into());}
    Ok(())
}

pub fn codex_messages(id: &str, cursor: &str) -> Result<Value, String> {
    ensure_codex_scope(id)?;
    let real_id = resolve_session_id(id);
    let mut data = read_codex_disk_messages(&real_id);
    if data.is_empty() && find_codex_file(&real_id).is_none() && get_live(&real_id).is_none() {
        return Err(format!("codex session {id} not found"));
    }
    append_live_turn(&real_id, &mut data);
    Ok(paginate_messages(data, cursor))
}

fn spawn_codex_runner(
    existing_sid: Option<String>,
    cwd: String,
    prompt: String,
    id_tx: Option<mpsc::Sender<Result<String, String>>>,
    cancellation: crate::session_cancellation::Ticket,
) {
    crate::codex_runtime::spawn(move || {
        if !cancellation.is_current() {
            if let Some(tx) = id_tx { let _ = tx.send(Err("codex_cancelled: request was interrupted before launch".into())); }
            return;
        }
        let Some(exe) = find_codex_exe() else {
            if let Some(tx) = id_tx {
                let _ = tx.send(Err("codex CLI not found".into()));
            }
            return;
        };
        let is_cmd = exe
            .extension()
            .and_then(|e| e.to_str())
            .map(|e| e.eq_ignore_ascii_case("cmd") || e.eq_ignore_ascii_case("bat"))
            .unwrap_or(false);
        let mut cmd = if is_cmd {
            let mut c = Command::new("cmd");
            c.arg("/C").arg(&exe);
            c
        } else {
            Command::new(&exe)
        };
        if let Err(error) = crate::codex_runtime::current().configure(&mut cmd) {
            if let Some(tx) = id_tx { let _ = tx.send(Err(error)); }
            return;
        }
        if crate::codex_runtime::current().beta { /* restrictive permission profile from runtime */ } else if crate::codex_runtime::current().read_only {cmd.args(["--sandbox","read-only"]);} else { cmd.arg("--dangerously-bypass-approvals-and-sandbox"); }
        let resolved_existing = existing_sid.as_ref().map(|s| resolve_session_id(s));
        let has_disk_session = resolved_existing
            .as_ref()
            .and_then(|s| find_codex_file(s))
            .is_some();
        if let (Some(sid), true) = (&resolved_existing, has_disk_session) {
            cmd.args([
                "exec",
                "resume",
                "--json",
                "--skip-git-repo-check",
                sid,
                "-",
            ]);
        } else {
            cmd.args([
                "exec",
                "-C",
                &cwd,
                "--json",
                "--skip-git-repo-check",
                "-",
            ]);
        }
        if Path::new(&cwd).is_dir() {
            cmd.current_dir(&cwd);
        }
        cmd.stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(0x0800_0000);
        }

        let mut child = match cancellation.launch(|| {
            let child = cmd.spawn().map_err(|error| error.to_string())?;
            let pid = child.id();
            Ok((child, pid))
        }) {
            Ok(c) => c,
            Err(e) => {
                if let Some(tx) = id_tx {
                    let _ = tx.send(Err(format!("spawn codex failed: {e}")));
                }
                if !cancellation.is_current() { return; }
                if let Some(sid) = existing_sid {
                    let real_sid = resolve_session_id(&sid);
                    if let Ok(mut map) = live_map().lock() {
                        if let Some(l) = map.get_mut(&real_sid) {
                            l.running = false;
                            l.error = Some(e.to_string());
                        }
                        if real_sid != sid {
                            if let Some(l) = map.get_mut(&sid) {
                                l.running = false;
                                l.error = Some(e.to_string());
                            }
                        }
                    }
                }
                return;
            }
        };

        let pid = child.id();
        // The launch fence already owns the PID. Publish it and send stdin only
        // while this generation is current, closing the pre-LiveSession window.
        let delivered = cancellation.while_current(|| {
            if let Some(sid) = &resolved_existing {
                if let Ok(mut map) = live_map().lock() {
                    if let Some(live) = map.get_mut(sid) { live.pid = Some(pid); }
                }
            }
            if let Some(mut stdin) = child.stdin.take() {
                let _ = stdin.write_all(prompt.as_bytes());
                let _ = stdin.flush();
            }
        });
        if delivered.is_none() {
            kill_pid(pid);
            let _ = child.wait();
            cancellation.finished(pid);
            if let Some(tx) = id_tx { let _ = tx.send(Err("codex_cancelled: request was interrupted during launch".into())); }
            return;
        }

        // Always drain stderr. CLI startup errors must not fill its pipe or leave
        // a rejected tenant launch waiting forever before thread.started.
        if let Some(stderr) = child.stderr.take() {
            let init = id_tx.clone();
            let beta = crate::codex_runtime::current().beta;
            thread::spawn(move || {
                for line in BufReader::new(stderr).lines().map_while(Result::ok) {
                    if beta && (line.contains("refusing to run unsandboxed")
                        || line.contains("Restricted read-only access requires")
                        || line.contains("requires effective `:root` read access")) {
                        if let Some(tx) = &init {
                            let _ = tx.send(Err("Worker cannot enforce tenant filesystem isolation. Configure a supported isolated Linux/WSL worker; unsafe fallback is disabled".into()));
                        }
                        kill_pid(pid);
                        break;
                    }
                }
            });
        }
        let mut active_id = resolved_existing.clone().unwrap_or_default();
        let mut tx_opt = id_tx;
        if let Some(stdout) = child.stdout.take() {
            for line in BufReader::new(stdout).lines().flatten() {
                let Ok(v) = serde_json::from_str::<Value>(&line) else { continue };
                let kind = v.get("type").and_then(|x| x.as_str()).unwrap_or("");
                if kind == "thread.started" {
                    if let Some(tid) = v.get("thread_id").and_then(Value::as_str) {
                        if !cancellation.bind(tid, kill_pid) { break; }
                    }
                }
                let finished = cancellation.while_current(|| {
                    if kind == "thread.started" {
                        if let Some(tid) = v.get("thread_id").and_then(|x| x.as_str()) {
                            if let Some(ref init_sid) = existing_sid {
                                if init_sid != tid {
                                    register_session_alias(init_sid, tid);
                                }
                            }
                            mark_headless_created(tid);
                            if active_id != tid {
                                let started = now_ms();
                                active_id = tid.to_string();
                                if let Ok(mut map) = live_map().lock() {
                                    map.insert(
                                        active_id.clone(),
                                        LiveSession {
                                            runtime_scope: crate::codex_runtime::current().scope,
                                            agent: "codex".into(),
                                            id: active_id.clone(),
                                            title: trim_title(&prompt, 60),
                                            cwd: cwd.clone(),
                                            updated: started,
                                            started_ms: started,
                                            pid: Some(pid),
                                            base_msg_count: 0,
                                            user_text: extract_clean_user_prompt(&prompt),
                                            assistant_text: String::new(),
                                            reasoning_text: String::new(),
                                            running: true,
                                            error: None,
                                        },
                                    );
                                }
                            }
                            if let Some(tx) = tx_opt.take() {
                                let _ = tx.send(Ok(active_id.clone()));
                            }
                        }
                    } else if kind == "item.completed" || kind == "item.started" {
                        if let Some(item) = v.get("item") {
                            let itype = item.get("type").and_then(|x| x.as_str()).unwrap_or("");
                            if itype == "agent_message" {
                                if let Some(t) = item.get("text").and_then(|x| x.as_str()) {
                                    if !active_id.is_empty() {
                                        if let Ok(mut map) = live_map().lock() {
                                            if let Some(l) = map.get_mut(&active_id).filter(|live| live.pid == Some(pid)) {
                                                l.assistant_text = t.to_string();
                                                l.updated = now_ms();
                                            }
                                            if let Some(ref init_sid) = existing_sid {
                                                if init_sid != &active_id {
                                                    if let Some(l) = map.get_mut(init_sid).filter(|live| live.pid == Some(pid)) {
                                                        l.assistant_text = t.to_string();
                                                        l.updated = now_ms();
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if itype == "reasoning" {
                                if let Some(t) = item.get("text").and_then(|x| x.as_str()) {
                                    if !active_id.is_empty() && !t.trim().is_empty() {
                                        if let Ok(mut map) = live_map().lock() {
                                            if let Some(l) = map.get_mut(&active_id).filter(|live| live.pid == Some(pid)) {
                                                l.reasoning_text = t.to_string();
                                                l.updated = now_ms();
                                            }
                                            if let Some(ref init_sid) = existing_sid {
                                                if init_sid != &active_id {
                                                    if let Some(l) = map.get_mut(init_sid).filter(|live| live.pid == Some(pid)) {
                                                        l.reasoning_text = t.to_string();
                                                        l.updated = now_ms();
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if itype == "command_execution" {
                                if let Some(cmd_str) = item.get("command").and_then(|x| x.as_str()) {
                                    if !active_id.is_empty() {
                                        if let Ok(mut map) = live_map().lock() {
                                            if let Some(l) = map.get_mut(&active_id).filter(|live| live.pid == Some(pid)) {
                                                l.reasoning_text = format!("正在执行命令: {cmd_str}");
                                                l.updated = now_ms();
                                            }
                                            if let Some(ref init_sid) = existing_sid {
                                                if init_sid != &active_id {
                                                    if let Some(l) = map.get_mut(init_sid).filter(|live| live.pid == Some(pid)) {
                                                        l.reasoning_text = format!("正在执行命令: {cmd_str}");
                                                        l.updated = now_ms();
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if kind == "turn.completed" || kind == "turn.failed" {
                        if !active_id.is_empty() {
                            if let Ok(mut map) = live_map().lock() {
                                if let Some(l) = map.get_mut(&active_id).filter(|live| live.pid == Some(pid)) {
                                    l.running = false;
                                    l.pid = None;
                                    l.updated = now_ms();
                                }
                                if let Some(ref init_sid) = existing_sid {
                                    if let Some(l) = map.get_mut(init_sid).filter(|live| live.pid == Some(pid)) {
                                        l.running = false;
                                        l.pid = None;
                                        l.updated = now_ms();
                                    }
                                }
                            }
                        }
                        return true;
                    }
                    false
                });
                if finished != Some(false) { break; }

            }
        }

        let status = child.wait().ok();
        cancellation.finished(pid);
        if let Some(tx) = tx_opt.take() {
            let _ = tx.send(Err("codex exited before starting thread".into()));
        }
        if !active_id.is_empty() {
            if let Ok(mut map) = live_map().lock() {
                if let Some(l) = map.get_mut(&active_id).filter(|live| live.pid == Some(pid)) {
                    l.running = false;
                    l.pid = None;
                    l.updated = now_ms();
                    if status.map(|s| !s.success()).unwrap_or(true) && l.assistant_text.is_empty() {
                        l.error = Some("Codex 进程异常退出".into());
                    }
                }
                if let Some(ref init_sid) = existing_sid {
                    if let Some(l) = map.get_mut(init_sid).filter(|live| live.pid == Some(pid)) {
                        l.running = false;
                        l.pid = None;
                        l.updated = now_ms();
                    }
                }
            }
        }
    });
}

pub fn codex_create(cwd: &str, text: &str, files: Option<&Value>) -> Result<Value, String> {
    crate::codex_runtime::current().prepare()?;
    crate::subscription_policy::validate_project(cwd)?;
    let latest_cwd = codex_sessions()
        .first()
        .and_then(|s| s.get("cwd").and_then(|x| x.as_str()).map(str::to_string));
    let resolved_cwd = resolve_cwd(cwd, latest_cwd.as_deref());
    crate::subscription_policy::validate_project(&resolved_cwd)?;
    let saved = save_uploaded_files(&resolved_cwd, files, true);
    let augmented = augment_prompt_with_files(text, &saved);
    if augmented.trim().is_empty() {
        let sid = new_uuid_v4();
        mark_headless_created(&sid);
        let started = now_ms();
        let title = "新 Codex 会话".to_string();
        if let Ok(mut map) = live_map().lock() {
            map.insert(
                sid.clone(),
                LiveSession {
                    runtime_scope: crate::codex_runtime::current().scope,
                    agent: "codex".into(),
                    id: sid.clone(),
                    title: title.clone(),
                    cwd: resolved_cwd.clone(),
                    updated: started,
                    started_ms: started,
                    pid: None,
                    base_msg_count: 0,
                    user_text: String::new(),
                    assistant_text: String::new(),
                    reasoning_text: String::new(),
                    running: false,
                    error: None,
                },
            );
        }
        return Ok(json!({
            "id": sid,
            "agent": "codex",
            "cwd": resolved_cwd,
            "title": title,
            "running": false,
        }));
    }
    let prompt = augmented;
    let (tx, rx) = mpsc::channel();
    let cancellation = crate::session_cancellation::Ticket::capture(&crate::codex_runtime::current().scope, None);
    spawn_codex_runner(None, resolved_cwd.clone(), prompt.clone(), Some(tx), cancellation);
    match rx.recv_timeout(Duration::from_secs(10)) {
        Ok(Ok(tid)) => {
            mark_headless_created(&tid);
            Ok(json!({
                "id": tid,
                "agent": "codex",
                "cwd": resolved_cwd,
                "title": trim_title(&prompt, 60),
                "running": true,
            }))
        }
        Ok(Err(e)) => Err(e),
        Err(_) => Err("等待 Codex 初始化新会话超时".into()),
    }
}

pub fn codex_prompt(id: &str, text: &str, cwd: Option<&str>, files: Option<&Value>) -> Result<Value, String> {
    ensure_codex_scope(id)?;
    let real_id = resolve_session_id(id);
    let cancellation = crate::session_cancellation::Ticket::capture(&crate::codex_runtime::current().scope, Some(&real_id));
    crate::codex_runtime::current().prepare()?;
    let disk_cwd = find_codex_file(&real_id)
        .and_then(|p| codex_meta(&p))
        .and_then(|m| m.get("cwd").and_then(|x| x.as_str()).map(str::to_string));
    let live_cwd = get_live(&real_id).map(|l| l.cwd);
    let resolved_cwd = resolve_cwd(
        cwd.unwrap_or(""),
        disk_cwd.as_deref().or(live_cwd.as_deref()),
    );
    crate::subscription_policy::validate_project(&resolved_cwd)?;
    let saved = save_uploaded_files(&resolved_cwd, files, true);
    let final_prompt = augment_prompt_with_files(text, &saved);
    if final_prompt.trim().is_empty() {
        return Err("text or files required".into());
    }
    let clean_user = extract_clean_user_prompt(&final_prompt);
    if live_running(&real_id) == Some(true) {
        let orig_id = id.to_string();
        let queued_real_id = real_id.clone();
        let queued_cwd = resolved_cwd.clone();
        let queued_prompt = final_prompt.clone();
        let queued_clean = clean_user.clone();
        crate::codex_runtime::spawn(move || {
            let wait_start = now_ms();
            if !cancellation.wait_until_ready(
                || live_running(&queued_real_id) == Some(true) && now_ms().saturating_sub(wait_start) < 600_000,
                || thread::sleep(Duration::from_millis(450)),
            ) { return; }
            let prepared = cancellation.while_current(|| {
                    let base_count = read_codex_disk_messages(&queued_real_id).len();
                    let started = now_ms();
                    if let Ok(mut map) = live_map().lock() {
                        let session_entry = LiveSession {
                            runtime_scope: crate::codex_runtime::current().scope,
                            agent: "codex".into(),
                            id: queued_real_id.clone(),
                            title: trim_title(&queued_clean, 60),
                            cwd: queued_cwd.clone(),
                            updated: started,
                            started_ms: started,
                            pid: None,
                            base_msg_count: base_count,
                            user_text: queued_clean.clone(),
                            assistant_text: String::new(),
                            reasoning_text: String::new(),
                            running: true,
                            error: None,
                        };
                        map.insert(queued_real_id.clone(), session_entry.clone());
                        if queued_real_id != orig_id {
                            map.insert(orig_id.clone(), session_entry);
                        }
                    }
                    base_count
                });
                let Some(base_count) = prepared else { return; };
                if try_echo_to_interactive_cli(
                    "codex",
                    &queued_real_id,
                    &queued_cwd,
                    &queued_clean,
                    &queued_prompt,
                    base_count,
                ) {
                    return;
                }
                spawn_codex_runner(Some(queued_real_id), queued_cwd, queued_prompt, None, cancellation);
            });
            return Ok(json!({ "ok": true, "id": id, "queued": true }));
        }
        let prepared = cancellation.while_current(|| {
        let base_count = read_codex_disk_messages(&real_id).len();
        let started = now_ms();
        if let Ok(mut map) = live_map().lock() {
            let session_entry = LiveSession {
                runtime_scope: crate::codex_runtime::current().scope,
                agent: "codex".into(),
                id: real_id.clone(),
                title: trim_title(&clean_user, 60),
                cwd: resolved_cwd.clone(),
                updated: started,
                started_ms: started,
                pid: None,
                base_msg_count: base_count,
                user_text: clean_user.clone(),
                assistant_text: String::new(),
                reasoning_text: String::new(),
                running: true,
                error: None,
            };
            map.insert(real_id.clone(), session_entry.clone());
            if real_id != id {
                map.insert(id.to_string(), session_entry);
            }
        }
        base_count
    });
    let Some(base_count) = prepared else { return Err("codex_cancelled: prompt was interrupted before launch".into()); };
    if try_echo_to_interactive_cli(
        "codex",
        &real_id,
        &resolved_cwd,
        &clean_user,
        &final_prompt,
        base_count,
    ) {
        return Ok(json!({ "ok": true, "id": id, "echoed": true }));
    }
    let is_new_disk_thread = find_codex_file(&real_id).is_none();
    if is_new_disk_thread {
        let (tx, rx) = mpsc::channel();
        spawn_codex_runner(Some(real_id.clone()), resolved_cwd, final_prompt, Some(tx), cancellation);
        if let Ok(Ok(tid)) = rx.recv_timeout(Duration::from_secs(5)) {
            return Ok(json!({ "ok": true, "id": tid, "aliasOf": id }));
        }
        return Ok(json!({ "ok": true, "id": real_id }));
    }
    spawn_codex_runner(Some(real_id), resolved_cwd, final_prompt, None, cancellation);
    Ok(json!({ "ok": true, "id": id }))
}

pub fn codex_interrupt(id: &str) -> Result<Value, String> {
    ensure_codex_scope(id)?;
    let real_id = resolve_session_id(id);
    let known = crate::session_cancellation::interrupt_with(&crate::codex_runtime::current().scope, &real_id, kill_pid, || {
        let mut known = false;
        if let Ok(mut map) = live_map().lock() {
            for key in [real_id.as_str(), id] {
                if let Some(live) = map.get_mut(key) {
                    known = true;
                    // The fence owns every in-process Codex child, including those
                    // not yet visible here. Never kill again via a stale PID.
                    live.pid = None;
                    live.running = false;
                    live.updated = now_ms();
                }
            }
        }
        known
    });
    Ok(json!({ "ok": true, "known": known }))
}

// ---------------- Claude Code ----------------

fn claude_root() -> PathBuf {
    home().join(".claude").join("projects")
}

fn find_claude_exe() -> Option<PathBuf> {
    let mut candidates: Vec<PathBuf> = Vec::new();
    if cfg!(windows) {
        let userprofile = std::env::var("USERPROFILE").unwrap_or_default();
        let appdata = std::env::var("APPDATA").unwrap_or_default();
        candidates.push(PathBuf::from(format!(r"{userprofile}\.local\bin\claude.exe")));
        candidates.push(PathBuf::from(format!(
            r"{appdata}\npm\node_modules\@anthropic-ai\claude-code\bin\claude.exe"
        )));
        candidates.push(PathBuf::from(format!(r"{appdata}\npm\claude.cmd")));
    } else {
        let h = home();
        candidates.push(h.join(".local/bin/claude"));
        candidates.push(PathBuf::from("/opt/homebrew/bin/claude"));
        candidates.push(PathBuf::from("/usr/local/bin/claude"));
    }
    for c in candidates {
        if c.is_file() {
            return Some(c);
        }
    }
    let path = std::env::var("PATH").unwrap_or_default();
    for dir in std::env::split_paths(&path) {
        #[cfg(windows)]
        for name in ["claude.exe", "claude.cmd"] {
            let p = dir.join(name);
            if p.is_file() {
                return Some(p);
            }
        }
        #[cfg(not(windows))]
        {
            let p = dir.join("claude");
            if p.is_file() {
                return Some(p);
            }
        }
    }
    None
}

fn claude_cwd_from_file(path: &Path) -> Option<String> {
    let f = File::open(path).ok()?;
    for line in BufReader::new(f).lines().take(40).flatten() {
        let Ok(v) = serde_json::from_str::<Value>(&line) else { continue };
        if let Some(cwd) = v.get("cwd").and_then(|x| x.as_str()).filter(|s| !s.trim().is_empty()) {
            return Some(cwd.to_string());
        }
    }
    None
}

fn find_claude_file(id: &str) -> Option<PathBuf> {
    let projects = fs::read_dir(claude_root()).ok()?;
    for proj in projects.flatten() {
        let Ok(files) = fs::read_dir(proj.path()) else { continue };
        for f in files.flatten() {
            let p = f.path();
            if p.extension().and_then(|x| x.to_str()) == Some("jsonl")
                && p.file_stem().and_then(|x| x.to_str()) == Some(id)
            {
                return Some(p);
            }
        }
    }
    None
}

pub fn claude_sessions() -> Vec<Value> {
    let mut out = Vec::new();
    let mut seen = std::collections::HashSet::new();
    if let Ok(projects) = fs::read_dir(claude_root()) {
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
                if id.is_empty() || !seen.insert(id.clone()) {
                    continue;
                }
                let live = get_live(&id);
                let running = live.as_ref().map(|l| l.running).unwrap_or(false);
                let updated = mtime_ms(&path).max(live.as_ref().map(|l| l.updated).unwrap_or(0));
                let cwd = claude_cwd_from_file(&path)
                    .or_else(|| live.as_ref().map(|l| l.cwd.clone()).filter(|s| !s.is_empty()))
                    .unwrap_or_else(|| dir.file_name().and_then(|x| x.to_str()).unwrap_or("").to_string());
                let title = title_from_messages(&path, "message")
                    .or_else(|| live.as_ref().map(|l| l.title.clone()).filter(|t| !t.is_empty()))
                    .unwrap_or_else(|| id.chars().take(8).collect());
                out.push(json!({
                    "id": id,
                    "title": title,
                    "cwd": cwd,
                    "updated": updated,
                    "running": running,
                }));
            }
        }
    }
    if let Ok(map) = live_map().lock() {
        for live in map.values() {
            if live.agent == "claude" && !seen.contains(&live.id) {
                out.push(json!({
                    "id": live.id,
                    "title": if live.title.is_empty() { "新 Claude 会话" } else { &live.title },
                    "cwd": live.cwd,
                    "updated": live.updated,
                    "running": live.running,
                }));
            }
        }
    }
    out.sort_by(|a, b| {
        b.get("updated").and_then(|x| x.as_u64()).unwrap_or(0)
            .cmp(&a.get("updated").and_then(|x| x.as_u64()).unwrap_or(0))
    });
    out.truncate(300);
    out
}

fn read_claude_disk_messages(id: &str) -> Vec<Value> {
    let Some(path) = find_claude_file(id) else { return Vec::new() };
    let base_ts = mtime_ms(&path).max(1_000_000);
    let Ok(f) = File::open(&path) else { return Vec::new() };
    let mut data = Vec::new();
    let mut prev_ts = 0u64;
    for (idx, line) in BufReader::new(f).lines().flatten().enumerate() {
        let Ok(v) = serde_json::from_str::<Value>(&line) else { continue };
        let step_ts = v
            .get("timestamp")
            .or_else(|| v.get("created_at"))
            .and_then(Value::as_str)
            .and_then(parse_iso8601_ms)
            .unwrap_or_else(|| if prev_ts > 0 { prev_ts + 1 } else { base_ts + idx as u64 });
        let role = v.get("type").and_then(|x| x.as_str()).unwrap_or("");
        if role != "user" && role != "assistant" {
            continue;
        }
        let msg = v.get("message").cloned().unwrap_or(Value::Null);
        let (raw_text, blocks) = content_blocks(&msg);
        if (raw_text.trim().is_empty() && blocks.is_empty()) || (role == "user" && is_noise(&raw_text)) {
            continue;
        }
        let text = if role == "user" {
            extract_clean_user_prompt(&raw_text)
        } else {
            raw_text
        };
        let msg_id = v
            .get("uuid")
            .and_then(|x| x.as_str())
            .map(str::to_string)
            .unwrap_or_else(|| format!("{id}-{idx}"));
        let started_ts = if role == "assistant" && prev_ts > 0 && step_ts >= prev_ts && step_ts - prev_ts <= 600_000 {
            prev_ts
        } else {
            step_ts
        };
        prev_ts = step_ts;
        data.push(if role == "user" {
            json!({ "id": msg_id, "type": "user", "text": text, "time": { "created": step_ts } })
        } else {
            let has_tool = blocks.iter().any(|b| b.get("type").and_then(Value::as_str) == Some("tool"));
            let content = if blocks.is_empty() {
                vec![json!({ "type": "text", "text": text })]
            } else {
                blocks
            };
            let completed_ts = if has_tool { 0 } else { step_ts };
            json!({
                "id": msg_id,
                "type": "assistant",
                "content": content,
                "time": { "created": started_ts, "completed": completed_ts }
            })
        });
    }
    data
}

pub fn claude_messages(id: &str, cursor: &str) -> Result<Value, String> {
    let real_id = resolve_session_id(id);
    let mut data = read_claude_disk_messages(&real_id);
    if data.is_empty() && find_claude_file(&real_id).is_none() && get_live(&real_id).is_none() {
        return Err(format!("claude session {id} not found"));
    }
    append_live_turn(&real_id, &mut data);
    Ok(paginate_messages(data, cursor))
}


pub fn claude_create(_cwd: &str, _text: &str, _files: Option<&Value>) -> Result<Value, String> {
    Err("Runtime disabled. Subscription models must use their official CLI; only Codex is currently enabled.".into())
}

pub fn claude_prompt(_id: &str, _text: &str, _cwd: Option<&str>, _files: Option<&Value>) -> Result<Value, String> {
    Err("Runtime disabled. Subscription models must use their official CLI; only Codex is currently enabled.".into())
}

pub fn claude_interrupt(id: &str) -> Result<Value, String> {
    let real_id = resolve_session_id(id);
    if let Ok(mut map) = live_map().lock() {
        for key in [real_id.as_str(), id] {
            if let Some(live) = map.get_mut(key) {
                if let Some(pid) = live.pid.take() {
                    kill_pid(pid);
                }
                live.running = false;
                live.updated = now_ms();
            }
        }
    }
    Ok(json!({ "ok": true }))
}

// ---------------- Remote Workspace Inspector (Tree / Read / Upload / Git Diff) ----------------

fn is_ignored_dir(name: &str) -> bool {
    matches!(
        name,
        ".git"
            | "node_modules"
            | "target"
            | ".gradle"
            | "build"
            | ".next"
            | "dist"
            | ".pnpm"
            | ".pnpm-store"
            | "__pycache__"
            | ".venv"
    )
}

fn read_dir_entries(dir: &Path, depth: usize, max_items: &mut usize) -> Vec<Value> {
    if depth == 0 || *max_items == 0 {
        return Vec::new();
    }
    let Ok(rd) = fs::read_dir(dir) else {
        return Vec::new();
    };
    let mut items: Vec<(bool, String, PathBuf, u64, u64)> = Vec::new();
    for entry in rd.flatten() {
        let p = entry.path();
        let name = entry.file_name().to_string_lossy().to_string();
        if name.is_empty() {
            continue;
        }
        let is_dir = p.is_dir();
        if is_dir && is_ignored_dir(&name) {
            continue;
        }
        if crate::roots::is_secret(Path::new(&name)) {
            continue;
        }
        let size = if is_dir {
            0
        } else {
            fs::metadata(&p).map(|m| m.len()).unwrap_or(0)
        };
        let mtime = mtime_ms(&p);
        items.push((is_dir, name, p, size, mtime));
    }
    // 目录排在前面，按名称排序
    items.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.to_lowercase().cmp(&b.1.to_lowercase())));
    let mut out = Vec::new();
    for (is_dir, name, p, size, mtime) in items {
        if *max_items == 0 {
            break;
        }
        *max_items -= 1;
        let children = if is_dir && depth > 1 {
            read_dir_entries(&p, depth - 1, max_items)
        } else {
            Vec::new()
        };
        out.push(json!({
            "name": name,
            "path": p.to_string_lossy().to_string(),
            "isDir": is_dir,
            "size": size,
            "mtime": mtime,
            "children": children,
        }));
    }
    out
}

pub fn workspace_tree(cwd: &str, depth: usize) -> Result<Value, String> {
    let resolved = resolve_cwd(cwd, None);
    let path = Path::new(&resolved);
    if !path.is_dir() {
        return Err(format!("directory not found: {resolved}"));
    }
    // 只在还没到根目录时给「上一级」，手机端不能借此爬出白名单
    let roots = crate::roots::roots();
    let parent = path
        .parent()
        .filter(|p| crate::roots::within(p, &roots))
        .map(|p| p.to_string_lossy().to_string())
        .unwrap_or_default();
    let mut max_items = 250usize;
    let entries = read_dir_entries(path, depth.clamp(1, 3), &mut max_items);
    Ok(json!({
        "cwd": resolved,
        "parent": parent,
        "entries": entries,
    }))
}

fn guess_mime(path: &Path) -> &'static str {
    match path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_ascii_lowercase()
        .as_str()
    {
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "svg" => "image/svg+xml",
        "pdf" => "application/pdf",
        "json" => "application/json",
        "md" => "text/markdown",
        "rs" | "kt" | "ts" | "tsx" | "js" | "py" | "toml" | "yaml" | "yml" | "txt" | "ps1" | "sh" | "html" | "css" => "text/plain",
        _ => "application/octet-stream",
    }
}

pub fn workspace_read(file_path: &str) -> Result<Value, String> {
    let trimmed = file_path.trim();
    if trimmed.is_empty() {
        return Err("path required".into());
    }
    let checked = crate::roots::check_read(Path::new(trimmed))?;
    let p = checked.as_path();
    if !p.is_file() {
        return Err(format!("file not found: {trimmed}"));
    }
    let meta = fs::metadata(p).map_err(|e| e.to_string())?;
    let size = meta.len();
    if size > 20 * 1024 * 1024 {
        return Err(format!("file too large ({size} bytes > 20MB)"));
    }
    let bytes = fs::read(p).map_err(|e| e.to_string())?;
    let mime = guess_mime(p);
    let name = p
        .file_name()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| trimmed.to_string());
    let is_binary = bytes.iter().take(4096).any(|&b| b == 0) || mime.starts_with("image/");
    if !is_binary {
        if let Ok(s) = String::from_utf8(bytes.clone()) {
            let max_chars = 120_000;
            let truncated = s.len() > max_chars;
            let text = if truncated {
                s.chars().take(max_chars).collect::<String>()
            } else {
                s
            };
            return Ok(json!({
                "path": trimmed,
                "name": name,
                "size": size,
                "mime": mime,
                "isText": true,
                "truncated": truncated,
                "text": text,
            }));
        }
    }
    Ok(json!({
        "path": trimmed,
        "name": name,
        "size": size,
        "mime": mime,
        "isText": false,
        "base64": b64_encode(&bytes),
    }))
}

pub fn workspace_upload(cwd: &str, files: Option<&Value>, into_subdir: bool) -> Result<Value, String> {
    let saved = save_uploaded_files(cwd, files, into_subdir);
    let list: Vec<Value> = saved
        .into_iter()
        .map(|(path, name, mime, size)| {
            json!({
                "path": path,
                "name": name,
                "mime": mime,
                "size": size,
            })
        })
        .collect();
    Ok(json!({
        "ok": true,
        "saved": list,
    }))
}

fn run_git_cmd(cwd: &str, args: &[&str]) -> Option<String> {
    let mut cmd = Command::new("git");
    cmd.args(args).current_dir(cwd);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000);
    }
    let out = cmd.output().ok()?;
    if !out.status.success() && out.stdout.is_empty() {
        return None;
    }
    Some(String::from_utf8_lossy(&out.stdout).to_string())
}

pub fn workspace_git(cwd: &str) -> Result<Value, String> {
    let resolved = resolve_cwd(cwd, None);
    if !Path::new(&resolved).is_dir() {
        return Err(format!("directory not found: {resolved}"));
    }
    let branch = run_git_cmd(&resolved, &["rev-parse", "--abbrev-ref", "HEAD"])
        .map(|s| s.trim().to_string())
        .unwrap_or_default();
    let status_raw = run_git_cmd(&resolved, &["status", "--porcelain", "-uall"]).unwrap_or_default();
    let mut files = Vec::new();
    for line in status_raw.lines() {
        if line.len() < 4 {
            continue;
        }
        let status = line[..2].trim().to_string();
        let rel_path = line[3..].trim().trim_matches('"').to_string();
        let abs_path = Path::new(&resolved).join(&rel_path).to_string_lossy().to_string();
        files.push(json!({
            "status": status,
            "path": rel_path,
            "absPath": abs_path,
        }));
    }
    let diff_raw = run_git_cmd(&resolved, &["diff", "HEAD"])
        .filter(|s| !s.trim().is_empty())
        .or_else(|| run_git_cmd(&resolved, &["diff"]))
        .unwrap_or_default();
    let max_diff = 80_000;
    let diff = if diff_raw.len() > max_diff {
        let mut s: String = diff_raw.chars().take(max_diff).collect();
        s.push_str("\n... (diff 已截断)");
        s
    } else {
        diff_raw
    };
    Ok(json!({
        "cwd": resolved,
        "branch": branch,
        "files": files,
        "diff": diff,
    }))
}

// ---------------- Antigravity (agy.exe + ~/.gemini/antigravity-cli/brain) ----------------

fn agy_bin() -> String {
    if cfg!(windows) {
        let local = std::env::var("LOCALAPPDATA").unwrap_or_default();
        let p = PathBuf::from(&local).join("agy").join("bin").join("agy.exe");
        if p.is_file() {
            return p.to_string_lossy().to_string();
        }
        let p2 = home().join("AppData").join("Local").join("agy").join("bin").join("agy.exe");
        if p2.is_file() {
            return p2.to_string_lossy().to_string();
        }
    }
    "agy".into()
}

fn agy_brain_dir() -> PathBuf {
    home().join(".gemini").join("antigravity-cli").join("brain")
}

fn find_agy_transcript(id: &str) -> Option<PathBuf> {
    let p = agy_brain_dir()
        .join(id)
        .join(".system_generated")
        .join("logs")
        .join("transcript.jsonl");
    if p.is_file() {
        Some(p)
    } else {
        None
    }
}

fn extract_agy_user_request(raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    if let Some(s) = trimmed.find("<USER_REQUEST>") {
        let after = &trimmed[s + 14..];
        let inner = if let Some(e) = after.find("</USER_REQUEST>") {
            after[..e].trim()
        } else {
            after.trim()
        };
        if !inner.is_empty() {
            return Some(extract_clean_user_prompt(inner));
        }
    }
    if trimmed.starts_with("The following is a <SYSTEM_MESSAGE>")
        || trimmed.starts_with("<CONTEXT_SUMMARY>")
        || trimmed.starts_with("<EPHEMERAL_MESSAGE>")
        || trimmed.starts_with("<identity>")
    {
        return None;
    }
    Some(extract_clean_user_prompt(trimmed))
}

fn extract_agy_cwd(raw: &str) -> Option<String> {
    for line in raw.lines() {
        if let Some(rest) = line.trim().strip_prefix("Command Working Directory:") {
            let dir = rest.trim();
            if !dir.is_empty() {
                return Some(dir.to_string());
            }
        }
    }
    None
}

fn read_agy_session_summary(path: &Path, id: &str) -> Option<(String, String)> {
    let f = File::open(path).ok()?;
    let mut title = String::new();
    let mut cwd = String::new();
    let mut has_any_step = false;
    for (line_idx, line) in BufReader::new(f).lines().map_while(Result::ok).enumerate() {
        let Ok(v) = serde_json::from_str::<Value>(&line) else {
            continue;
        };
        let step_type = v.get("type").and_then(Value::as_str).unwrap_or("");
        if step_type == "USER_INPUT" {
            let raw = v.get("content").and_then(Value::as_str).unwrap_or("");
            if cwd.is_empty() {
                if let Some(c) = extract_agy_cwd(raw) {
                    cwd = c;
                }
            }
            if let Some(clean) = extract_agy_user_request(raw) {
                has_any_step = true;
                if title.is_empty() {
                    title = trim_title(&clean, 60);
                }
            }
        } else if step_type == "PLANNER_RESPONSE" {
            has_any_step = true;
        }
        if !title.is_empty() && !cwd.is_empty() {
            break;
        }
        if line_idx > 120 && has_any_step {
            break;
        }
    }
    if !has_any_step && get_live(id).is_none() {
        return None;
    }
    if title.is_empty() {
        title = format!("Antigravity {}", &id[..id.len().min(8)]);
    }
    if cwd.is_empty() {
        cwd = default_cwd();
    }
    Some((title, cwd))
}

fn read_agy_disk_messages(id: &str) -> (String, String, u64, Vec<Value>) {
    let mut title = String::new();
    let mut cwd = default_cwd();
    let mut found_cwd = false;
    let Some(compact_path) = find_agy_transcript(id) else {
        return (title, cwd, 0, Vec::new());
    };
    let full_path = compact_path.with_file_name("transcript_full.jsonl");
    let path = if full_path.is_file() && mtime_ms(&full_path) >= mtime_ms(&compact_path) {
        full_path
    } else {
        compact_path
    };
    let updated = mtime_ms(&path);
    let Ok(f) = File::open(&path) else {
        return (title, cwd, updated, Vec::new());
    };
    let mut out = Vec::new();
    let mut idx = 0usize;
    let mut prev_ts = 0u64;
    let mut last_step_unfinished = false;
    for line in BufReader::new(f).lines().map_while(Result::ok) {
        if !line.contains("\"USER_INPUT\"") && !line.contains("\"PLANNER_RESPONSE\"") {
            continue;
        }
        let Ok(v) = serde_json::from_str::<Value>(&line) else {
            continue;
        };
        let step_ts = v
            .get("created_at")
            .or_else(|| v.get("timestamp"))
            .and_then(Value::as_str)
            .and_then(parse_iso8601_ms)
            .unwrap_or_else(|| if prev_ts > 0 { prev_ts + 1 } else { updated.max(1_000_000) + idx as u64 });
        let step_type = v.get("type").and_then(Value::as_str).unwrap_or("");
        if step_type == "USER_INPUT" {
            let raw = v.get("content").and_then(Value::as_str).unwrap_or("");
            if !found_cwd {
                if let Some(c) = extract_agy_cwd(raw) {
                    cwd = c;
                    found_cwd = true;
                }
            }
            if let Some(clean) = extract_agy_user_request(raw) {
                if title.is_empty() {
                    title = trim_title(&clean, 60);
                }
                idx += 1;
                prev_ts = step_ts;
                last_step_unfinished = true;
                out.push(json!({
                    "id": format!("{id}-u-{idx}"),
                    "type": "user",
                    "text": clean,
                    "time": { "created": step_ts }
                }));
            } else if raw.trim_start().starts_with("The following is a <SYSTEM_MESSAGE>") {
                last_step_unfinished = true;
            }
        } else if step_type == "PLANNER_RESPONSE" {
            let raw_content = v.get("content").and_then(Value::as_str).unwrap_or("").trim();
            let has_inline_sys_msg = raw_content.contains("The following is a <SYSTEM_MESSAGE>");
            let content = raw_content
                .split("The following is a <SYSTEM_MESSAGE>")
                .next()
                .unwrap_or("")
                .trim()
                .trim_end_matches("!]")
                .trim();
            let thinking = v.get("thinking").and_then(Value::as_str).unwrap_or("").trim();
            let status_str = v.get("status").and_then(Value::as_str).unwrap_or("DONE");
            let mut has_active_tools = has_inline_sys_msg;
            let mut blocks = Vec::new();
            if !thinking.is_empty() {
                blocks.push(json!({ "type": "reasoning", "text": thinking }));
            }
            if let Some(tcalls) = v.get("tool_calls").and_then(Value::as_array) {
                for tc in tcalls {
                    let name = tc.get("name").and_then(Value::as_str).unwrap_or("tool");
                    if name != "ask_question" {
                        has_active_tools = true;
                    }
                    let args = tc.get("args").cloned().unwrap_or(Value::Null);
                    let mut cmd_str = String::new();
                    let mut file_str = String::new();
                    let mut diff_str = String::new();
                    if let Some(c) = args.get("CommandLine").and_then(Value::as_str) {
                        cmd_str = c.trim_matches('"').to_string();
                    }
                    if let Some(p) = args.get("Path").and_then(Value::as_str) {
                        file_str = p.trim_matches('"').replace("\\\\", "\\");
                    }
                    if let (Some(old), Some(new)) = (
                        args.get("Target").and_then(Value::as_str),
                        args.get("Replacement").and_then(Value::as_str),
                    ) {
                        let mut d = String::new();
                        for l in old.lines().take(20) {
                            d.push_str("- ");
                            d.push_str(l);
                            d.push('\n');
                        }
                        for l in new.lines().take(20) {
                            d.push_str("+ ");
                            d.push_str(l);
                            d.push('\n');
                        }
                        diff_str = d;
                    }
                    let t_title = if !file_str.is_empty() {
                        let short = Path::new(&file_str)
                            .file_name()
                            .map(|s| s.to_string_lossy().to_string())
                            .unwrap_or_else(|| file_str.clone());
                        format!("{name}: {short}")
                    } else if let Some(s) = args.get("toolSummary").and_then(Value::as_str) {
                        format!("{name}: {}", s.trim_matches('"'))
                    } else {
                        format!("调用工具: {name}")
                    };
                    blocks.push(json!({
                        "type": "reasoning",
                        "text": format!("🔧 正在调用工具：{t_title}")
                    }));
                    blocks.push(json!({
                        "type": "tool",
                        "name": name,
                        "title": t_title,
                        "status": "completed",
                        "command": cmd_str,
                        "file": file_str,
                        "diff": diff_str,
                    }));
                }
            }
            let step_unfinished = has_active_tools || status_str != "DONE";
            last_step_unfinished = step_unfinished;
            if !content.is_empty() {
                if step_unfinished {
                    blocks.push(json!({ "type": "reasoning", "text": content }));
                } else {
                    blocks.push(json!({ "type": "text", "text": content }));
                }
            }
            if !blocks.is_empty() {
                idx += 1;
                let started_ts = if prev_ts > 0 && step_ts >= prev_ts && step_ts - prev_ts <= 600_000 {
                    prev_ts
                } else {
                    step_ts
                };
                prev_ts = step_ts;
                let completed_ts = if step_unfinished { 0 } else { step_ts };
                out.push(json!({
                    "id": format!("{id}-a-{idx}"),
                    "type": "assistant",
                    "content": blocks,
                    "time": { "created": started_ts, "completed": completed_ts }
                }));
            }
        }
    }
    let is_live_running = get_live(id).map(|l| l.running).unwrap_or(false);
    let recently_active = is_live_running || now_ms().saturating_sub(updated) < 600_000;
    if last_step_unfinished && recently_active {
        let last_is_user = out
            .last()
            .and_then(|m| m.get("type"))
            .and_then(Value::as_str)
            == Some("user");
        if last_is_user {
            idx += 1;
            out.push(json!({
                "id": format!("{id}-a-{idx}"),
                "type": "assistant",
                "content": [{ "type": "reasoning", "text": "正在分析上下文与思考方案…" }],
                "time": { "created": prev_ts.max(updated), "completed": 0 }
            }));
        } else if let Some(last) = out.last_mut() {
            last["time"]["completed"] = json!(0);
        }
    } else if last_step_unfinished {
        if let Some(last) = out.last_mut() {
            if last.get("type").and_then(Value::as_str) == Some("assistant") {
                last["time"]["completed"] = json!(prev_ts.max(updated).max(1));
            }
        }
    }
    if title.is_empty() {
        title = format!("Antigravity {}", &id[..id.len().min(8)]);
    }
    (title, cwd, updated, out)
}

pub fn antigravity() -> Value {
    let brain = agy_brain_dir();
    let mut sessions = Vec::new();
    let mut seen = std::collections::HashSet::new();

    if let Ok(rd) = fs::read_dir(&brain) {
        let mut dirs: Vec<(u64, String, PathBuf)> = rd
            .flatten()
            .filter_map(|e| {
                let p = e.path();
                let t = p.join(".system_generated").join("logs").join("transcript.jsonl");
                if t.is_file() {
                    let id = e.file_name().to_string_lossy().to_string();
                    Some((mtime_ms(&t), id, t))
                } else {
                    None
                }
            })
            .collect();
        dirs.sort_by(|a, b| b.0.cmp(&a.0));
        for (updated, id, tpath) in dirs.into_iter().take(40) {
            let Some((title, cwd)) = read_agy_session_summary(&tpath, &id) else {
                continue;
            };
            let running = get_live(&id).map(|l| l.running).unwrap_or(false);
            seen.insert(id.clone());
            sessions.push(json!({
                "id": id,
                "title": title,
                "cwd": cwd,
                "updated": updated,
                "running": running,
            }));
        }
    }

    if let Ok(map) = live_map().lock() {
        for live in map.values() {
            if live.agent == "antigravity" && !seen.contains(&live.id) {
                sessions.push(json!({
                    "id": live.id,
                    "title": if live.title.is_empty() { "Antigravity 会话".into() } else { live.title.clone() },
                    "cwd": live.cwd,
                    "updated": live.updated,
                    "running": live.running,
                }));
            }
        }
    }

    sessions.sort_by(|a, b| {
        let ua = a.get("updated").and_then(Value::as_u64).unwrap_or(0);
        let ub = b.get("updated").and_then(Value::as_u64).unwrap_or(0);
        ub.cmp(&ua)
    });

    json!({
        "id": "antigravity",
        "name": "Antigravity",
        "note": "",
        "sessions": sessions,
    })
}

pub fn antigravity_messages(id: &str, cursor: &str) -> Result<Value, String> {
    let real_id = resolve_session_id(id);
    let (_, _, _, mut data) = read_agy_disk_messages(&real_id);
    if data.is_empty() && find_agy_transcript(&real_id).is_none() && get_live(&real_id).is_none() {
        return Err(format!("antigravity session {id} not found"));
    }
    append_live_turn(&real_id, &mut data);
    Ok(paginate_messages(data, cursor))
}


pub fn antigravity_create(
    _cwd: &str,
    _text: &str,
    _files: Option<&Value>,
) -> Result<Value, String> {
    Err("Runtime disabled. Subscription models must use their official CLI; only Codex is currently enabled.".into())
}

pub fn antigravity_prompt(
    _id: &str,
    _text: &str,
    _cwd: Option<&str>,
    _files: Option<&Value>,
) -> Result<Value, String> {
    Err("Runtime disabled. Subscription models must use their official CLI; only Codex is currently enabled.".into())
}

pub fn antigravity_interrupt(id: &str) -> Result<Value, String> {
    let real_id = resolve_session_id(id);
    if let Ok(mut map) = live_map().lock() {
        for key in [real_id.as_str(), id] {
            if let Some(live) = map.get_mut(key) {
                if let Some(pid) = live.pid.take() {
                    kill_pid(pid);
                }
                live.running = false;
                live.updated = now_ms();
            }
        }
    }
    Ok(json!({ "ok": true }))
}

// ---------------- Interactive PC CLI Console Echo / Takeover ----------------




fn try_echo_to_interactive_cli(_agent: &str, _sid: &str, _cwd: &str, _clean_user: &str, _full_prompt: &str, _base_count: usize) -> bool {
    // Only isolated, verified headless CLI sessions may execute subscription requests.
    false
}

// ---------------- Remote Desktop Screen & Input (Cindy Remote Desktop Alignment — Native Win32) ----------------

#[cfg(windows)]
mod win32_desktop {
    #[repr(C)]
    #[derive(Default, Clone, Copy)]
    pub struct POINT {
        pub x: i32,
        pub y: i32,
    }

    #[repr(C)]
    #[derive(Default, Clone, Copy)]
    pub struct BITMAPINFOHEADER {
        pub bi_size: u32,
        pub bi_width: i32,
        pub bi_height: i32,
        pub bi_planes: u16,
        pub bi_bit_count: u16,
        pub bi_compression: u32,
        pub bi_size_image: u32,
        pub bi_x_pels_per_meter: i32,
        pub bi_y_pels_per_meter: i32,
        pub bi_clr_used: u32,
        pub bi_clr_important: u32,
    }

    #[link(name = "user32")]
    unsafe extern "system" {
        pub fn SetProcessDPIAware() -> i32;
        pub fn GetSystemMetrics(n_index: i32) -> i32;
        pub fn GetCursorPos(lp_point: *mut POINT) -> i32;
        pub fn SetCursorPos(x: i32, y: i32) -> i32;
        pub fn mouse_event(dw_flags: u32, dx: u32, dy: u32, dw_data: i32, dw_extra_info: usize);
        pub fn keybd_event(b_vk: u8, b_scan: u8, dw_flags: u32, dw_extra_info: usize);
        pub fn GetDC(hwnd: isize) -> isize;
        pub fn ReleaseDC(hwnd: isize, hdc: isize) -> i32;
    }

    #[link(name = "gdi32")]
    unsafe extern "system" {
        pub fn CreateCompatibleDC(hdc: isize) -> isize;
        pub fn CreateCompatibleBitmap(hdc: isize, cx: i32, cy: i32) -> isize;
        pub fn SelectObject(hdc: isize, h: isize) -> isize;
        pub fn SetStretchBltMode(hdc: isize, mode: i32) -> i32;
        pub fn StretchBlt(
            hdc_dest: isize,
            x_dest: i32,
            y_dest: i32,
            w_dest: i32,
            h_dest: i32,
            hdc_src: isize,
            x_src: i32,
            y_src: i32,
            w_src: i32,
            h_src: i32,
            rop: u32,
        ) -> i32;
        pub fn GetDIBits(
            hdc: isize,
            hbm: isize,
            start: u32,
            c_lines: u32,
            lpv_bits: *mut u8,
            lpbmi: *mut BITMAPINFOHEADER,
            usage: u32,
        ) -> i32;
        pub fn DeleteObject(ho: isize) -> i32;
        pub fn DeleteDC(hdc: isize) -> i32;
    }
}

#[derive(Clone, Debug, Default)]
struct TakeoverLeaseState {
    holder: String,
    expires_at: u64,
}

static TAKEOVER_LEASE: OnceLock<Mutex<TakeoverLeaseState>> = OnceLock::new();

fn takeover_lease() -> &'static Mutex<TakeoverLeaseState> {
    TAKEOVER_LEASE.get_or_init(|| Mutex::new(TakeoverLeaseState::default()))
}

fn fnv1a_hex(bytes: &[u8]) -> String {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for &b in bytes.iter().step_by(4) {
        hash ^= b as u64;
        hash = hash.wrapping_mul(0x0100_0000_01b3);
    }
    format!("{hash:016x}")
}

/// 远程桌面人工接管租约（Takeover Lease Fencing，对标 Rakazo P1）：
/// 当用户在手机端或 Cindy 桌面端接管控制时，自动持有租约（默认 60s TTL），阻止后台 Agent 并发点击鼠标或抢焦点。
pub fn desktop_lease(body: &Value) -> Value {
    let action = body
        .get("action")
        .and_then(Value::as_str)
        .unwrap_or("status")
        .trim()
        .to_lowercase();
    let holder = body
        .get("holder")
        .and_then(Value::as_str)
        .unwrap_or("human")
        .trim();
    let ttl_ms = body
        .get("ttlMs")
        .and_then(Value::as_u64)
        .unwrap_or(60_000)
        .clamp(5_000, 600_000);
    let now = now_ms();
    let mut g = takeover_lease().lock().unwrap_or_else(|e| e.into_inner());
    match action.as_str() {
        "acquire" | "renew" => {
            g.holder = holder.to_string();
            g.expires_at = now + ttl_ms;
        }
        "release" => {
            g.holder.clear();
            g.expires_at = 0;
        }
        _ => {}
    }
    let active = g.expires_at > now && !g.holder.is_empty();
    json!({
        "ok": true,
        "active": active,
        "holder": if active { g.holder.clone() } else { String::new() },
        "expiresAt": if active { g.expires_at } else { 0 },
        "remainingMs": if active { g.expires_at.saturating_sub(now) } else { 0 },
    })
}

pub fn desktop_frame(width_target: u32) -> Result<Value, String> {
    desktop_frame_with_hash(width_target, None)
}

pub fn desktop_frame_with_hash(
    width_target: u32,
    last_hash: Option<&str>,
) -> Result<Value, String> {
    #[cfg(windows)]
    unsafe {
        use win32_desktop::*;
        SetProcessDPIAware();
        let w = GetSystemMetrics(0).max(1);
        let h = GetSystemMetrics(1).max(1);
        let mut pt = POINT::default();
        GetCursorPos(&mut pt);

        // 对齐到 4 像素宽度，使 16-bit RGB555 BMP 每行字节数为 4 的整数倍
        let raw_tw = (width_target.clamp(480, 960) as i32).min(w);
        let tw = ((raw_tw / 4) * 4).max(4);
        let th = ((h as f64 * (tw as f64 / w as f64)).round() as i32).max(1);

        let hdc_screen = GetDC(0);
        if hdc_screen == 0 {
            return Err("GetDC(0) failed".into());
        }
        let hdc_mem = CreateCompatibleDC(hdc_screen);
        let hbmp = CreateCompatibleBitmap(hdc_screen, tw, th);
        let old = SelectObject(hdc_mem, hbmp);
        SetStretchBltMode(hdc_mem, 4); // HALFTONE
        StretchBlt(hdc_mem, 0, 0, tw, th, hdc_screen, 0, 0, w, h, 0x00CC0020);

        let pixel_bytes = (tw * th * 2) as usize;
        let mut pixels = vec![0u8; pixel_bytes];
        let mut bmi = BITMAPINFOHEADER {
            bi_size: std::mem::size_of::<BITMAPINFOHEADER>() as u32,
            bi_width: tw,
            bi_height: -th, // top-down DIB
            bi_planes: 1,
            bi_bit_count: 16, // RGB555
            bi_compression: 0, // BI_RGB
            bi_size_image: pixel_bytes as u32,
            ..Default::default()
        };
        GetDIBits(
            hdc_mem,
            hbmp,
            0,
            th as u32,
            pixels.as_mut_ptr(),
            &mut bmi,
            0,
        );

        SelectObject(hdc_mem, old);
        DeleteObject(hbmp);
        DeleteDC(hdc_mem);
        ReleaseDC(0, hdc_screen);

        // 在缩放后的像素缓冲区上绘制红色鼠标准星圆点
        let cx = ((pt.x as i64 * tw as i64) / w as i64) as i32;
        let cy = ((pt.y as i64 * th as i64) / h as i64) as i32;
        let red_555: u16 = 0x7C00;
        let white_555: u16 = 0x7FFF;
        for dy in -6i32..=6 {
            for dx in -6i32..=6 {
                let d2 = dx * dx + dy * dy;
                if d2 <= 36 {
                    let px = cx + dx;
                    let py = cy + dy;
                    if px >= 0 && px < tw && py >= 0 && py < th {
                        let off = ((py * tw + px) * 2) as usize;
                        let color = if d2 >= 20 { white_555 } else { red_555 };
                        let bytes = color.to_le_bytes();
                        pixels[off] = bytes[0];
                        pixels[off + 1] = bytes[1];
                    }
                }
            }
        }

        let frame_hash = fnv1a_hex(&pixels);
        let lease = desktop_lease(&json!({ "action": "status" }));
        if let Some(prev) = last_hash.map(str::trim).filter(|s| !s.is_empty()) {
            if prev == frame_hash {
                return Ok(json!({
                    "unchanged": true,
                    "frameHash": frame_hash,
                    "width": w,
                    "height": h,
                    "imgWidth": tw,
                    "imgHeight": th,
                    "cursorX": pt.x,
                    "cursorY": pt.y,
                    "lease": lease,
                    "at": now_ms(),
                }));
            }
        }

        // 组装 54 字节标准 BMP 文件头 + 像素数据（Android BitmapFactory 原生极速解码）
        let file_size = (54 + pixel_bytes) as u32;
        let mut bmp = Vec::with_capacity(file_size as usize);
        bmp.extend_from_slice(b"BM");
        bmp.extend_from_slice(&file_size.to_le_bytes());
        bmp.extend_from_slice(&0u32.to_le_bytes());
        bmp.extend_from_slice(&54u32.to_le_bytes());
        bmp.extend_from_slice(&bmi.bi_size.to_le_bytes());
        bmp.extend_from_slice(&bmi.bi_width.to_le_bytes());
        bmp.extend_from_slice(&bmi.bi_height.to_le_bytes());
        bmp.extend_from_slice(&bmi.bi_planes.to_le_bytes());
        bmp.extend_from_slice(&bmi.bi_bit_count.to_le_bytes());
        bmp.extend_from_slice(&bmi.bi_compression.to_le_bytes());
        bmp.extend_from_slice(&bmi.bi_size_image.to_le_bytes());
        bmp.extend_from_slice(&bmi.bi_x_pels_per_meter.to_le_bytes());
        bmp.extend_from_slice(&bmi.bi_y_pels_per_meter.to_le_bytes());
        bmp.extend_from_slice(&bmi.bi_clr_used.to_le_bytes());
        bmp.extend_from_slice(&bmi.bi_clr_important.to_le_bytes());
        bmp.extend_from_slice(&pixels);

        let b64 = b64_encode(&bmp);
        return Ok(json!({
            "unchanged": false,
            "frameHash": frame_hash,
            "width": w,
            "height": h,
            "imgWidth": tw,
            "imgHeight": th,
            "cursorX": pt.x,
            "cursorY": pt.y,
            "jpegBase64": b64,
            "lease": lease,
            "at": now_ms(),
        }));
    }
    #[cfg(target_os = "macos")]
    {
        let tw = width_target.clamp(480, 1280);
        let tmp_path = std::env::temp_dir().join("vibelink-macos-screen.jpg");
        let tmp_str = tmp_path.to_string_lossy().to_string();
        let status = Command::new("screencapture")
            .args(["-x", "-t", "jpg", &tmp_str])
            .status()
            .map_err(|e| format!("screencapture failed: {e}"))?;
        if !status.success() {
            return Err("screencapture exited with non-zero status".into());
        }
        let _ = Command::new("sips")
            .args(["-Z", &tw.to_string(), &tmp_str])
            .output();
        let bytes = fs::read(&tmp_path).map_err(|e| format!("read screencapture failed: {e}"))?;
        let frame_hash = fnv1a_hex(&bytes);
        let lease = desktop_lease(&json!({ "action": "status" }));
        if let Some(prev) = last_hash.map(str::trim).filter(|s| !s.is_empty()) {
            if prev == frame_hash {
                return Ok(json!({
                    "unchanged": true,
                    "frameHash": frame_hash,
                    "width": 1440,
                    "height": 900,
                    "imgWidth": tw,
                    "imgHeight": (tw * 9) / 16,
                    "cursorX": 0,
                    "cursorY": 0,
                    "lease": lease,
                    "at": now_ms(),
                }));
            }
        }
        return Ok(json!({
            "unchanged": false,
            "frameHash": frame_hash,
            "width": 1440,
            "height": 900,
            "imgWidth": tw,
            "imgHeight": (tw * 9) / 16,
            "cursorX": 0,
            "cursorY": 0,
            "jpegBase64": b64_encode(&bytes),
            "lease": lease,
            "at": now_ms(),
        }));
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        let _ = (width_target, last_hash);
        Err("desktop capture only supported on Windows and macOS".into())
    }
}

pub fn desktop_input(body: &Value) -> Result<Value, String> {
    // Takeover Lease Fencing：如果当前是后台 Agent 请求控制桌面，而人工接管租约处于激活态，则拦截报错；
    // 如果是人工从手机/桌面发起操作，则自动续期人工接管租约 45 秒。
    let actor = body
        .get("actor")
        .and_then(Value::as_str)
        .unwrap_or("human")
        .trim();
    if actor.eq_ignore_ascii_case("agent") {
        let lease = desktop_lease(&json!({ "action": "status" }));
        if lease.get("active").and_then(Value::as_bool).unwrap_or(false) {
            return Err("human takeover lease is active; agent desktop input is fenced".into());
        }
    } else {
        let _ = desktop_lease(&json!({
            "action": "acquire",
            "holder": actor,
            "ttlMs": 45_000
        }));
    }

    #[cfg(windows)]
    unsafe {
        use win32_desktop::*;
        SetProcessDPIAware();
        let kind = body.get("type").and_then(Value::as_str).unwrap_or("click");
        let nx = body.get("x").and_then(Value::as_f64).unwrap_or(0.5).clamp(0.0, 1.0);
        let ny = body.get("y").and_then(Value::as_f64).unwrap_or(0.5).clamp(0.0, 1.0);
        let w = GetSystemMetrics(0).max(1);
        let h = GetSystemMetrics(1).max(1);
        let tx = ((w as f64) * nx).round() as i32;
        let ty = ((h as f64) * ny).round() as i32;

        fn tap_key(vk: u8) {
            unsafe {
                win32_desktop::keybd_event(vk, 0, 0, 0);
                std::thread::sleep(Duration::from_millis(12));
                win32_desktop::keybd_event(vk, 0, 0x0002, 0);
            }
        }
        fn tap_combo(mod_vk: u8, vk: u8) {
            unsafe {
                win32_desktop::keybd_event(mod_vk, 0, 0, 0);
                std::thread::sleep(Duration::from_millis(10));
                win32_desktop::keybd_event(vk, 0, 0, 0);
                std::thread::sleep(Duration::from_millis(12));
                win32_desktop::keybd_event(vk, 0, 0x0002, 0);
                win32_desktop::keybd_event(mod_vk, 0, 0x0002, 0);
            }
        }

        match kind {
            "move" => {
                SetCursorPos(tx, ty);
            }
            "click" => {
                SetCursorPos(tx, ty);
                std::thread::sleep(Duration::from_millis(15));
                mouse_event(0x0002, 0, 0, 0, 0);
                std::thread::sleep(Duration::from_millis(20));
                mouse_event(0x0004, 0, 0, 0, 0);
            }
            "right_click" => {
                SetCursorPos(tx, ty);
                std::thread::sleep(Duration::from_millis(15));
                mouse_event(0x0008, 0, 0, 0, 0);
                std::thread::sleep(Duration::from_millis(20));
                mouse_event(0x0010, 0, 0, 0, 0);
            }
            "double_click" => {
                SetCursorPos(tx, ty);
                std::thread::sleep(Duration::from_millis(15));
                mouse_event(0x0002, 0, 0, 0, 0);
                mouse_event(0x0004, 0, 0, 0, 0);
                std::thread::sleep(Duration::from_millis(45));
                mouse_event(0x0002, 0, 0, 0, 0);
                mouse_event(0x0004, 0, 0, 0, 0);
            }
            "scroll" => {
                let delta = body.get("delta").and_then(Value::as_i64).unwrap_or(-240) as i32;
                mouse_event(0x0800, 0, 0, delta, 0);
            }
            "key" => {
                let k = body.get("key").and_then(Value::as_str).unwrap_or("{ENTER}");
                match k {
                    "{ENTER}" => tap_key(0x0D),
                    "{ESC}" => tap_key(0x1B),
                    "{TAB}" => tap_key(0x09),
                    "{BACKSPACE}" => tap_key(0x08),
                    "^c" => tap_combo(0x11, 0x43),
                    "^v" => tap_combo(0x11, 0x56),
                    "^z" => tap_combo(0x11, 0x5A),
                    "%{TAB}" => tap_combo(0x12, 0x09),
                    "^{ESC}" => tap_key(0x5B),
                    _ => {}
                }
            }
            "text" => {
                let txt = body.get("text").and_then(Value::as_str).unwrap_or("");
                if !txt.is_empty() {
                    let mut cmd = Command::new("clip");
                    cmd.stdin(Stdio::piped());
                    #[cfg(windows)]
                    {
                        use std::os::windows::process::CommandExt;
                        cmd.creation_flags(0x0800_0000);
                    }
                    let mut child = cmd.spawn().map_err(|e| e.to_string())?;
                    if let Some(mut stdin) = child.stdin.take() {
                        use std::io::Write;
                        let _ = stdin.write_all(txt.as_bytes());
                    }
                    let _ = child.wait();
                    std::thread::sleep(Duration::from_millis(25));
                    tap_combo(0x11, 0x56);
                }
            }
            _ => return Err(format!("unknown desktop input type: {kind}")),
        }
        return Ok(json!({ "ok": true }));
    }
    #[cfg(target_os = "macos")]
    {
        let kind = body.get("type").and_then(Value::as_str).unwrap_or("click");
        let nx = body.get("x").and_then(Value::as_f64).unwrap_or(0.5).clamp(0.0, 1.0);
        let ny = body.get("y").and_then(Value::as_f64).unwrap_or(0.5).clamp(0.0, 1.0);
        let tx = (1440.0 * nx).round() as i32;
        let ty = (900.0 * ny).round() as i32;
        match kind {
            "move" | "click" | "right_click" | "double_click" => {
                let flag = match kind {
                    "move" => format!("m:{tx},{ty}"),
                    "right_click" => format!("rc:{tx},{ty}"),
                    "double_click" => format!("dc:{tx},{ty}"),
                    _ => format!("c:{tx},{ty}"),
                };
                let _ = Command::new("cliclick").arg(&flag).output();
            }
            "key" => {
                let k = body.get("key").and_then(Value::as_str).unwrap_or("{ENTER}");
                let script = match k {
                    "{ENTER}" => "tell application \"System Events\" to key code 36",
                    "{ESC}" => "tell application \"System Events\" to key code 53",
                    "{TAB}" => "tell application \"System Events\" to key code 48",
                    "{BACKSPACE}" => "tell application \"System Events\" to key code 51",
                    "^c" => "tell application \"System Events\" to keystroke \"c\" using command down",
                    "^v" => "tell application \"System Events\" to keystroke \"v\" using command down",
                    "^z" => "tell application \"System Events\" to keystroke \"z\" using command down",
                    _ => "tell application \"System Events\" to key code 36",
                };
                let _ = Command::new("osascript").args(["-e", script]).output();
            }
            "text" => {
                let txt = body.get("text").and_then(Value::as_str).unwrap_or("");
                if !txt.is_empty() {
                    if let Ok(mut child) = Command::new("pbcopy").stdin(Stdio::piped()).spawn() {
                        if let Some(mut stdin) = child.stdin.take() {
                            use std::io::Write;
                            let _ = stdin.write_all(txt.as_bytes());
                        }
                        let _ = child.wait();
                        let _ = Command::new("osascript")
                            .args([
                                "-e",
                                "tell application \"System Events\" to keystroke \"v\" using command down",
                            ])
                            .output();
                    }
                }
            }
            _ => {}
        }
        return Ok(json!({ "ok": true }));
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        let _ = body;
        Err("desktop input only supported on Windows and macOS".into())
    }
}

// ---------------- Scheduler & Plugins (Cindy Automation & Plugin Alignment) ----------------

fn schedules_file() -> PathBuf {
    home().join(".vibelink-schedules.json")
}

pub fn scheduler_list() -> Value {
    let p = schedules_file();
    if let Ok(s) = fs::read_to_string(&p) {
        if let Ok(v) = serde_json::from_str::<Value>(&s) {
            if v.is_array() {
                return json!({ "tasks": v });
            }
        }
    }
    let default_tasks = json!([
        {
            "id": "sched-git-review",
            "name": "每日工作区 Git 状态与风险巡检",
            "agent": "antigravity",
            "botId": "bot-pm-agy",
            "cwd": default_cwd(),
            "prompt": "检查当前仓库 git status 与最近改动，汇总潜在问题。",
            "cron": "每天 09:00",
            "enabled": false,
            "lastRunAt": 0,
            "lastStatus": "待命"
        },
        {
            "id": "sched-proxy-health",
            "name": "WProxy 与节点代理链路健康检查",
            "agent": "claude",
            "botId": "bot-ops-claude",
            "cwd": default_cwd(),
            "prompt": "检查本机代理端口 7897 与 Hub 18995 状态并输出简报。",
            "cron": "0 */6 * * *",
            // 示例任务默认关着：现在会按时自动执行，打开前先确认要消耗模型额度
            "enabled": false,
            "lastRunAt": 0,
            "lastStatus": "待命"
        }
    ]);
    let _ = fs::write(&p, serde_json::to_string_pretty(&default_tasks).unwrap_or_default());
    json!({ "tasks": default_tasks })
}

pub fn scheduler_action(body: &Value) -> Result<Value, String> {
    let _g = crate::schedule::file_lock().lock().unwrap_or_else(|e| e.into_inner());
    let action = body.get("action").and_then(Value::as_str).unwrap_or("list");
    if action == "create" && crate::schedule::requires_model_binding(body) {
        return Err("Bot schedules require a fresh Hub model grant; use the Bot conversation until metered scheduling is available".into());
    }
    let mut list = scheduler_list()
        .get("tasks")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();

    match action {
        "create" => {
            let name = body.get("name").and_then(Value::as_str).unwrap_or("新建定时任务").trim();
            let agent = body.get("agent").and_then(Value::as_str).unwrap_or("antigravity").trim();
            let bot_id = body.get("botId").and_then(Value::as_str).unwrap_or("").trim();
            let cwd = resolve_cwd(body.get("cwd").and_then(Value::as_str).unwrap_or(""), None);
            let prompt = body.get("prompt").and_then(Value::as_str).unwrap_or("").trim();
            let cron = body.get("cron").and_then(Value::as_str).unwrap_or("每 6 小时").trim();
            if prompt.is_empty() {
                return Err("prompt required".into());
            }
            let now = now_ms();
            let next_run = crate::schedule::next_run_at(cron, now).unwrap_or(0);
            list.insert(
                0,
                json!({
                    "id": format!("sched-{}", now),
                    "name": name,
                    "agent": agent,
                    "botId": bot_id,
                    "cwd": cwd,
                    "prompt": prompt,
                    "cron": cron,
                    "enabled": true,
                    "enabledAt": now,
                    "lastRunAt": 0,
                    "nextRunAt": next_run,
                    "lastStatus": if next_run > 0 { "已就绪：按时自动执行" } else { "已就绪（定时写法认不出，只能手动执行）" }
                }),
            );
        }
        "toggle" => {
            let id = body.get("id").and_then(Value::as_str).unwrap_or("");
            for item in &mut list {
                if item.get("id").and_then(Value::as_str) == Some(id) {
                    let cur = item.get("enabled").and_then(Value::as_bool).unwrap_or(false);
                    if let Some(obj) = item.as_object_mut() {
                        obj.insert("enabled".into(), json!(!cur));
                        if !cur {
                            let now = now_ms();
                            // 从打开的这一刻起算间隔，不会一打开就跑
                            obj.insert("enabledAt".into(), json!(now));
                            obj.insert("lastRunAt".into(), json!(0));
                            let next_run = crate::schedule::next_run_at(
                                obj.get("cron").and_then(Value::as_str).unwrap_or(""),
                                now,
                            );
                            obj.insert("nextRunAt".into(), json!(next_run.unwrap_or(0)));
                            obj.insert(
                                "lastStatus".into(),
                                json!(if next_run.is_some() { "已启用：按时自动执行" } else { "已启用，但定时写法认不出，只能手动执行" }),
                            );
                        }
                    }
                }
            }
        }
        "delete" => {
            let id = body.get("id").and_then(Value::as_str).unwrap_or("");
            list.retain(|item| item.get("id").and_then(Value::as_str) != Some(id));
        }
        "run" => {
            let id = body.get("id").and_then(Value::as_str).unwrap_or("");
            for item in &mut list {
                if item.get("id").and_then(Value::as_str) == Some(id) {
                    if crate::schedule::requires_model_binding(item) {
                        return Err("Bot schedule paused: missing fresh Hub model binding".into());
                    }
                    let ag = item.get("agent").and_then(Value::as_str).unwrap_or("antigravity");
                    let cwd = item.get("cwd").and_then(Value::as_str).unwrap_or("");
                    let prompt = item.get("prompt").and_then(Value::as_str).unwrap_or("");
                    let _ = match ag {
                        "codex" => codex_create(cwd, prompt, None),
                        "claude" => claude_create(cwd, prompt, None),
                        _ => antigravity_create(cwd, prompt, None),
                    };
                    if let Some(obj) = item.as_object_mut() {
                        obj.insert("lastRunAt".into(), json!(now_ms()));
                        obj.insert("lastStatus".into(), json!("已触发运行"));
                    }
                }
            }
        }
        _ => {}
    }

    let _ = fs::write(
        schedules_file(),
        serde_json::to_string_pretty(&Value::Array(list.clone())).unwrap_or_default(),
    );
    Ok(json!({ "ok": true, "tasks": list }))
}

fn mcp_registry_file() -> PathBuf {
    home().join(".vibelink-mcp.json")
}

fn load_mcp_servers() -> Vec<Value> {
    let p = mcp_registry_file();
    if let Ok(raw) = fs::read_to_string(&p) {
        if let Ok(Value::Array(arr)) = serde_json::from_str::<Value>(&raw) {
            return arr;
        }
    }
    let seeded = vec![
        json!({
            "id": "mcp-codebase-memory",
            "name": "codebase-memory-mcp",
            "transport": "stdio",
            "url": "local://codebase-memory-mcp",
            "description": "项目代码知识图谱（函数调用链与架构索引）",
            "enabled": true,
            "builtin": true
        }),
        json!({
            "id": "mcp-github-remote",
            "name": "github-copilot-mcp",
            "transport": "https",
            "url": "https://api.githubcopilot.com/mcp/",
            "description": "远程 HTTPS MCP 示例（Issue / PR / 仓库检索）",
            "enabled": true,
            "builtin": false
        }),
    ];
    let _ = fs::write(
        &p,
        serde_json::to_string_pretty(&Value::Array(seeded.clone())).unwrap_or_default(),
    );
    seeded
}

pub fn plugins_list() -> Value {
    let mut skills = Vec::new();
    let skill_dirs = [
        ("Antigravity / Gemini", home().join(".gemini").join("skills")),
        (
            "Antigravity Builtin",
            home()
                .join(".gemini")
                .join("antigravity-cli")
                .join("builtin")
                .join("skills"),
        ),
        ("Claude Code", home().join(".claude").join("skills")),
        ("Workspace Agents", home().join(".agents").join("skills")),
    ];
    for (scope, dir) in skill_dirs {
        if let Ok(rd) = fs::read_dir(&dir) {
            for e in rd.flatten() {
                let p = e.path();
                if !p.is_dir() {
                    continue;
                }
                let name = e.file_name().to_string_lossy().to_string();
                let md = p.join("SKILL.md");
                let desc = fs::read_to_string(&md)
                    .ok()
                    .and_then(|s| {
                        s.lines()
                            .find(|l| l.trim_start().starts_with("description:"))
                            .map(|l| {
                                l.trim_start_matches("description:")
                                    .trim()
                                    .trim_matches('"')
                                    .to_string()
                            })
                    })
                    .unwrap_or_else(|| "本地已安装技能插件".into());
                skills.push(json!({
                    "name": name,
                    "scope": scope,
                    "path": p.to_string_lossy().to_string(),
                    "description": desc,
                    "enabled": true,
                }));
            }
        }
    }
    let mcp_servers = load_mcp_servers();
    json!({
        "skills": skills,
        "mcpServers": mcp_servers,
        "engines": [
            { "id": "antigravity", "name": "Antigravity (agy)", "bin": agy_bin(), "ready": true },
            { "id": "claude", "name": "Claude Code", "bin": find_claude_exe().map(|p| p.to_string_lossy().to_string()).unwrap_or_else(|| "claude".into()), "ready": true },
            { "id": "codex", "name": "Codex", "bin": find_codex_exe().map(|p| p.to_string_lossy().to_string()).unwrap_or_else(|| "codex".into()), "ready": true },
            { "id": "opencode", "name": "OpenCode", "bin": "opencode", "ready": true }
        ]
    })
}

/// 动态管理远程 HTTPS MCP Server 与自定义 Skill（对标 Rakazo P3 自定义 MCP & Skill 热插拔）
pub fn plugins_action(body: &Value) -> Result<Value, String> {
    let action = body
        .get("action")
        .and_then(Value::as_str)
        .unwrap_or("list")
        .trim()
        .to_lowercase();
    let mut mcp_servers = load_mcp_servers();

    match action.as_str() {
        "add_mcp" | "upsert_mcp" => {
            let name = body
                .get("name")
                .and_then(Value::as_str)
                .unwrap_or("")
                .trim();
            let url = body
                .get("url")
                .or_else(|| body.get("command"))
                .and_then(Value::as_str)
                .unwrap_or("")
                .trim();
            if name.is_empty() || url.is_empty() {
                return Err("MCP server name and url/command are required".into());
            }
            let transport = body
                .get("transport")
                .and_then(Value::as_str)
                .unwrap_or(if url.starts_with("http://") || url.starts_with("https://") {
                    "https"
                } else {
                    "stdio"
                });
            let desc = body
                .get("description")
                .and_then(Value::as_str)
                .unwrap_or("自定义远程 MCP 服务")
                .trim();
            let id = body
                .get("id")
                .and_then(Value::as_str)
                .filter(|s| !s.trim().is_empty())
                .map(|s| s.trim().to_string())
                .unwrap_or_else(|| format!("mcp-{}", now_ms()));
            mcp_servers.retain(|m| {
                m.get("id").and_then(Value::as_str) != Some(id.as_str())
                    && m.get("name").and_then(Value::as_str) != Some(name)
            });
            mcp_servers.insert(
                0,
                json!({
                    "id": id,
                    "name": name,
                    "transport": transport,
                    "url": url,
                    "description": desc,
                    "enabled": true,
                    "builtin": false
                }),
            );
            let _ = fs::write(
                mcp_registry_file(),
                serde_json::to_string_pretty(&Value::Array(mcp_servers)).unwrap_or_default(),
            );
        }
        "toggle_mcp" => {
            let id = body
                .get("id")
                .or_else(|| body.get("name"))
                .and_then(Value::as_str)
                .unwrap_or("");
            for item in &mut mcp_servers {
                let matched = item.get("id").and_then(Value::as_str) == Some(id)
                    || item.get("name").and_then(Value::as_str) == Some(id);
                if matched {
                    let cur = item.get("enabled").and_then(Value::as_bool).unwrap_or(true);
                    if let Some(o) = item.as_object_mut() {
                        o.insert("enabled".into(), json!(!cur));
                    }
                }
            }
            let _ = fs::write(
                mcp_registry_file(),
                serde_json::to_string_pretty(&Value::Array(mcp_servers)).unwrap_or_default(),
            );
        }
        "remove_mcp" | "delete_mcp" => {
            let id = body
                .get("id")
                .or_else(|| body.get("name"))
                .and_then(Value::as_str)
                .unwrap_or("");
            mcp_servers.retain(|m| {
                m.get("id").and_then(Value::as_str) != Some(id)
                    && m.get("name").and_then(Value::as_str) != Some(id)
            });
            let _ = fs::write(
                mcp_registry_file(),
                serde_json::to_string_pretty(&Value::Array(mcp_servers)).unwrap_or_default(),
            );
        }
        "add_skill" | "upsert_skill" => {
            let raw_name = body
                .get("name")
                .and_then(Value::as_str)
                .unwrap_or("")
                .trim();
            let desc = body
                .get("description")
                .and_then(Value::as_str)
                .unwrap_or("自定义技能插件")
                .trim();
            let content = body
                .get("content")
                .or_else(|| body.get("instructions"))
                .and_then(Value::as_str)
                .unwrap_or("")
                .trim();
            let safe_name: String = raw_name
                .chars()
                .filter(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_')
                .collect();
            if safe_name.is_empty() {
                return Err("valid skill name (alphanumeric/-/_) is required".into());
            }
            let skill_dir = home().join(".agents").join("skills").join(&safe_name);
            fs::create_dir_all(&skill_dir).map_err(|e| e.to_string())?;
            let md = format!(
                "---\nname: {safe_name}\ndescription: \"{desc}\"\n---\n\n# {safe_name}\n\n{content}\n"
            );
            fs::write(skill_dir.join("SKILL.md"), md).map_err(|e| e.to_string())?;
        }
        "remove_skill" | "delete_skill" => {
            let raw_name = body
                .get("name")
                .and_then(Value::as_str)
                .unwrap_or("")
                .trim();
            let safe_name: String = raw_name
                .chars()
                .filter(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_')
                .collect();
            if !safe_name.is_empty() {
                let skill_dir = home().join(".agents").join("skills").join(&safe_name);
                if skill_dir.is_dir() {
                    let _ = fs::remove_dir_all(&skill_dir);
                }
            }
        }
        _ => {}
    }

    Ok(plugins_list())
}


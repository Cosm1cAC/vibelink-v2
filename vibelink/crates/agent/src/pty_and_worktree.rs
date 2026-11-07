//! 交互式持久化终端会话、`computer.command` 活动流与 Per-Bot Git Worktree 隔离执行层（对标 Rakazo P1）。
//!
//! 1. **持久化终端会话 (`terminal.*`)**：
//!    - 支持按 `sessionId` / `botId` 创建、复用、输入命令、轮询增量输出、清理与关闭终端。
//!    - 会话内持久维护当前工作目录 (`cwd`) 与自定义环境变量 (`env`)，支持 `cd`、`export` / `$env:K="V"` 跨命令保持。
//!    - 每次执行自动生成结构化 `computer.command` 活动流条目，供手机端 `WorkspaceInspectorDialog` 实时查看。
//!
//! 2. **Per-Bot Git Worktree 隔离 (`worktree.*`)**：
//!    - 当 Bot 配置 `isolationMode = "worktree"` 时，自动在仓库 `.vibelink-worktrees/<bot-id>` 下创建独立分支 `bot/<bot-id>` 的 Git Worktree。
//!    - 避免多个研发 Bot 在同一工作区并发改文件时互相覆盖或引发 Git 索引锁冲突。

use crate::roots::{clamp_cwd, roots, within};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    fs,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::{Mutex, OnceLock},
    time::{Instant, SystemTime, UNIX_EPOCH},
};

const MAX_SCROLLBACK_LINES: usize = 400;
const MAX_ACTIVITY_ITEMS: usize = 120;
const MAX_OUTPUT_CHARS: usize = 24_000;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TerminalSession {
    pub id: String,
    pub title: String,
    pub bot_id: String,
    pub cwd: String,
    pub shell: String,
    pub env: HashMap<String, String>,
    pub lines: Vec<TerminalLine>,
    pub last_exit_code: Option<i32>,
    pub created_at: u64,
    pub updated_at: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TerminalLine {
    pub seq: u64,
    pub stream: String, // "Prompt" | "stdout" | "stderr" | "system"
    pub text: String,
    pub at: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CommandActivity {
    pub id: String,
    pub kind: String, // "computer.command"
    pub session_id: String,
    pub bot_id: String,
    pub cwd: String,
    pub command: String,
    pub output_preview: String,
    pub exit_code: i32,
    pub duration_ms: u64,
    pub at: u64,
}

#[derive(Default)]
struct TerminalState {
    sessions: HashMap<String, TerminalSession>,
    activities: Vec<CommandActivity>,
    seq: u64,
}

static STATE: OnceLock<Mutex<TerminalState>> = OnceLock::new();

fn state() -> &'static Mutex<TerminalState> {
    STATE.get_or_init(|| Mutex::new(TerminalState::default()))
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn default_shell_name() -> String {
    if cfg!(windows) {
        "pwsh / powershell".to_string()
    } else {
        std::env::var("SHELL").unwrap_or_else(|_| "/bin/sh".to_string())
    }
}

fn default_workspace_cwd() -> String {
    let d_stack = Path::new(r"D:\AI\VibeLink");
    if d_stack.is_dir() {
        return clamp_cwd(r"D:\AI\VibeLink", ".");
    }
    clamp_cwd(r"C:\Users\22771\Desktop\项目\VibeLink", ".")
}

pub fn sanitize_slug(raw: &str) -> String {
    let mut out = String::new();
    for ch in raw.trim().chars() {
        if ch.is_ascii_alphanumeric() || ch == '-' || ch == '_' {
            out.push(ch.to_ascii_lowercase());
        } else if (ch.is_whitespace() || ch == '.' || ch == '/') && !out.ends_with('-') {
            out.push('-');
        }
    }
    let trimmed = out.trim_matches('-').to_string();
    if trimmed.is_empty() {
        "bot-default".to_string()
    } else if trimmed.len() > 48 {
        trimmed[..48].trim_end_matches('-').to_string()
    } else {
        trimmed
    }
}

fn push_line(sess: &mut TerminalSession, seq: &mut u64, stream: &str, text: impl Into<String>) {
    *seq += 1;
    let now = now_ms();
    sess.lines.push(TerminalLine {
        seq: *seq,
        stream: stream.to_string(),
        text: text.into(),
        at: now,
    });
    if sess.lines.len() > MAX_SCROLLBACK_LINES {
        let drop_n = sess.lines.len() - MAX_SCROLLBACK_LINES;
        sess.lines.drain(0..drop_n);
    }
    sess.updated_at = now;
}

/// 记录一条 `computer.command` 活动事件（可供内部终端或外部 Agent 调用）
pub fn record_command_activity(
    session_id: &str,
    bot_id: &str,
    cwd: &str,
    command: &str,
    output: &str,
    exit_code: i32,
    duration_ms: u64,
) -> CommandActivity {
    let mut g = state().lock().unwrap_or_else(|e| e.into_inner());
    g.seq += 1;
    let preview = if output.chars().count() > 600 {
        let head: String = output.chars().take(600).collect();
        format!("{head}…")
    } else {
        output.to_string()
    };
    let item = CommandActivity {
        id: format!("cmd-{}", g.seq),
        kind: "computer.command".to_string(),
        session_id: session_id.to_string(),
        bot_id: bot_id.to_string(),
        cwd: cwd.to_string(),
        command: command.to_string(),
        output_preview: preview,
        exit_code,
        duration_ms,
        at: now_ms(),
    };
    g.activities.push(item.clone());
    if g.activities.len() > MAX_ACTIVITY_ITEMS {
        let drop_n = g.activities.len() - MAX_ACTIVITY_ITEMS;
        g.activities.drain(0..drop_n);
    }
    item
}

/// 列出所有活跃终端会话与最近的 `computer.command` 活动流
pub fn terminal_list() -> Value {
    let mut g = state().lock().unwrap_or_else(|e| e.into_inner());
    if g.sessions.is_empty() {
        let cwd = default_workspace_cwd();
        let now = now_ms();
        g.seq += 1;
        let seq = g.seq;
        let default_sess = TerminalSession {
            id: "term-main".to_string(),
            title: "主工作区终端".to_string(),
            bot_id: String::new(),
            cwd: cwd.clone(),
            shell: default_shell_name(),
            env: HashMap::new(),
            lines: vec![TerminalLine {
                seq,
                stream: "system".to_string(),
                text: format!("VibeLink Interactive Terminal ready ({cwd})"),
                at: now,
            }],
            last_exit_code: Some(0),
            created_at: now,
            updated_at: now,
        };
        g.sessions.insert(default_sess.id.clone(), default_sess);
    }
    let mut sessions: Vec<TerminalSession> = g.sessions.values().cloned().collect();
    sessions.sort_by(|a, b| b.updated_at.cmp(&a.updated_at));
    let mut activities: Vec<CommandActivity> = g.activities.iter().rev().take(40).cloned().collect();
    activities.sort_by(|a, b| b.at.cmp(&a.at));
    json!({
        "ok": true,
        "sessions": sessions,
        "activities": activities,
    })
}

/// 执行终端动作：`open` | `input` / `exec` | `poll` | `clear` | `close` | `record_command`
pub fn terminal_action(body: &Value) -> Result<Value, String> {
    let action = body
        .get("action")
        .and_then(|v| v.as_str())
        .unwrap_or("poll")
        .trim()
        .to_lowercase();
    let session_id = body
        .get("sessionId")
        .or_else(|| body.get("id"))
        .and_then(|v| v.as_str())
        .unwrap_or("term-main")
        .trim()
        .to_string();
    let bot_id = body
        .get("botId")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .trim()
        .to_string();
    let req_cwd = body
        .get("cwd")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .trim()
        .to_string();

    match action.as_str() {
        "open" | "create" => {
            let cwd = if req_cwd.is_empty() {
                default_workspace_cwd()
            } else {
                clamp_cwd(&req_cwd, &default_workspace_cwd())
            };
            let title = body
                .get("title")
                .and_then(|v| v.as_str())
                .filter(|s| !s.trim().is_empty())
                .map(|s| s.trim().to_string())
                .unwrap_or_else(|| {
                    if !bot_id.is_empty() {
                        format!("Bot 终端 ({bot_id})")
                    } else {
                        "工作区终端".to_string()
                    }
                });
            let mut g = state().lock().unwrap_or_else(|e| e.into_inner());
            let id = if session_id.is_empty() {
                g.seq += 1;
                format!("term-{}", g.seq)
            } else {
                session_id
            };
            let now = now_ms();
            let mut seq = g.seq;
            let snapshot = {
                let sess = g.sessions.entry(id.clone()).or_insert_with(|| {
                    seq += 1;
                    TerminalSession {
                        id: id.clone(),
                        title: title.clone(),
                        bot_id: bot_id.clone(),
                        cwd: cwd.clone(),
                        shell: default_shell_name(),
                        env: HashMap::new(),
                        lines: vec![TerminalLine {
                            seq,
                            stream: "system".to_string(),
                            text: format!("已挂载终端会话 [{id}] @ {cwd}"),
                            at: now,
                        }],
                        last_exit_code: Some(0),
                        created_at: now,
                        updated_at: now,
                    }
                });
                if !req_cwd.is_empty() {
                    sess.cwd = cwd;
                }
                if !bot_id.is_empty() {
                    sess.bot_id = bot_id;
                }
                sess.clone()
            };
            g.seq = seq;
            Ok(json!({
                "ok": true,
                "session": snapshot,
            }))
        }
        "clear" => {
            let mut g = state().lock().unwrap_or_else(|e| e.into_inner());
            if let Some(sess) = g.sessions.get_mut(&session_id) {
                sess.lines.clear();
                sess.updated_at = now_ms();
                return Ok(json!({ "ok": true, "session": sess.clone() }));
            }
            Err(format!("terminal session not found: {session_id}"))
        }
        "close" | "kill" => {
            let mut g = state().lock().unwrap_or_else(|e| e.into_inner());
            let removed = g.sessions.remove(&session_id).is_some();
            Ok(json!({ "ok": removed, "sessionId": session_id }))
        }
        "record_command" => {
            let cmd = body
                .get("command")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .trim();
            let output = body.get("output").and_then(|v| v.as_str()).unwrap_or("");
            let exit_code = body.get("exitCode").and_then(|v| v.as_i64()).unwrap_or(0) as i32;
            let duration_ms = body.get("durationMs").and_then(|v| v.as_u64()).unwrap_or(0);
            let cwd = if req_cwd.is_empty() {
                default_workspace_cwd()
            } else {
                req_cwd
            };
            let act = record_command_activity(
                &session_id,
                &bot_id,
                &cwd,
                cmd,
                output,
                exit_code,
                duration_ms,
            );
            Ok(json!({ "ok": true, "activity": act }))
        }
        "input" | "exec" | "run" => {
            let raw_cmd = body
                .get("command")
                .or_else(|| body.get("input"))
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .trim()
                .to_string();
            if raw_cmd.is_empty() {
                return Err("empty command".into());
            }
            Ok(execute_in_session(
                &session_id,
                &bot_id,
                &req_cwd,
                &raw_cmd,
            ))
        }
        _ => {
            // "poll" / "get"
            let since_seq = body.get("sinceSeq").and_then(|v| v.as_u64()).unwrap_or(0);
            let g = state().lock().unwrap_or_else(|e| e.into_inner());
            let sess = g.sessions.get(&session_id).cloned();
            let lines: Vec<TerminalLine> = sess
                .as_ref()
                .map(|s| {
                    s.lines
                        .iter()
                        .filter(|l| l.seq > since_seq)
                        .cloned()
                        .collect()
                })
                .unwrap_or_default();
            let activities: Vec<CommandActivity> =
                g.activities.iter().rev().take(30).cloned().collect();
            Ok(json!({
                "ok": sess.is_some(),
                "session": sess,
                "lines": lines,
                "activities": activities,
            }))
        }
    }
}

fn execute_in_session(session_id: &str, bot_id: &str, req_cwd: &str, raw_cmd: &str) -> Value {
    let (mut curr_cwd, mut curr_env, eff_bot_id) = {
        let mut g = state().lock().unwrap_or_else(|e| e.into_inner());
        let fallback = default_workspace_cwd();
        let init_cwd = if req_cwd.is_empty() {
            fallback.clone()
        } else {
            clamp_cwd(req_cwd, &fallback)
        };
        let now = now_ms();
        let mut seq = g.seq;
        let out = {
            let sess = g.sessions.entry(session_id.to_string()).or_insert_with(|| {
                seq += 1;
                TerminalSession {
                    id: session_id.to_string(),
                    title: if !bot_id.is_empty() {
                        format!("Bot 终端 ({bot_id})")
                    } else {
                        "主工作区终端".to_string()
                    },
                    bot_id: bot_id.to_string(),
                    cwd: init_cwd.clone(),
                    shell: default_shell_name(),
                    env: HashMap::new(),
                    lines: Vec::new(),
                    last_exit_code: Some(0),
                    created_at: now,
                    updated_at: now,
                }
            });
            if !req_cwd.is_empty() {
                sess.cwd = clamp_cwd(req_cwd, &sess.cwd);
            }
            if !bot_id.is_empty() {
                sess.bot_id = bot_id.to_string();
            }
            let prompt_line = format!("{} > {}", sess.cwd, raw_cmd);
            push_line(sess, &mut seq, "Prompt", prompt_line);
            (sess.cwd.clone(), sess.env.clone(), sess.bot_id.clone())
        };
        g.seq = seq;
        out
    };

    let started = Instant::now();

    // 1. 内建状态命令：`cd <path>`
    if let Some(target_dir) = parse_cd_command(raw_cmd) {
        let candidate = if Path::new(&target_dir).is_absolute() {
            PathBuf::from(&target_dir)
        } else {
            Path::new(&curr_cwd).join(&target_dir)
        };
        let allowed_roots = roots();
        let (ok, msg, code) = if candidate.is_dir() && within(&candidate, &allowed_roots) {
            curr_cwd = crate::roots::normalize(&candidate)
                .to_string_lossy()
                .to_string();
            (true, format!("已切换工作目录至 {curr_cwd}"), 0)
        } else if !candidate.is_dir() {
            (false, format!("目录不存在: {}", candidate.display()), 1)
        } else {
            (
                false,
                format!("目标路径超出允许的工作区白名单: {}", candidate.display()),
                1,
            )
        };
        let dur = started.elapsed().as_millis() as u64;
        let act = record_command_activity(
            session_id,
            &eff_bot_id,
            &curr_cwd,
            raw_cmd,
            &msg,
            code,
            dur,
        );
        let mut g = state().lock().unwrap_or_else(|e| e.into_inner());
        let mut seq = g.seq;
        let snapshot = if let Some(sess) = g.sessions.get_mut(session_id) {
            sess.cwd = curr_cwd.clone();
            sess.last_exit_code = Some(code);
            push_line(sess, &mut seq, if ok { "stdout" } else { "stderr" }, &msg);
            Some(sess.clone())
        } else {
            None
        };
        g.seq = seq;
        if let Some(s) = snapshot {
            return json!({
                "ok": ok,
                "exitCode": code,
                "output": msg,
                "session": s,
                "activity": act,
            });
        }
    }

    // 2. 内建环境变量设置：`export K=V` 或 `$env:K="V"`
    if let Some((k, v)) = parse_env_assignment(raw_cmd) {
        curr_env.insert(k.clone(), v.clone());
        let msg = format!("已设置环境变量 {k}={v}");
        let dur = started.elapsed().as_millis() as u64;
        let act =
            record_command_activity(session_id, &eff_bot_id, &curr_cwd, raw_cmd, &msg, 0, dur);
        let mut g = state().lock().unwrap_or_else(|e| e.into_inner());
        let mut seq = g.seq;
        let snapshot = if let Some(sess) = g.sessions.get_mut(session_id) {
            sess.env = curr_env.clone();
            sess.last_exit_code = Some(0);
            push_line(sess, &mut seq, "system", &msg);
            Some(sess.clone())
        } else {
            None
        };
        g.seq = seq;
        if let Some(s) = snapshot {
            return json!({
                "ok": true,
                "exitCode": 0,
                "output": msg,
                "session": s,
                "activity": act,
            });
        }
    }

    // 3. 在目标工作目录执行外部命令
    let mut cmd = if cfg!(windows) {
        let mut c = Command::new("powershell");
        c.args(["-NoProfile", "-NonInteractive", "-Command", raw_cmd]);
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            c.creation_flags(0x0800_0000);
        }
        c
    } else {
        let mut c = Command::new("sh");
        c.args(["-c", raw_cmd]);
        c
    };
    cmd.current_dir(&curr_cwd)
        .envs(&curr_env)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    let out_res = cmd.output();
    let duration_ms = started.elapsed().as_millis() as u64;

    let (exit_code, stdout_str, stderr_str) = match out_res {
        Ok(out) => {
            let code = out.status.code().unwrap_or(-1);
            let mut so = String::from_utf8_lossy(&out.stdout).to_string();
            let mut se = String::from_utf8_lossy(&out.stderr).to_string();
            if so.len() > MAX_OUTPUT_CHARS {
                so.truncate(MAX_OUTPUT_CHARS);
                so.push_str("\n...[stdout truncated]");
            }
            if se.len() > MAX_OUTPUT_CHARS {
                se.truncate(MAX_OUTPUT_CHARS);
                se.push_str("\n...[stderr truncated]");
            }
            (code, so, se)
        }
        Err(e) => (-1, String::new(), format!("failed to spawn command: {e}")),
    };

    let combined_output = match (stdout_str.trim().is_empty(), stderr_str.trim().is_empty()) {
        (true, true) => "(无输出)".to_string(),
        (false, true) => stdout_str.trim_end().to_string(),
        (true, false) => stderr_str.trim_end().to_string(),
        (false, false) => format!("{}\n{}", stdout_str.trim_end(), stderr_str.trim_end()),
    };

    let act = record_command_activity(
        session_id,
        &eff_bot_id,
        &curr_cwd,
        raw_cmd,
        &combined_output,
        exit_code,
        duration_ms,
    );

    let mut g = state().lock().unwrap_or_else(|e| e.into_inner());
    let mut seq = g.seq;
    let sess_snapshot = if let Some(sess) = g.sessions.get_mut(session_id) {
        sess.last_exit_code = Some(exit_code);
        for line in stdout_str.lines() {
            push_line(sess, &mut seq, "stdout", line);
        }
        for line in stderr_str.lines() {
            push_line(sess, &mut seq, "stderr", line);
        }
        if stdout_str.trim().is_empty() && stderr_str.trim().is_empty() {
            push_line(
                sess,
                &mut seq,
                "system",
                format!("(exit {exit_code}, {duration_ms} ms)"),
            );
        }
        Some(sess.clone())
    } else {
        None
    };
    g.seq = seq;

    json!({
        "ok": exit_code == 0,
        "exitCode": exit_code,
        "durationMs": duration_ms,
        "output": combined_output,
        "session": sess_snapshot,
        "activity": act,
    })
}

fn parse_cd_command(raw: &str) -> Option<String> {
    let t = raw.trim();
    if let Some(rest) = t.strip_prefix("cd ") {
        let dir = rest.trim().trim_matches('"').trim_matches('\'');
        if !dir.is_empty() && !dir.contains(';') && !dir.contains("&&") {
            return Some(dir.to_string());
        }
    } else if let Some(rest) = t.strip_prefix("Set-Location ") {
        let dir = rest.trim().trim_matches('"').trim_matches('\'');
        if !dir.is_empty() && !dir.contains(';') {
            return Some(dir.to_string());
        }
    }
    None
}

fn parse_env_assignment(raw: &str) -> Option<(String, String)> {
    let t = raw.trim();
    if let Some(rest) = t.strip_prefix("export ") {
        let (k, v) = rest.split_once('=')?;
        let key = k.trim();
        let val = v.trim().trim_matches('"').trim_matches('\'');
        if !key.is_empty() && key.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
            return Some((key.to_string(), val.to_string()));
        }
    } else if let Some(rest) = t.strip_prefix("$env:") {
        let (k, v) = rest.split_once('=')?;
        let key = k.trim();
        let val = v.trim().trim_matches('"').trim_matches('\'');
        if !key.is_empty() && key.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
            return Some((key.to_string(), val.to_string()));
        }
    }
    None
}

// ============================================================================
// Per-Bot Git Worktree 隔离层（Phase P1）
// ============================================================================

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorktreeInfo {
    pub bot_id: String,
    pub slug: String,
    pub branch: String,
    pub base_repo: String,
    pub worktree_path: String,
    pub is_git_worktree: bool,
    pub dirty_files: usize,
    pub head_commit: String,
}

fn worktrees_root(base_repo: &Path) -> PathBuf {
    base_repo.join(".vibelink-worktrees")
}

/// 当 Bot 配置了 `isolationMode == "worktree"` 时，自动确保并返回该 Bot 的专属工作目录。
/// 若 `isolationMode != "worktree"` 或 `bot_id` 为空，则直接返回 `base_cwd`。
pub fn resolve_bot_cwd(base_cwd: &str, bot_id: &str, isolation_mode: &str) -> String {
    let clamped = clamp_cwd(base_cwd, &default_workspace_cwd());
    if !isolation_mode.eq_ignore_ascii_case("worktree") || bot_id.trim().is_empty() {
        return clamped;
    }
    match ensure_bot_worktree(Path::new(&clamped), bot_id) {
        Ok(info) => info.worktree_path,
        Err(_) => clamped,
    }
}

/// 在指定目录下构造静默（Windows 下带 `CREATE_NO_WINDOW`）的 `git` 命令，防止 PC 端弹出闪烁的黑框命令行窗口。
fn git_cmd(cwd: &Path) -> Command {
    let mut cmd = Command::new("git");
    cmd.current_dir(cwd)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000);
    }
    cmd
}

/// 确保指定 `bot_id` 在 `base_repo` 下拥有独立的 `.vibelink-worktrees/<slug>` 工作树
pub fn ensure_bot_worktree(base_repo: &Path, bot_id: &str) -> Result<WorktreeInfo, String> {
    let allowed = roots();
    if !within(base_repo, &allowed) {
        return Err(format!(
            "base repository outside allowed workspace roots: {}",
            base_repo.display()
        ));
    }
    let slug = sanitize_slug(bot_id);
    let branch = format!("bot/{slug}");
    let wt_root = worktrees_root(base_repo);
    fs::create_dir_all(&wt_root).map_err(|e| format!("create worktrees root failed: {e}"))?;
    let wt_path = wt_root.join(&slug);

    let is_git = base_repo.join(".git").exists()
        || git_cmd(base_repo)
            .args(["rev-parse", "--is-inside-work-tree"])
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false);

    if is_git && !wt_path.join(".git").exists() {
        let wt_str = wt_path.to_string_lossy().to_string();
        let out = git_cmd(base_repo)
            .args(["worktree", "add", "-B", &branch, &wt_str, "HEAD"])
            .output()
            .map_err(|e| format!("git worktree add failed: {e}"))?;
        if !out.status.success() {
            let _ = git_cmd(base_repo)
                .args(["worktree", "prune"])
                .output();
            let _ = git_cmd(base_repo)
                .args(["worktree", "add", "-B", &branch, &wt_str, "HEAD"])
                .output();
        }
    }

    if !wt_path.exists() {
        fs::create_dir_all(&wt_path).map_err(|e| format!("create isolated bot dir failed: {e}"))?;
    }

    inspect_worktree(base_repo, bot_id, &slug, &branch, &wt_path)
}

fn inspect_worktree(
    base_repo: &Path,
    bot_id: &str,
    slug: &str,
    branch: &str,
    wt_path: &Path,
) -> Result<WorktreeInfo, String> {
    let is_git_worktree = wt_path.join(".git").exists();
    let mut dirty_files = 0usize;
    let mut head_commit = String::new();
    let mut actual_branch = branch.to_string();

    if is_git_worktree {
        if let Ok(out) = git_cmd(wt_path)
            .args(["status", "--porcelain"])
            .output()
        {
            if out.status.success() {
                dirty_files = String::from_utf8_lossy(&out.stdout)
                    .lines()
                    .filter(|l| !l.trim().is_empty())
                    .count();
            }
        }
        if let Ok(out) = git_cmd(wt_path)
            .args(["rev-parse", "--short", "HEAD"])
            .output()
        {
            if out.status.success() {
                head_commit = String::from_utf8_lossy(&out.stdout).trim().to_string();
            }
        }
        if let Ok(out) = git_cmd(wt_path)
            .args(["rev-parse", "--abbrev-ref", "HEAD"])
            .output()
        {
            if out.status.success() {
                let b = String::from_utf8_lossy(&out.stdout).trim().to_string();
                if !b.is_empty() {
                    actual_branch = b;
                }
            }
        }
    }

    Ok(WorktreeInfo {
        bot_id: bot_id.to_string(),
        slug: slug.to_string(),
        branch: actual_branch,
        base_repo: base_repo.to_string_lossy().to_string(),
        worktree_path: wt_path.to_string_lossy().to_string(),
        is_git_worktree,
        dirty_files,
        head_commit,
    })
}

/// 列出指定仓库下所有已创建的 Bot Worktree
pub fn worktree_list(repo_path: Option<&str>) -> Value {
    let base_str = repo_path
        .filter(|s| !s.trim().is_empty())
        .map(|s| clamp_cwd(s, &default_workspace_cwd()))
        .unwrap_or_else(default_workspace_cwd);
    let base_repo = PathBuf::from(&base_str);
    let wt_root = worktrees_root(&base_repo);
    let mut items = Vec::new();

    if let Ok(rd) = fs::read_dir(&wt_root) {
        for entry in rd.flatten() {
            let p = entry.path();
            if p.is_dir() {
                let slug = entry.file_name().to_string_lossy().to_string();
                let branch = format!("bot/{slug}");
                if let Ok(info) = inspect_worktree(&base_repo, &slug, &slug, &branch, &p) {
                    items.push(info);
                }
            }
        }
    }
    items.sort_by(|a, b| a.slug.cmp(&b.slug));
    json!({
        "ok": true,
        "baseRepo": base_str,
        "worktrees": items,
    })
}

/// 执行 Git Worktree 管理操作：`ensure` | `status` | `checkpoint` | `remove`
pub fn worktree_action(body: &Value) -> Result<Value, String> {
    let action = body
        .get("action")
        .and_then(|v| v.as_str())
        .unwrap_or("ensure")
        .trim()
        .to_lowercase();
    let bot_id = body
        .get("botId")
        .or_else(|| body.get("slug"))
        .and_then(|v| v.as_str())
        .unwrap_or("bot-default")
        .trim();
    let repo_raw = body
        .get("repoPath")
        .or_else(|| body.get("cwd"))
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let base_str = if repo_raw.trim().is_empty() {
        default_workspace_cwd()
    } else {
        clamp_cwd(repo_raw, &default_workspace_cwd())
    };
    let base_repo = PathBuf::from(&base_str);

    match action.as_str() {
        "ensure" | "create" | "status" => {
            let info = ensure_bot_worktree(&base_repo, bot_id)?;
            Ok(json!({ "ok": true, "worktree": info }))
        }
        "checkpoint" | "commit" => {
            let info = ensure_bot_worktree(&base_repo, bot_id)?;
            let msg = body
                .get("message")
                .and_then(|v| v.as_str())
                .filter(|s| !s.trim().is_empty())
                .unwrap_or("chore(bot): checkpoint worktree changes");
            let wt_path = PathBuf::from(&info.worktree_path);
            let _ = git_cmd(&wt_path)
                .args(["add", "-A"])
                .output();
            let commit_out = git_cmd(&wt_path)
                .args(["commit", "-m", msg])
                .output();
            let committed = commit_out.as_ref().map(|o| o.status.success()).unwrap_or(false);
            let refreshed = ensure_bot_worktree(&base_repo, bot_id).ok();
            Ok(json!({
                "ok": true,
                "committed": committed,
                "worktree": refreshed,
            }))
        }
        "remove" | "delete" | "prune" => {
            let slug = sanitize_slug(bot_id);
            let wt_path = worktrees_root(&base_repo).join(&slug);
            let wt_str = wt_path.to_string_lossy().to_string();
            let _ = git_cmd(&base_repo)
                .args(["worktree", "remove", "--force", &wt_str])
                .output();
            if wt_path.exists() {
                let _ = fs::remove_dir_all(&wt_path);
            }
            Ok(json!({
                "ok": true,
                "removedSlug": slug,
                "worktreePath": wt_str,
            }))
        }
        _ => Err(format!("unknown worktree action: {action}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn slug_sanitization_is_safe() {
        assert_eq!(sanitize_slug("bot-reviewer-codex"), "bot-reviewer-codex");
        assert_eq!(sanitize_slug("../Evil Bot!!"), "evil-bot");
        assert_eq!(sanitize_slug("   "), "bot-default");
    }

    #[test]
    fn terminal_session_exec_and_activity_feed() {
        let open_res = terminal_action(&json!({
            "action": "open",
            "sessionId": "test-term-1",
            "botId": "bot-architect-claude"
        }))
        .unwrap();
        assert_eq!(open_res["ok"], true);

        let env_res = terminal_action(&json!({
            "action": "input",
            "sessionId": "test-term-1",
            "command": "$env:VIBELINK_TEST_VAR=\"hello_world\""
        }))
        .unwrap();
        assert_eq!(env_res["ok"], true);

        let echo_res = terminal_action(&json!({
            "action": "input",
            "sessionId": "test-term-1",
            "command": "echo vibelink_pty_ok"
        }))
        .unwrap();
        assert_eq!(echo_res["ok"], true);
        assert!(echo_res["output"]
            .as_str()
            .unwrap_or("")
            .contains("vibelink_pty_ok"));
        assert_eq!(echo_res["activity"]["kind"], "computer.command");
    }

    #[test]
    fn worktree_resolution_respects_isolation_mode() {
        let base = env!("CARGO_MANIFEST_DIR");
        let shared = resolve_bot_cwd(base, "bot-test", "shared");
        assert_eq!(shared, clamp_cwd(base, &default_workspace_cwd()));
    }
}

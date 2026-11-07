//! Direct DeepSeek API transport and persistent sessions, scoped to an edition/tenant.
use crate::{Shared, model_policy::DEEPSEEK_MODEL};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{collections::HashMap, fs, path::PathBuf, sync::Mutex, time::Duration};
use vibelink_protocol::now_ms;

pub struct Config {
    pub key: String,
    pub model: String,
}
impl Config {
    pub fn load(beta: bool) -> Result<Self, String> {
        let prefix = if beta {
            "VIBELINK_BETA_DEEPSEEK"
        } else {
            "VIBELINK_DEV_DEEPSEEK"
        };
        let key = std::env::var(format!("{prefix}_API_KEY")).unwrap_or_default();
        if key.trim().is_empty() {
            return Err(format!("{prefix}_API_KEY is required"));
        }
        let model =
            std::env::var(format!("{prefix}_MODEL")).unwrap_or_else(|_| DEEPSEEK_MODEL.into());
        validate_model(&model)?;
        Ok(Self { key, model })
    }
}

fn validate_model(model: &str) -> Result<(), String> {
    if model.starts_with("deepseek-")
        && !model.contains('/')
        && !model.contains(char::is_whitespace)
    {
        Ok(())
    } else {
        Err("Only native DeepSeek model IDs are permitted".into())
    }
}

pub fn client() -> reqwest::Client {
    reqwest::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(180))
        .build()
        .expect("DeepSeek HTTP client")
}

#[derive(Clone, Default, Serialize, Deserialize)]
struct Session {
    id: String,
    bot_id: String,
    cwd: String,
    title: String,
    updated: u64,
    messages: Vec<Value>,
    #[serde(skip)]
    running: bool,
    #[serde(skip)]
    generation: u64,
}
pub struct Runtime {
    dir: PathBuf,
    sessions: Mutex<HashMap<String, Session>>,
}
impl Runtime {
    pub fn load(dir: PathBuf) -> Self {
        let sessions = fs::read(dir.join("deepseek_sessions.json"))
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default();
        Self {
            dir,
            sessions: Mutex::new(sessions),
        }
    }
    fn persist(&self, sessions: &HashMap<String, Session>) {
        if let Ok(bytes) = serde_json::to_vec(sessions) {
            crate::write_atomic(&self.dir.join("deepseek_sessions.json"), &bytes);
        }
    }
    pub fn list(&self) -> Value {
        let sessions = self.sessions.lock().unwrap();
        let mut list: Vec<Value> = sessions
            .values()
            .map(|s| {
                json!({
                    "id": s.id, "title": s.title, "cwd": s.cwd, "updated": s.updated,
                    "running": s.running, "agent": "deepseek", "model": DEEPSEEK_MODEL
                })
            })
            .collect();
        list.sort_by_key(|s| std::cmp::Reverse(s["updated"].as_u64().unwrap_or(0)));
        json!({"id": "deepseek", "name": "DeepSeek", "sessions": list})
    }
    pub fn messages(&self, id: &str) -> Result<Value, String> {
        let sessions = self.sessions.lock().unwrap();
        let s = sessions.get(id).ok_or("DeepSeek session not found")?;
        let messages: Vec<Value> = s
            .messages
            .iter()
            .enumerate()
            .filter_map(|(i, m)| {
                let role = m["role"].as_str()?;
                if role != "user" && role != "assistant" {
                    return None;
                }
                let text = m["content"]
                    .as_str()
                    .map(str::to_string)
                    .unwrap_or_else(|| {
                        m["content"]
                            .as_array()
                            .map(|parts| {
                                parts
                                    .iter()
                                    .filter_map(|p| p["text"].as_str())
                                    .collect::<Vec<_>>()
                                    .join("\n")
                            })
                            .unwrap_or_default()
                    });
                let reasoning = m["reasoning_content"].as_str().unwrap_or("");
                Some(
                    json!({"id": format!("{id}_{i}"), "role": role, "text": text,
                "reasoning": reasoning, "at": s.updated, "started": s.updated}),
                )
            })
            .collect();
        Ok(json!({"messages": messages, "running": s.running}))
    }
}

pub async fn dispatch(app: &Shared, kind: &str, payload: Value) -> Result<Value, String> {
    let id = payload["session"].as_str().unwrap_or("");
    match kind {
        "deepseek.messages" => app.deepseek.messages(id),
        "deepseek.interrupt" => {
            let mut sessions = app.deepseek.sessions.lock().unwrap();
            let session = sessions.get_mut(id).ok_or("DeepSeek session not found")?;
            session.generation += 1;
            session.running = false;
            Ok(json!({"ok": true}))
        }
        "deepseek.create" => {
            let id = format!("ds_{}", crate::auth::random_token());
            let bot_id = payload["botId"].as_str().unwrap_or("").to_string();
            let mut session = Session {
                id: id.clone(),
                bot_id,
                cwd: payload["cwd"].as_str().unwrap_or("").into(),
                title: payload["text"]
                    .as_str()
                    .unwrap_or("新对话")
                    .chars()
                    .take(40)
                    .collect(),
                updated: now_ms(),
                ..Session::default()
            };
            if let Some(prompt) = payload
                .pointer("/botContext/systemPrompt")
                .and_then(Value::as_str)
            {
                session
                    .messages
                    .push(json!({"role": "system", "content": prompt}));
            }
            {
                let mut sessions = app.deepseek.sessions.lock().unwrap();
                sessions.insert(id.clone(), session);
                app.deepseek.persist(&sessions);
            }
            if payload["text"].as_str().is_some_and(|s| !s.is_empty()) {
                start(app, &id, &payload)?;
            }
            Ok(json!({"id": id, "agent": "deepseek", "cwd": payload["cwd"], "running": false}))
        }
        "deepseek.prompt" => {
            start(app, id, &payload)?;
            Ok(json!({"id": id, "ok": true}))
        }
        _ => Err("Unknown DeepSeek operation".into()),
    }
}

fn start(app: &Shared, id: &str, payload: &Value) -> Result<(), String> {
    let config = Config::load(app.beta_mode)?;
    let (messages, generation, bot_id) = {
        let mut sessions = app.deepseek.sessions.lock().unwrap();
        let s = sessions.get_mut(id).ok_or("DeepSeek session not found")?;
        if s.running {
            return Err("DeepSeek session busy; create another session or interrupt first".into());
        }
        if let Some(bot_id) = payload["botId"].as_str() {
            if bot_id != s.bot_id {
                return Err("Session belongs to a different Bot".into());
            }
        }
        s.running = true;
        s.generation += 1;
        s.updated = now_ms();
        let text = payload["text"].as_str().unwrap_or("");
        if let Some(prompt) = payload
            .pointer("/botContext/systemPrompt")
            .and_then(Value::as_str)
        {
            let system = json!({"role":"system","content":prompt});
            if s.messages.first().is_some_and(|m| m["role"] == "system") {
                s.messages[0] = system;
            } else {
                s.messages.insert(0, system);
            }
        }
        let mut content = vec![json!({"type":"text","text":text})];
        if let Some(files) = payload["files"].as_array() {
            for file in files {
                let mime = file["mime"].as_str().unwrap_or("application/octet-stream");
                let data = file["data"]
                    .as_str()
                    .or_else(|| file["base64"].as_str())
                    .unwrap_or("");
                if data.is_empty() {
                    continue;
                }
                if mime.starts_with("image/") {
                    content.push(json!({"type":"image_url","image_url":{"url":format!("data:{mime};base64,{data}")}}));
                } else {
                    content.push(json!({"type":"file","file":{"filename":file["filename"].as_str().or_else(|| file["name"].as_str()).unwrap_or("attachment"),"file_data":format!("data:{mime};base64,{data}")}}));
                }
            }
        }
        s.messages.push(json!({"role": "user", "content":if content.len() == 1 { json!(text) } else { json!(content) }}));
        if s.title.is_empty() {
            s.title = text.chars().take(40).collect();
        }
        let result = (s.messages.clone(), s.generation, s.bot_id.clone());
        app.deepseek.persist(&sessions);
        result
    };
    let app = app.clone();
    let id = id.to_string();
    tokio::spawn(async move {
        let result = complete(&app, &config, messages, &bot_id, &id, generation).await;
        let message = result.unwrap_or_else(|e| json!({"role":"assistant", "content": e}));
        let mut sessions = app.deepseek.sessions.lock().unwrap();
        if let Some(s) = sessions.get_mut(&id) {
            if s.generation == generation {
                s.messages.push(message);
                s.running = false;
                s.updated = now_ms();
                app.deepseek.persist(&sessions);
            }
        }
    });
    Ok(())
}

fn tools() -> Value {
    json!([
        {"type":"function", "function":{"name":"workspace_tree", "description":"List files in the assigned workspace", "parameters":{"type":"object", "properties":{}}}},
        {"type":"function", "function":{"name":"workspace_read", "description":"Read a file in the assigned workspace", "parameters":{"type":"object", "properties":{"path":{"type":"string"}}, "required":["path"]}}},
        {"type":"function", "function":{"name":"run_command", "description":"Run a workspace shell command, subject to Bot permissions and approval", "parameters":{"type":"object", "properties":{"command":{"type":"string"}}, "required":["command"]}}}
    ])
}

async fn execute_tool(
    app: &Shared,
    bot_id: &str,
    name: &str,
    args: Value,
    consult: bool,
) -> Result<Value, String> {
    if app.beta_mode {
        return Err("Development workspace tools are unavailable in public beta".into());
    }
    let bot = app
        .bots
        .get_bot(bot_id)
        .ok_or("Workspace tools require a Bot binding")?;
    let cwd = if bot.isolation_mode == "worktree" {
        let result = Box::pin(crate::call_agent(
            app,
            &bot.device,
            "worktree.action",
            json!({"action":"ensure","botId":bot.id,"repoPath":bot.cwd}),
            30,
        ))
        .await?;
        result
            .pointer("/worktree/worktreePath")
            .and_then(Value::as_str)
            .ok_or("Cannot establish isolated worktree")?
            .to_string()
    } else {
        bot.cwd.clone()
    };
    let (kind, payload) = match name {
        "workspace_tree" => ("workspace.tree", json!({"cwd":cwd,"depth":3})),
        "workspace_read" => {
            let path = args["path"].as_str().ok_or("path required")?;
            let root = std::path::Path::new(&cwd)
                .canonicalize()
                .map_err(|_| "Workspace unavailable")?;
            let path = root
                .join(path)
                .canonicalize()
                .map_err(|_| "File unavailable")?;
            if !path.starts_with(&root) {
                return Err("File is outside the Bot workspace".into());
            }
            ("workspace.read", json!({"path":path.to_string_lossy()}))
        }
        "run_command" => {
            if consult
                || bot.approval_mode == "plan"
                || bot.disabled_tools.iter().any(|t| {
                    matches!(
                        t.as_str(),
                        "write_file" | "terminal" | "shell" | "computer.command" | "run_command"
                    )
                })
            {
                return Err("This Bot/consult turn is read-only".into());
            }
            let command = args["command"].as_str().ok_or("command required")?;
            let payload = json!({"action":"exec","sessionId":format!("ds_{}",bot.id),"botId":bot.id,"cwd":cwd,"command":command});
            let (allow, card) = app.approvals.evaluate_or_enqueue(
                "deepseek_command",
                "DeepSeek 工作区命令",
                command,
                &bot.id,
                &bot.id,
                json!({"runtimeKind":"terminal.action", "device":bot.device, "payload":payload}),
                args.clone(),
            );
            if !allow {
                return Ok(json!({"pendingApproval":card.status == "pending", "approval":card}));
            }
            ("terminal.action", payload)
        }
        _ => return Err("Unknown tool".into()),
    };
    Box::pin(crate::call_agent(app, &bot.device, kind, payload, 120)).await
}

async fn complete(
    app: &Shared,
    config: &Config,
    mut messages: Vec<Value>,
    bot_id: &str,
    session_id: &str,
    generation: u64,
) -> Result<Value, String> {
    let consult = messages
        .last()
        .and_then(|m| m["content"].as_str())
        .is_some_and(|s| {
            s.contains("CONSULT / PLAN-ONLY") || s.contains("<delegation_mode>consult")
        });
    let mut logs = Vec::new();
    let active = || {
        app.deepseek
            .sessions
            .lock()
            .unwrap()
            .get(session_id)
            .is_some_and(|s| s.running && s.generation == generation)
    };
    for _ in 0..16 {
        if !active() {
            return Err("本轮已取消".into());
        }
        let mut payload = json!({"model":config.model, "messages":messages, "stream":false});
        if !app.beta_mode && !bot_id.is_empty() {
            payload["tools"] = tools();
        }
        let response = client()
            .post("https://api.deepseek.com/chat/completions")
            .bearer_auth(&config.key)
            .json(&payload)
            .send()
            .await
            .map_err(|_| "DeepSeek 连接失败或超时，请重试。")?;
        if !response.status().is_success() {
            return Err(format!("DeepSeek 请求失败：HTTP {}", response.status()));
        }
        let value: Value = response
            .json()
            .await
            .map_err(|_| "DeepSeek 返回无效 JSON")?;
        let mut message = value
            .pointer("/choices/0/message")
            .cloned()
            .ok_or("DeepSeek 未返回消息")?;
        let calls = message["tool_calls"]
            .as_array()
            .cloned()
            .unwrap_or_default();
        if calls.is_empty() {
            if !logs.is_empty() {
                message["reasoning_content"] = json!(format!(
                    "{}\n{}",
                    message["reasoning_content"].as_str().unwrap_or(""),
                    logs.join("\n")
                ));
            }
            return Ok(message);
        }
        messages.push(message);
        for call in calls {
            if !active() {
                return Err("本轮已取消".into());
            }
            let name = call["function"]["name"].as_str().unwrap_or("");
            let args = serde_json::from_str(call["function"]["arguments"].as_str().unwrap_or("{}"));
            let result = match args {
                Ok(args) => execute_tool(app, bot_id, name, args, consult).await,
                Err(_) => Err("Invalid tool arguments".into()),
            };
            logs.push(format!(
                "🔧 {name}: {}",
                if result.is_ok() { "完成" } else { "失败" }
            ));
            let result = result.unwrap_or_else(|e| json!({"error":e}));
            let content: String = result.to_string().chars().take(32_000).collect();
            messages.push(json!({"role":"tool", "tool_call_id":call["id"], "content":content}));
        }
    }
    Err("工具调用达到本轮上限，请继续下一轮。".into())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn subscription_and_gateway_model_ids_are_rejected() {
        for model in [
            "gpt-5",
            "claude",
            "gemini",
            "auto/deepseek-flash",
            "deepseek-flash/../gpt",
        ] {
            assert!(validate_model(model).is_err());
        }
        assert!(validate_model("deepseek-flash").is_ok());
    }
    #[test]
    fn session_history_is_scoped_to_its_directory() {
        let dir =
            std::env::temp_dir().join(format!("vibelink_deepseek_{}", crate::auth::random_token()));
        let a = Runtime::load(dir.join("a"));
        a.sessions.lock().unwrap().insert(
            "ds_test".into(),
            Session {
                id: "ds_test".into(),
                messages: vec![json!({"role":"user","content":"private"})],
                ..Session::default()
            },
        );
        a.persist(&a.sessions.lock().unwrap());
        assert!(Runtime::load(dir.join("a")).messages("ds_test").is_ok());
        assert!(Runtime::load(dir.join("b")).messages("ds_test").is_err());
        fs::remove_dir_all(dir).unwrap();
    }
}

//! Persistent ownership/model/agent binding; conversation content remains on the agent.
use crate::{Shared, model_gateway, model_policy, model_usage::Identity};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{collections::HashMap, fs, path::PathBuf, sync::Mutex};

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Binding {
    pub id: String,
    pub agent: String,
    pub device: String,
    pub model: String,
    pub scope: String,
    pub owner: Identity,
    pub bot_id: String,
    pub cwd: String,
    pub title: String,
    #[serde(default)]
    pub work_id:String,
}
pub struct Sessions {
    file: PathBuf,
    inner: Mutex<HashMap<String, Binding>>,
}
impl Sessions {
    pub fn load(dir: PathBuf) -> Self {
        let file = dir.join("agent_sessions.json");
        let values = fs::read(&file)
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default();
        Self {
            file,
            inner: Mutex::new(values),
        }
    }
    pub fn get(&self, id: &str) -> Option<Binding> {
        self.inner.lock().unwrap().get(id).cloned()
    }
    pub fn list(&self) -> Vec<Binding> {
        let map = self.inner.lock().unwrap();
        map.iter()
            .filter(|(id, b)| **id == b.id)
            .map(|(_, b)| b.clone())
            .collect()
    }
    pub fn bind(&self, old: &str, binding: Binding) {
        let mut map = self.inner.lock().unwrap();
        map.insert(binding.id.clone(), binding.clone());
        if !old.is_empty() {
            map.insert(old.into(), binding);
        }
        if let Ok(bytes) = serde_json::to_vec(&*map) {
            crate::write_atomic(&self.file, &bytes);
        }
    }
}
pub fn scope(owner:&Identity,model:&str)->String {
    let engine=model_policy::engine_for_model(model).unwrap_or("legacy");
    if !owner.beta && owner.user_id=="owner" && engine=="codex" {return "dev_gpt".into();}
    format!("{}_{}_{}_{}",if owner.beta{"beta"}else{"dev"},&crate::auth::hash_token(&owner.user_id)[..24],engine,&crate::auth::hash_token(model)[..16])
}

pub fn owner(app: &Shared, payload: &Value) -> Identity {
    if let Some(tid) = &app.tenant_id {
        return Identity {
            user_id: tid.clone(),
            label: tid.clone(),
            beta: true,
        };
    }
    let mut identity: Identity = payload
        .get("meterIdentity")
        .and_then(|v| serde_json::from_value(v.clone()).ok())
        .unwrap_or_else(|| Identity {
            user_id: "owner".into(),
            label: "owner".into(),
            beta: app.beta_mode,
        });
    identity.beta |= app.beta_mode;
    identity
}
pub fn runtime(app: &Shared, b: &Binding, grant: bool) -> Value {
    let gateway = std::env::var("VIBELINK_MODEL_GATEWAY_URL").unwrap_or_else(|_| {
        format!(
            "http://127.0.0.1:{}/api/llm/v1",
            std::env::var("VIBELINK_PORT").unwrap_or_else(|_| "18995".into())
        )
    });
    let read_only = app.bots.get_bot(&b.bot_id).is_some_and(|bot| {
        bot.approval_mode == "plan"
            || bot
                .disabled_tools
                .iter()
                .any(|t| matches!(t.as_str(), "write_file" | "shell" | "computer.command"))
    });
    json!({"engine":b.agent,"readOnly":read_only,"scope":b.scope,"model":b.model,"beta":b.owner.beta,"gateway":gateway,
        "token":if grant&&b.model==model_policy::DEEPSEEK_MODEL {app.grants.issue(b.owner.clone())}else{String::new()}})
}
pub fn new_binding(app: &Shared, device: &str, payload: &Value) -> Result<Binding, String> {
    let owner = owner(app, payload);
    let bot_id = payload["botId"].as_str().unwrap_or("");
    let model = if let Some(bot) = app.bots.get_bot(bot_id) {
        bot.model
    } else {
        payload["model"]
            .as_str()
            .unwrap_or(if owner.beta {
                model_policy::DEEPSEEK_MODEL
            } else {
                ""
            })
            .into()
    };
    if payload["model"]
        .as_str()
        .is_some_and(|requested| requested != model)
    {
        return Err("Bot model binding differs from requested model".into());
    }
    model_policy::validate_model(&model, owner.beta)?;
    let device = if owner.beta {
        std::env::var("VIBELINK_BETA_AGENT_DEVICE").unwrap_or_else(|_| "pc".into())
    } else {
        device.into()
    };
    Ok(Binding {
        id: String::new(),
        work_id:payload["groupWorkId"].as_str().unwrap_or("").into(),
        agent: model_policy::engine_for_model(&model)?.into(),
        device,
        scope: scope(&owner, &model),
        model,
        owner,
        bot_id: bot_id.into(),
        cwd: payload["cwd"].as_str().unwrap_or("").into(),
        title: payload["text"]
            .as_str()
            .unwrap_or("")
            .chars()
            .take(60)
            .collect(),
    })
}
pub fn caller(ctx: Option<&crate::auth::AuthCtx>) -> Value {
    ctx.map(model_gateway::identity)
        .map(|i| json!(i))
        .unwrap_or(Value::Null)
}

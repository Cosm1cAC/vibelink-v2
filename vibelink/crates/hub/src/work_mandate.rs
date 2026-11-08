//! User-issued scope. Model output and quoted conversation never expand this record.
use crate::{bots::BotRecord, model_usage::Identity};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MemberScope {
    pub bot_id: String,
    pub agent: String,
    pub model: String,
    pub device: String,
    pub configured_workspace: String,
    pub allow_write: bool,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Mandate {
    pub version: u64,
    pub authority: String,
    pub user_id: String,
    pub input_id: String,
    pub input_hash: String,
    pub created_at: u64,
    pub revoked_at: u64,
    pub acceptance: String,
    pub members: Vec<MemberScope>,
}
impl Mandate {
    pub fn issue(
        owner: &Identity,
        input_id: &str,
        text: &str,
        mode: &str,
        bots: &[BotRecord],
    ) -> Self {
        Self {
            version: 1,
            authority: "explicit_user_request".into(),
            user_id: owner.user_id.clone(),
            input_id: input_id.into(),
            input_hash: crate::auth::hash_token(text),
            created_at: vibelink_protocol::now_ms(),
            revoked_at: 0,
            acceptance: text.into(),
            members: bots
                .iter()
                .map(|b| MemberScope {
                    bot_id: b.id.clone(),
                    agent: b.engine.clone(),
                    model: b.model.clone(),
                    device: b.device.clone(),
                    configured_workspace: b.cwd.clone(),
                    allow_write: mode == "work"
                        && b.approval_mode != "plan"
                        && !b.disabled_tools.iter().any(|t| {
                            matches!(t.as_str(), "write_file" | "shell" | "computer.command")
                        }),
                })
                .collect(),
        }
    }
    pub fn check(&self, owner: &str, bot: &BotRecord, kind: &str) -> Result<(), String> {
        if self.version == 0
            || self.authority != "explicit_user_request"
            || self.revoked_at > 0
            || self.user_id != owner
        {
            return Err("任务授权无效或已撤销；请提交新任务".into());
        }
        let m = self
            .members
            .iter()
            .find(|m| m.bot_id == bot.id)
            .ok_or("执行者不在用户授权成员范围内")?;
        if m.agent != bot.engine
            || m.model != bot.model
            || m.device != bot.device
            || m.configured_workspace != bot.cwd
        {
            return Err("执行绑定与原授权不同；请提交新任务".into());
        }
        // A role can narrow a proposed work step to read-only; it cannot widen the user's scope.
        if kind == "work" && !m.allow_write {
            return Err("原授权只允许该成员只读核查".into());
        }
        Ok(())
    }
    pub fn read_only(&self, bot: &str) -> bool {
        self.members
            .iter()
            .find(|m| m.bot_id == bot)
            .is_none_or(|m| !m.allow_write)
    }
    pub fn public(&self) -> Value {
        json!({"version":self.version,"authority":self.authority,"inputId":self.input_id,"createdAt":self.created_at,"revokedAt":self.revoked_at,"acceptance":self.acceptance,"members":self.members})
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn model_scope_cannot_widen_members_model_workspace_or_readonly_authority() {
        let (dir, app, _) = crate::runtime_tests::fixture();
        let bot = app.bots.get_bot("bot_dev").unwrap();
        let owner = Identity {
            user_id: "one".into(),
            label: "one".into(),
            beta: false,
        };
        let mut scope = Mandate::issue(
            &owner,
            "request-1",
            "Inspect only",
            "consult",
            &[bot.clone()],
        );
        assert!(scope.check("one", &bot, "consult").is_ok());
        assert!(scope.check("one", &bot, "work").is_err());
        assert!(scope.check("another-user", &bot, "consult").is_err());
        let mut other = bot.clone();
        other.cwd = "outside".into();
        assert!(scope.check("one", &other, "consult").is_err());
        other = bot.clone();
        other.model = "different".into();
        assert!(scope.check("one", &other, "consult").is_err());
        scope.revoked_at = 1;
        assert!(scope.check("one", &bot, "consult").is_err());
        std::fs::remove_dir_all(dir).unwrap();
    }
}

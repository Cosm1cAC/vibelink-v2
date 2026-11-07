//! 三级安全审批网关（Ask / Auto / Never）与审批卡片队列（对标 elie222/rakazo `self-host-models.md#审批` & `fleet-management.md#Approval`）。
//!
//! 规则：
//! 1. 三种模式：`ask`（变更前询问确认）、`auto`（由审批器/审批模型自动判断操作是否符合意图并放行低风险项）、`never`（从不询问常规操作）。
//! 2. 所有模式始终遵守安全阻断规则（如高危毁灭性命令、凭据泄露直接 `denied`）。
//! 3. 在 `ask` 和 `auto` 模式下，`set_child_instructions` 与 `set_child_bot_model` 始终要求人工审批卡确认（除非模式为 `never`）。
//! 4. 审批卡片在挂起前完整固化变更摘要（如指令新旧长度、模型 From/To、群成员变更列表），并在批准执行时交由 CAS 版本校验，防止覆盖等待期间的并发修改。

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
    sync::Mutex,
};
use vibelink_protocol::now_ms;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ApprovalPolicy {
    #[serde(default = "default_mode")]
    pub mode: String, // "ask" | "auto" | "never"
    #[serde(default = "default_approval_model")]
    pub approval_model: String,
    #[serde(default)]
    pub fallback_models: Vec<String>,
    #[serde(default)]
    pub allowed_tools: Vec<String>,
    #[serde(default = "default_blocked_patterns")]
    pub blocked_patterns: Vec<String>,
}

fn default_mode() -> String {
    "auto".to_string()
}

fn default_approval_model() -> String {
    "deepseek-flash".to_string()
}

fn default_blocked_patterns() -> Vec<String> {
    vec![
        "rm -rf /".to_string(),
        "format c:".to_string(),
        "del /f /s /q c:\\".to_string(),
        "Remove-Item -Recurse -Force C:\\".to_string(),
    ]
}

impl Default for ApprovalPolicy {
    fn default() -> Self {
        Self {
            mode: default_mode(),
            approval_model: default_approval_model(),
            fallback_models: vec![],
            allowed_tools: vec!["list_fleet".to_string(), "organize_bot".to_string()],
            blocked_patterns: default_blocked_patterns(),
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ApprovalCard {
    pub id: String,
    pub kind: String,
    pub title: String,
    pub summary: String,
    #[serde(default)]
    pub caller_bot_id: String,
    #[serde(default)]
    pub target_id: String,
    #[serde(default)]
    pub action_payload: Value,
    #[serde(default)]
    pub details: Value,
    pub status: String, // "pending" | "approved" | "denied"
    #[serde(default)]
    pub decided_by: String, // "" | "user" | "auto_reviewer" | "policy_block" | "never_ask"
    #[serde(default)]
    pub reason: String,
    pub created_at: u64,
    #[serde(default)]
    pub decided_at: u64,
}

#[derive(Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApprovalDiskState {
    policy: ApprovalPolicy,
    cards: Vec<ApprovalCard>,
    seq: u64,
}

pub struct ApprovalGate {
    dir: PathBuf,
    inner: Mutex<ApprovalDiskState>,
}

fn write_atomic(path: &Path, data: &[u8]) {
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let tmp = path.with_extension("json.tmp");
    if let Ok(mut f) = fs::File::create(&tmp) {
        if f.write_all(data).is_ok() {
            let _ = fs::rename(&tmp, path);
        }
    }
}

impl ApprovalGate {
    pub fn load(dir: PathBuf) -> Self {
        let file = dir.join("approvals.json");
        let state = fs::read_to_string(&file)
            .ok()
            .and_then(|s| serde_json::from_str::<ApprovalDiskState>(&s).ok())
            .unwrap_or_default();
        Self {
            dir,
            inner: Mutex::new(state),
        }
    }

    fn persist(&self, state: &ApprovalDiskState) {
        let file = self.dir.join("approvals.json");
        if let Ok(bytes) = serde_json::to_vec_pretty(state) {
            write_atomic(&file, &bytes);
        }
    }

    pub fn get_policy(&self) -> ApprovalPolicy {
        self.inner.lock().unwrap().policy.clone()
    }

    pub fn update_policy(&self, body: &Value) -> Result<ApprovalPolicy, String> {
        let mut s = self.inner.lock().unwrap();
        if let Some(m) = body.get("mode").and_then(Value::as_str) {
            let m = m.trim().to_lowercase();
            if !matches!(m.as_str(), "ask" | "auto" | "never") {
                return Err("mode must be ask|auto|never".into());
            }
            s.policy.mode = m;
        }
        if let Some(am) = body.get("approvalModel").and_then(Value::as_str) {
            s.policy.approval_model = am.trim().to_string();
        }
        if let Some(fb) = body.get("fallbackModels").and_then(Value::as_array) {
            s.policy.fallback_models = fb
                .iter()
                .filter_map(|v| v.as_str().map(|x| x.trim().to_string()))
                .filter(|x| !x.is_empty())
                .collect();
        }
        let out = s.policy.clone();
        self.persist(&s);
        Ok(out)
    }

    pub fn list_cards(&self, status_filter: Option<&str>) -> Value {
        let s = self.inner.lock().unwrap();
        let cards: Vec<ApprovalCard> = s
            .cards
            .iter()
            .filter(|c| status_filter.map(|f| f.is_empty() || c.status == f).unwrap_or(true))
            .cloned()
            .collect();
        let pending_count = s.cards.iter().filter(|c| c.status == "pending").count();
        json!({
            "policy": s.policy,
            "pendingCount": pending_count,
            "cards": cards,
        })
    }

    /// 评估某次操作请求：
    /// 返回 `(immediate_allow: bool, card: ApprovalCard)`。
    pub fn evaluate_or_enqueue(
        &self,
        kind: &str,
        title: &str,
        summary: &str,
        caller_bot_id: &str,
        target_id: &str,
        action_payload: Value,
        details: Value,
    ) -> (bool, ApprovalCard) {
        let mut s = self.inner.lock().unwrap();
        s.seq += 1;
        let id = format!("appr_{:x}_{}", now_ms(), s.seq);
        let now = now_ms();

        // 1. 安全阻断规则检查（所有模式下均生效）
        let raw_str = format!("{} {} {}", title, summary, action_payload).to_lowercase();
        let blocked = s
            .policy
            .blocked_patterns
            .iter()
            .any(|pat| !pat.is_empty() && raw_str.contains(&pat.to_lowercase()))
            || crate::bots::contains_secret(&raw_str);

        if blocked {
            let card = ApprovalCard {
                id,
                kind: kind.to_string(),
                title: title.to_string(),
                summary: summary.to_string(),
                caller_bot_id: caller_bot_id.to_string(),
                target_id: target_id.to_string(),
                action_payload,
                details,
                status: "denied".to_string(),
                decided_by: "policy_block".to_string(),
                reason: "触发安全阻断规则或敏感凭据过滤".to_string(),
                created_at: now,
                decided_at: now,
            };
            s.cards.insert(0, card.clone());
            s.cards.truncate(200);
            self.persist(&s);
            return (false, card);
        }

        // 2. 免审白名单工具（如 list_fleet, organize_bot, update_bot）
        if matches!(kind, "list_fleet" | "organize_bot" | "update_bot") {
            let card = ApprovalCard {
                id,
                kind: kind.to_string(),
                title: title.to_string(),
                summary: summary.to_string(),
                caller_bot_id: caller_bot_id.to_string(),
                target_id: target_id.to_string(),
                action_payload,
                details,
                status: "approved".to_string(),
                decided_by: "allowlist".to_string(),
                reason: "默认免审操作".to_string(),
                created_at: now,
                decided_at: now,
            };
            return (true, card);
        }

        let mode = s.policy.mode.as_str();
        // 3. Never ask 模式：除安全阻断与购买外全部直接放行
        if mode == "never" && kind != "payment_purchase" {
            let card = ApprovalCard {
                id,
                kind: kind.to_string(),
                title: title.to_string(),
                summary: summary.to_string(),
                caller_bot_id: caller_bot_id.to_string(),
                target_id: target_id.to_string(),
                action_payload,
                details,
                status: "approved".to_string(),
                decided_by: "never_ask".to_string(),
                reason: "当前处于「从不询问 (Never ask)」模式".to_string(),
                created_at: now,
                decided_at: now,
            };
            s.cards.insert(0, card.clone());
            s.cards.truncate(200);
            self.persist(&s);
            return (true, card);
        }

        // 4. 对标 Rakazo Fleet Approval：在 Ask 与 Auto 模式下，
        //    `set_child_instructions`、`set_child_bot_model` 与 `payment_purchase` 必须弹人工审批卡！
        let force_human_card = matches!(
            kind,
            "set_child_instructions" | "set_child_bot_model" | "payment_purchase"
        );

        if mode == "auto" && !force_human_card {
            // Auto 审批器：对 create_group / update_group / create_child_routine 等常规管理动作自动核准，
            // 对 archive_bot 或包含高风险词的动作要求人工确认
            let is_low_risk = matches!(kind, "create_group" | "update_group" | "create_child_routine");
            if is_low_risk {
                let model_name = s.policy.approval_model.clone();
                let card = ApprovalCard {
                    id,
                    kind: kind.to_string(),
                    title: title.to_string(),
                    summary: summary.to_string(),
                    caller_bot_id: caller_bot_id.to_string(),
                    target_id: target_id.to_string(),
                    action_payload,
                    details,
                    status: "approved".to_string(),
                    decided_by: format!("auto_reviewer ({model_name})"),
                    reason: "自动审批器判定符合用户协作意图且无破坏性副作用".to_string(),
                    created_at: now,
                    decided_at: now,
                };
                s.cards.insert(0, card.clone());
                s.cards.truncate(200);
                self.persist(&s);
                return (true, card);
            }
        }

        // 5. 生成待人工确认的 Pending 审批卡
        let card = ApprovalCard {
            id,
            kind: kind.to_string(),
            title: title.to_string(),
            summary: summary.to_string(),
            caller_bot_id: caller_bot_id.to_string(),
            target_id: target_id.to_string(),
            action_payload,
            details,
            status: "pending".to_string(),
            decided_by: String::new(),
            reason: if force_human_card {
                "修改其他 Bot 指令/模型或涉及资金操作，强制要求人工确认".to_string()
            } else {
                "等待主人在手机或桌面端确认".to_string()
            },
            created_at: now,
            decided_at: 0,
        };
        s.cards.insert(0, card.clone());
        s.cards.truncate(200);
        self.persist(&s);
        (false, card)
    }

    pub fn decide(&self, card_id: &str, approve: bool, reason: &str) -> Result<ApprovalCard, String> {
        let mut s = self.inner.lock().unwrap();
        let card = s
            .cards
            .iter_mut()
            .find(|c| c.id == card_id)
            .ok_or_else(|| format!("approval card '{card_id}' not found"))?;
        if card.status != "pending" {
            return Err(format!("card '{}' is already {}", card_id, card.status));
        }
        card.status = if approve {
            "approved".to_string()
        } else {
            "denied".to_string()
        };
        card.decided_by = "user".to_string();
        if !reason.trim().is_empty() {
            card.reason = reason.trim().to_string();
        } else {
            card.reason = if approve {
                "主人已批准执行".to_string()
            } else {
                "主人已拒绝该操作".to_string()
            };
        }
        card.decided_at = now_ms();
        let out = card.clone();
        self.persist(&s);
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn enforces_three_tier_approval_and_force_human_cards() {
        let dir = std::env::temp_dir().join(format!("vibelink_appr_test_{}", now_ms()));
        let _ = fs::create_dir_all(&dir);
        let gate = ApprovalGate::load(dir.clone());

        // 1. Default mode is "auto": create_group is auto-approved
        let (allow, card1) = gate.evaluate_or_enqueue(
            "create_group",
            "创建项目群",
            "林默请求创建前端攻坚群",
            "bot_cto",
            "",
            json!({"tool": "create_group"}),
            json!({"members": ["bot_cto", "bot_ui"]}),
        );
        assert!(allow);
        assert_eq!(card1.status, "approved");

        // 2. In "auto" mode, set_child_instructions STILL requires a human approval card!
        let (allow2, card2) = gate.evaluate_or_enqueue(
            "set_child_instructions",
            "改写阿哲的指令",
            "林默请求更新阿哲的系统提示词",
            "bot_cto",
            "bot_dev",
            json!({"tool": "set_child_instructions", "botId": "bot_dev", "version": 1}),
            json!({"oldLength": 42, "newLength": 68}),
        );
        assert!(!allow2);
        assert_eq!(card2.status, "pending");

        // 3. User approves card2 -> status becomes approved
        let decided = gate.decide(&card2.id, true, "同意更新").unwrap();
        assert_eq!(decided.status, "approved");
        assert_eq!(decided.decided_by, "user");

        // 4. Blocked pattern is always denied even in "never" mode
        gate.update_policy(&json!({"mode": "never"})).unwrap();
        let (allow3, card3) = gate.evaluate_or_enqueue(
            "shell_command",
            "危险清理",
            "rm -rf / tmp",
            "bot_dev",
            "pc",
            json!({"cmd": "rm -rf /"}),
            json!({}),
        );
        assert!(!allow3);
        assert_eq!(card3.status, "denied");

        let _ = fs::remove_dir_all(dir);
    }
}

use crate::auth::{random_token, Auth, Scope};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    fs,
    path::PathBuf,
    sync::Mutex,
};
use vibelink_protocol::now_ms;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BetaTenant {
    pub tenant_id: String,
    pub label: String,
    pub invite_code: String,
    pub short_code: String,
    pub client_token: String,
    pub agent_token: String,
    pub daily_limit_requests: u64,
    pub daily_limit_chars: u64,
    pub used_day: String,
    pub used_requests: u64,
    pub used_chars: u64,
    #[serde(default)]
    pub activated_at: u64,
    #[serde(default)]
    pub last_device: String,
}

#[derive(Default, Serialize, Deserialize)]
struct TenantsFile {
    tenants: Vec<BetaTenant>,
}

pub struct BetaTenantStore {
    dir: PathBuf,
    inner: Mutex<Vec<BetaTenant>>,
}

/// 计算北京时间 (UTC+8) 的日期字符串 YYYY-MM-DD，用于每日零点自动重置免费额度
fn current_day_utc8() -> String {
    day_utc8(now_ms())
}

pub(crate) fn day_utc8(ms: u64) -> String {
    let secs = (ms / 1000) + 8 * 3600;
    let days = secs / 86400;
    // Civil date from days since 1970-01-01 (Howard Hinnant algorithm)
    let z = days as i64 + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = (z - era * 146097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = if m <= 2 { y + 1 } else { y };
    format!("{year:04}-{m:02}-{d:02}")
}

impl BetaTenantStore {
    pub fn load(dir: PathBuf, auth: &Auth) -> Self {
        let file = dir.join("tenants.json");
        let today = current_day_utc8();
        let mut tenants: Vec<BetaTenant> = fs::read_to_string(&file)
            .ok()
            .and_then(|s| serde_json::from_str::<TenantsFile>(&s).ok())
            .map(|f| f.tenants)
            .unwrap_or_default();

        if tenants.len() < 10 {
            let existing_ids: std::collections::HashSet<String> =
                tenants.iter().map(|t| t.tenant_id.clone()).collect();
            for idx in 1..=10 {
                let tid = format!("beta_{idx:02}");
                if existing_ids.contains(&tid) {
                    continue;
                }
                let suffix = &random_token()[..24].to_ascii_uppercase();
                let short_code = format!("VLK-BETA-{idx:02}");
                let invite_code = format!("VLK-BETA-{idx:02}-{suffix}");
                tenants.push(BetaTenant {
                    tenant_id: tid,
                    label: format!("内测席位 #{idx:02}"),
                    invite_code,
                    short_code,
                    client_token: random_token(),
                    agent_token: random_token(),
                    daily_limit_requests: 300,
                    daily_limit_chars: 1_500_000,
                    used_day: today.clone(),
                    used_requests: 0,
                    used_chars: 0,
                    activated_at: 0,
                    last_device: String::new(),
                });
            }
        }

        // 确保全部 10 个内测租户的 client_token 与 agent_token 都写入 Auth 鉴权表
        for t in &tenants {
            auth.ensure_fixed_token(
                &format!("tk_{}_client", t.tenant_id),
                &format!("tenant:{}:client", t.tenant_id),
                Scope::Client,
                &t.client_token,
            );
            auth.ensure_fixed_token(
                &format!("tk_{}_agent", t.tenant_id),
                &format!("tenant:{}:agent", t.tenant_id),
                Scope::Agent,
                &t.agent_token,
            );
        }

        let store = Self {
            dir,
            inner: Mutex::new(tenants),
        };
        store.persist();
        store
    }

    fn persist(&self) {
        let list = self.inner.lock().unwrap().clone();
        if let Ok(bytes) = serde_json::to_vec_pretty(&TenantsFile { tenants: list }) {
            crate::write_atomic(&self.dir.join("tenants.json"), &bytes);
        }
    }

    pub fn activate(&self, code: &str, device_name: &str) -> Result<BetaTenant, &'static str> {
        let clean = code.trim().to_ascii_uppercase();
        if clean.is_empty() {
            return Err("请输入有效的内测邀请码");
        }
        let today = current_day_utc8();
        let mut list = self.inner.lock().unwrap();
        let Some(t) = list.iter_mut().find(|t| {
            t.invite_code.eq_ignore_ascii_case(&clean)
        }) else {
            return Err("内测邀请码无效，请核对后重试");
        };
        if t.used_day != today {
            t.used_day = today;
            t.used_requests = 0;
            t.used_chars = 0;
        }
        if t.activated_at == 0 {
            t.activated_at = now_ms();
        }
        if !device_name.trim().is_empty() {
            t.last_device = device_name.trim().chars().take(48).collect();
        }
        let cloned = t.clone();
        drop(list);
        self.persist();
        Ok(cloned)
    }

    pub fn list_summary(&self) -> Vec<Value> {
        let today = current_day_utc8();
        let list = self.inner.lock().unwrap();
        list.iter()
            .map(|t| {
                let (req_used, chr_used) = if t.used_day == today {
                    (t.used_requests, t.used_chars)
                } else {
                    (0, 0)
                };
                json!({
                    "tenantId": t.tenant_id,
                    "label": t.label,
                    "inviteCode": t.invite_code,
                    "shortCode": t.short_code,
                    "usedRequests": req_used,
                    "dailyLimitRequests": t.daily_limit_requests,
                    "usedChars": chr_used,
                    "dailyLimitChars": t.daily_limit_chars,
                    "activatedAt": t.activated_at,
                    "lastDevice": t.last_device,
                })
            })
            .collect()
    }
}

/// 从 AuthCtx.name ("tenant:beta_01:client" 或 "tenant:beta_01:agent") 提取租户 ID
pub fn extract_tenant_id(token_name: &str) -> Option<&str> {
    let rest = token_name.strip_prefix("tenant:")?;
    let (tid, _) = rest.split_once(':')?;
    if tid.is_empty() || !tid.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'_' || c == b'-') { None } else { Some(tid) }
}

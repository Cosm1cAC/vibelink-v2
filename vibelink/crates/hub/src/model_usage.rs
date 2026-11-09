//! Meter each upstream request, including streaming responses, retries and compaction.
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    fs, io,
    path::PathBuf,
    sync::{Arc, Mutex},
};
use vibelink_protocol::now_ms;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Identity {
    pub user_id: String,
    pub label: String,
    pub beta: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Limits {
    pub daily_requests: u64,
    pub daily_tokens: u64,
    pub daily_input_bytes: u64,
    pub concurrent_requests: u64,
    pub requests_per_minute: u64,
}
impl Limits {
    fn for_user(beta: bool) -> Self {
        Self {
            daily_requests: if beta { 300 } else { 0 },
            daily_tokens: if beta { 1_500_000 } else { 0 },
            // Serialized CLI context is larger than its token count, and is resent for
            // tool turns. Keep this transport guard above a normal daily token budget.
            daily_input_bytes: if beta { 12_000_000 } else { 0 },
            concurrent_requests: if beta { 2 } else { 8 },
            requests_per_minute: if beta { 30 } else { 120 },
        }
    }
}

#[derive(Clone, Default, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Tokens {
    pub input: u64,
    pub output: u64,
    pub cached: u64,
    pub total: u64,
}
impl Tokens {
    pub fn parse(value: &Value) -> Option<Self> {
        let usage = value
            .get("usage")
            .or_else(|| value.pointer("/response/usage"))?;
        if !usage.is_object()
            || (usage.get("input_tokens").is_none() && usage.get("prompt_tokens").is_none())
        {
            return None;
        }
        let input = usage
            .get("input_tokens")
            .or_else(|| usage.get("prompt_tokens"))
            .and_then(Value::as_u64)?;
        let output = usage
            .get("output_tokens")
            .or_else(|| usage.get("completion_tokens"))
            .and_then(Value::as_u64)
            .unwrap_or(0);
        let cached = usage
            .pointer("/input_tokens_details/cached_tokens")
            .or_else(|| usage.pointer("/prompt_tokens_details/cached_tokens"))
            .or_else(|| usage.get("prompt_cache_hit_tokens"))
            .and_then(Value::as_u64)
            .unwrap_or(0);
        Some(Self {
            input,
            output,
            cached,
            total: usage["total_tokens"]
                .as_u64()
                .unwrap_or(input.saturating_add(output)),
        })
    }
}

#[derive(Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Daily {
    identity: Option<Identity>,
    requests: u64,
    succeeded: u64,
    failed: u64,
    interrupted: u64,
    input_bytes: u64,
    input_tokens: u64,
    output_tokens: u64,
    cached_tokens: u64,
    actual_tokens: u64,
    estimated_tokens: u64,
    missing_usage: u64,
    reserved_tokens: u64,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Pending {
    id: String,
    identity: Identity,
    day: String,
    protocol: String,
    started_at: u64,
    reserved: u64,
    #[serde(default)]
    task: Option<WorkCharge>,
}
#[derive(Clone,Debug,Serialize,Deserialize)]
#[serde(rename_all="camelCase")]
pub struct WorkCharge {pub work_id:String,pub max_tokens:u64,pub max_requests:u64}
#[derive(Clone,Default,Serialize,Deserialize)]
#[serde(rename_all="camelCase")]
struct TaskTotals {user_id:String,requests:u64,tokens:u64,estimated:u64,reserved:u64,cached:u64,duration_ms:u64,missing:u64}
#[derive(Clone, Default, Serialize, Deserialize)]
struct Disk {
    daily: BTreeMap<String, Daily>,
    pending: BTreeMap<String, Pending>,
    limits: BTreeMap<String, Limits>,
    #[serde(default)]
    limit_revisions: BTreeMap<String, u64>,
    starts: BTreeMap<String, Vec<u64>>,
    recent: Vec<Value>,
    #[serde(default)]
    tasks: BTreeMap<String,TaskTotals>,
}
#[derive(Debug)]
pub enum LimitsError {
    Invalid,
    Conflict { current: u64 },
    RevisionExhausted,
    Persistence(String),
}
impl std::fmt::Display for LimitsError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Invalid => write!(f, "Invalid limits"),
            Self::Conflict { current } => write!(f, "Limits changed; current revision is {current}"),
            Self::RevisionExhausted => write!(f, "Limits revision exhausted"),
            Self::Persistence(error) => write!(f, "Unable to save limits: {error}"),
        }
    }
}
pub struct UsageStore {
    file: PathBuf,
    inner: Mutex<Disk>,
}
pub fn day_at(ms: u64) -> String {
    crate::tenants::day_utc8(ms)
}
fn key(day: &str, user: &str) -> String {
    format!("{day}/{user}")
}

impl UsageStore {
    pub fn load(dir: PathBuf) -> io::Result<Self> {
        fs::create_dir_all(&dir)?;
        let file = dir.join("model_usage.json");
        let disk = match fs::read(&file) {
            Ok(bytes) => serde_json::from_slice::<Disk>(&bytes).map_err(io::Error::other)?,
            Err(e) if e.kind() == io::ErrorKind::NotFound => Disk::default(),
            Err(e) => return Err(e),
        };
        let store = Self {
            file,
            inner: Mutex::new(disk),
        };
        // A restarted process cannot know if the upstream billed unfinished requests.
        let ids: Vec<_> = store
            .inner
            .lock()
            .unwrap()
            .pending
            .keys()
            .cloned()
            .collect();
        for id in ids {
            store.finish(&id, None, "interrupted", None, true)?;
        }
        Ok(store)
    }
    fn persist(&self, disk: &Disk) -> io::Result<()> {
        let tmp = self.file.with_extension("json.tmp");
        let bytes = serde_json::to_vec(disk).map_err(io::Error::other)?;
        fs::write(&tmp, bytes)?;
        fs::rename(tmp, &self.file)
    }
    pub fn begin(
        self: &Arc<Self>,
        identity: Identity,
        protocol: &str,
        bytes: u64,
        output_budget: u64,
    ) -> Result<Meter, String> {
        self.begin_work(identity,protocol,bytes,output_budget,None)
    }
    pub fn begin_work(self:&Arc<Self>,identity:Identity,protocol:&str,bytes:u64,output_budget:u64,task:Option<WorkCharge>)->Result<Meter,String> {
        let mut disk = self.inner.lock().unwrap();
        let limits = disk
            .limits
            .get(&identity.user_id)
            .cloned()
            .unwrap_or_else(|| Limits::for_user(identity.beta));
        let now = now_ms();
        let day = day_at(now);
        let k = key(&day, &identity.user_id);
        let reserved = bytes.saturating_add(output_budget); // conservative byte upper bound, NOT reported as actual tokens
        if let Some(task)=&task {
            let stats=disk.tasks.get(&task.work_id).cloned().unwrap_or_default();
            if (!stats.user_id.is_empty()&&stats.user_id!=identity.user_id)
                ||(task.max_requests>0&&stats.requests>=task.max_requests)
                ||(task.max_tokens>0&&stats.tokens.saturating_add(stats.estimated).saturating_add(stats.reserved).saturating_add(reserved)>task.max_tokens) {
                return Err("Work token/request budget exceeded".into());
            }
        }
        let stats = disk.daily.get(&k).cloned().unwrap_or_default();
        let active = disk
            .pending
            .values()
            .filter(|p| p.identity.user_id == identity.user_id)
            .count() as u64;
        let starts = disk.starts.entry(identity.user_id.clone()).or_default();
        starts.retain(|time| now.saturating_sub(*time) < 60_000);
        if (limits.daily_requests > 0 && stats.requests >= limits.daily_requests)
            || (limits.daily_tokens > 0
                && stats
                    .actual_tokens
                    .saturating_add(stats.estimated_tokens)
                    .saturating_add(stats.reserved_tokens)
                    .saturating_add(reserved)
                    > limits.daily_tokens)
            || (limits.daily_input_bytes > 0
                && stats.input_bytes.saturating_add(bytes) > limits.daily_input_bytes)
            || active >= limits.concurrent_requests
            || starts.len() as u64 >= limits.requests_per_minute
        {
            return Err("Model quota, rate or concurrency limit exceeded".into());
        }
        if disk.pending.len() >= 16 {
            return Err("Model gateway is at capacity".into());
        }
        let pending = Pending {
            id: crate::auth::random_token(),
            identity: identity.clone(),
            day: day.clone(),
            protocol: protocol.into(),
            started_at: now,
            reserved,
            task:task.clone(),
        };
        if let Some(task)=task {
            let stats=disk.tasks.entry(task.work_id).or_default();stats.user_id=identity.user_id.clone();stats.requests+=1;stats.reserved=stats.reserved.saturating_add(reserved);
        }
        disk.starts
            .entry(identity.user_id.clone())
            .or_default()
            .push(now);
        let stats = disk.daily.entry(k).or_default();
        stats.identity = Some(identity);
        stats.requests += 1;
        stats.input_bytes = stats.input_bytes.saturating_add(bytes);
        stats.reserved_tokens = stats.reserved_tokens.saturating_add(reserved);
        disk.pending.insert(pending.id.clone(), pending.clone());
        self.persist(&disk)
            .map_err(|_| "Cannot persist model usage; request blocked".to_string())?;
        Ok(Meter {
            store: self.clone(),
            id: pending.id,
            tokens: None,
            status: None,
            settled: false,
            sse: SseUsage::default(),
        })
    }
    fn finish(
        &self,
        id: &str,
        tokens: Option<Tokens>,
        outcome: &str,
        status: Option<u16>,
        uncertain: bool,
    ) -> io::Result<()> {
        let mut disk = self.inner.lock().unwrap();
        let Some(pending) = disk.pending.remove(id) else {
            return Ok(());
        };
        let stats = disk
            .daily
            .entry(key(&pending.day, &pending.identity.user_id))
            .or_default();
        stats.reserved_tokens = stats.reserved_tokens.saturating_sub(pending.reserved);
        match outcome {
            "completed" => stats.succeeded += 1,
            "interrupted" => stats.interrupted += 1,
            _ => stats.failed += 1,
        }
        if let Some(t) = &tokens {
            stats.input_tokens = stats.input_tokens.saturating_add(t.input);
            stats.output_tokens = stats.output_tokens.saturating_add(t.output);
            stats.cached_tokens = stats.cached_tokens.saturating_add(t.cached);
            stats.actual_tokens = stats.actual_tokens.saturating_add(t.total);
        } else if uncertain {
            stats.missing_usage += 1;
            stats.estimated_tokens = stats.estimated_tokens.saturating_add(pending.reserved);
        }
        if let Some(task)=&pending.task {
            let totals=disk.tasks.entry(task.work_id.clone()).or_default();totals.reserved=totals.reserved.saturating_sub(pending.reserved);
            totals.duration_ms=totals.duration_ms.saturating_add(now_ms().saturating_sub(pending.started_at));
            if let Some(t)=&tokens {totals.tokens=totals.tokens.saturating_add(t.total);totals.cached=totals.cached.saturating_add(t.cached);}
            else if uncertain {totals.missing+=1;totals.estimated=totals.estimated.saturating_add(pending.reserved);}
        }
        disk.recent.push(json!({"requestId":id,"userId":pending.identity.user_id,"workId":pending.task.as_ref().map(|t|t.work_id.as_str()).unwrap_or(""),"day":pending.day,"model":crate::model_policy::DEEPSEEK_MODEL,
            "protocol":pending.protocol,"startedAt":pending.started_at,"finishedAt":now_ms(),"durationMs":now_ms().saturating_sub(pending.started_at),"outcome":outcome,"httpStatus":status,
            "tokens":tokens,"usageMissing":tokens.is_none() && uncertain,"estimatedTokens":if tokens.is_none() && uncertain {pending.reserved} else {0}}));
        if disk.recent.len() > 1000 {
            disk.recent.remove(0);
        }
        self.persist(&disk)
    }
    pub fn summary(&self, identity: &Identity, day: &str) -> Value {
        let disk = self.inner.lock().unwrap();
        let stats = disk
            .daily
            .get(&key(day, &identity.user_id))
            .cloned()
            .unwrap_or_default();
        let limits = disk
            .limits
            .get(&identity.user_id)
            .cloned()
            .unwrap_or_else(|| Limits::for_user(identity.beta));
        let charged = stats.actual_tokens.saturating_add(stats.estimated_tokens);
        let token_ratio = if limits.daily_tokens > 0 {
            charged as f64 / limits.daily_tokens as f64
        } else {
            0.0
        };
        let request_ratio = if limits.daily_requests > 0 {
            stats.requests as f64 / limits.daily_requests as f64
        } else {
            0.0
        };
        let byte_ratio = if limits.daily_input_bytes > 0 {
            stats.input_bytes as f64 / limits.daily_input_bytes as f64
        } else {
            0.0
        };
        let ratio = token_ratio.max(request_ratio).max(byte_ratio);
        json!({"userId":identity.user_id,"label":identity.label,"day":day,"model":crate::model_policy::DEEPSEEK_MODEL,
            "usedRequests":stats.requests,"successfulRequests":stats.succeeded,"failedRequests":stats.failed,"interruptedRequests":stats.interrupted,
            "inputTokens":stats.input_tokens,"outputTokens":stats.output_tokens,"cachedTokens":stats.cached_tokens,"totalTokens":stats.actual_tokens,
            "estimatedTokens":stats.estimated_tokens,"usageMissingRequests":stats.missing_usage,"reservedTokens":stats.reserved_tokens,"chargedTokens":charged,
            "usedChars":stats.input_bytes,"inputBytes":stats.input_bytes,"dailyLimitRequests":limits.daily_requests,"dailyLimitTokens":limits.daily_tokens,
            "dailyLimitChars":limits.daily_input_bytes,"limits":limits,"limitsRevision":disk.limit_revisions.get(&identity.user_id).copied().unwrap_or(0),"warning":if ratio>=1.0 {"exhausted"} else if ratio>=0.8 {"near_limit"} else {""}})
    }
    pub fn work_summary(&self,work_id:&str)->Value {
        let disk=self.inner.lock().unwrap();let t=disk.tasks.get(work_id).cloned().unwrap_or_default();
        json!({"meteredProvider":"deepseek","requests":t.requests,"totalTokens":t.tokens,"cachedTokens":t.cached,"estimatedTokens":t.estimated,"reservedTokens":t.reserved,"usageMissingRequests":t.missing,"modelDurationMs":t.duration_ms,"subscriptionUsage":"not_reported"})
    }
    pub fn users(&self, day: &str) -> Value {
        let identities: Vec<_> = self
            .inner
            .lock()
            .unwrap()
            .daily
            .values()
            .filter_map(|s| s.identity.clone())
            .collect();
        let mut ids = BTreeMap::new();
        for i in identities {
            ids.insert(i.user_id.clone(), i);
        }
        json!({"day":day,"users":ids.values().map(|i| self.summary(i,day)).collect::<Vec<_>>()})
    }
    pub fn recent(&self) -> Value {
        json!({"requests":self.inner.lock().unwrap().recent.iter().rev().take(200).cloned().collect::<Vec<_>>()})
    }
    pub fn import_legacy(&self, rows: Vec<Value>) -> io::Result<()> {
        let mut disk = self.inner.lock().unwrap();
        let day = day_at(now_ms());
        for row in rows {
            let id = row["tenantId"].as_str().unwrap_or("").to_string();
            if id.is_empty() {
                continue;
            }
            disk.limits.entry(id.clone()).or_insert(Limits {
                daily_requests: row["dailyLimitRequests"].as_u64().unwrap_or(300),
                daily_input_bytes: match row["dailyLimitChars"].as_u64() {
                    // Normalize only the old built-in seed when first importing limits.
                    // Existing ledger/admin overrides are preserved by entry.or_insert.
                    Some(1_500_000) | None => 12_000_000,
                    Some(value) => value,
                },
                ..Limits::for_user(true)
            });
            let requests = row["usedRequests"].as_u64().unwrap_or(0);
            let bytes = row["usedChars"].as_u64().unwrap_or(0);
            if requests > 0 {
                disk.daily.entry(key(&day, &id)).or_insert(Daily {
                    identity: Some(Identity {
                        user_id: id,
                        label: row["label"].as_str().unwrap_or("").into(),
                        beta: true,
                    }),
                    requests,
                    input_bytes: bytes,
                    estimated_tokens: bytes,
                    missing_usage: requests,
                    ..Daily::default()
                });
            }
        }
        self.persist(&disk)
    }
    pub fn set_limits(&self, user: &str, limits: Limits) -> Result<(), String> {
        self.set_limits_if_revision(user, limits, None).map(|_| ()).map_err(|error| error.to_string())
    }
    pub fn set_limits_if_revision(&self, user: &str, limits: Limits, expected: Option<u64>) -> Result<u64, LimitsError> {
        if user.is_empty() || limits.concurrent_requests == 0 || limits.concurrent_requests > 16 || limits.requests_per_minute == 0 {
            return Err(LimitsError::Invalid);
        }
        let mut disk = self.inner.lock().unwrap();
        let current = disk.limit_revisions.get(user).copied().unwrap_or(0);
        if expected.is_some_and(|revision| revision != current) {
            return Err(LimitsError::Conflict { current });
        }
        let revision = current.checked_add(1).ok_or(LimitsError::RevisionExhausted)?;
        // Persist a candidate under the same lock. Failed writes cannot change
        // effective limits, consume a revision, or overwrite concurrent usage.
        let mut candidate = disk.clone();
        candidate.limits.insert(user.into(), limits);
        candidate.limit_revisions.insert(user.into(), revision);
        self.persist(&candidate).map_err(|error| LimitsError::Persistence(error.to_string()))?;
        *disk = candidate;
        Ok(revision)
    }
}

#[derive(Default)]
pub struct SseUsage {
    pending: Vec<u8>,
    data: Vec<u8>,
    oversized: bool,
    failed: bool,
    complete: bool,
}
impl SseUsage {
    pub fn feed(&mut self, bytes: &[u8]) -> Vec<Tokens> {
        let mut found = Vec::new();
        for &byte in bytes {
            if byte == b'\n' {
                if self.pending.last() == Some(&b'\r') {
                    self.pending.pop();
                }
                if self.pending.is_empty() {
                    if !self.oversized {
                        if let Ok(value) = serde_json::from_slice::<Value>(&self.data) {
                            if value["type"] == "response.failed"
                                || value["type"] == "error"
                                || !value["error"].is_null()
                            {
                                self.failed = true;
                            }
                            if value["type"] == "response.completed" {
                                self.complete = true;
                            }
                            if let Some(t) = Tokens::parse(&value) {
                                found.push(t);
                            }
                        }
                    }
                    self.data.clear();
                    self.oversized = false;
                } else if let Some(data) = self.pending.strip_prefix(b"data:") {
                    if self.data.len() + data.len() < 512 * 1024 {
                        self.data
                            .extend_from_slice(data.strip_prefix(b" ").unwrap_or(data));
                        self.data.push(b'\n');
                    } else {
                        self.oversized = true;
                    }
                }
                self.pending.clear();
            } else if self.pending.len() < 512 * 1024 {
                self.pending.push(byte);
            } else {
                self.oversized = true;
            }
        }
        found
    }
}
pub struct Meter {
    store: Arc<UsageStore>,
    id: String,
    tokens: Option<Tokens>,
    status: Option<u16>,
    settled: bool,
    sse: SseUsage,
}
impl Meter {
    pub fn status(&mut self, status: u16) {
        self.status = Some(status);
    }
    pub fn json(&mut self, value: &Value) {
        if let Some(t) = Tokens::parse(value) {
            self.tokens = Some(t);
        }
    }
    pub fn chunk(&mut self, bytes: &[u8]) {
        for t in self.sse.feed(bytes) {
            self.tokens = Some(t);
        }
    }
    pub fn finish(&mut self, outcome: &str, uncertain: bool) {
        if !self.settled {
            let outcome = if self.sse.failed {
                "error"
            } else if self.sse.complete {
                "completed"
            } else {
                outcome
            };
            if let Err(e) = self.store.finish(
                &self.id,
                self.tokens.clone(),
                outcome,
                self.status,
                uncertain,
            ) {
                eprintln!("model usage persistence failed: {e}");
            }
            self.settled = true;
        }
    }
}
impl Drop for Meter {
    fn drop(&mut self) {
        self.finish("interrupted", true);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn identity(user: &str) -> Identity {
        Identity {
            user_id: user.into(),
            label: user.into(),
            beta: true,
        }
    }
    fn store() -> (PathBuf, Arc<UsageStore>) {
        let p =
            std::env::temp_dir().join(format!("vibelink_usage_{}", crate::auth::random_token()));
        (p.clone(), Arc::new(UsageStore::load(p).unwrap()))
    }
    #[test]
    fn beta_transport_budget_allows_cli_context_and_preserves_admin_limits() {
        let (p,s)=store();
        let limits=Limits::for_user(true);
        assert_eq!(limits.daily_tokens,1_500_000);
        assert_eq!(limits.daily_input_bytes,12_000_000);
        s.import_legacy(vec![json!({"tenantId":"seed","dailyLimitChars":1_500_000})]).unwrap();
        assert_eq!(s.summary(&identity("seed"),&day_at(now_ms()))["limits"]["dailyInputBytes"],12_000_000);
        s.set_limits("seed",Limits{daily_input_bytes:500_000,..limits}).unwrap();
        s.import_legacy(vec![json!({"tenantId":"seed","dailyLimitChars":1_500_000})]).unwrap();
        assert_eq!(s.summary(&identity("seed"),&day_at(now_ms()))["limits"]["dailyInputBytes"],500_000);
        std::fs::remove_dir_all(p).unwrap();
    }
    #[test]
    fn actual_usage_is_per_user_persistent_and_cached_tokens_are_not_double_counted() {
        let (p, s) = store();
        let a = identity("a");
        let b = identity("b");
        let mut m = s.begin(a.clone(), "responses", 30, 10).unwrap();
        m.json(&json!({"usage":{"input_tokens":10,"output_tokens":3,"total_tokens":13,"input_tokens_details":{"cached_tokens":7}}}));
        m.finish("completed", true);
        assert_eq!(s.summary(&a, &day_at(now_ms()))["totalTokens"], 13);
        assert_eq!(s.summary(&b, &day_at(now_ms()))["totalTokens"], 0);
        assert_eq!(
            UsageStore::load(p.clone())
                .unwrap()
                .summary(&a, &day_at(now_ms()))["cachedTokens"],
            7
        );
        fs::remove_dir_all(p).unwrap();
    }
    #[test]
    fn concurrent_reservations_and_cancellation_cannot_bypass_limits() {
        let (p, s) = store();
        let a = identity("a");
        s.set_limits(
            "a",
            Limits {
                daily_requests: 2,
                daily_tokens: 100,
                daily_input_bytes: 100,
                concurrent_requests: 1,
                requests_per_minute: 30,
            },
        )
        .unwrap();
        let m = s.begin(a.clone(), "responses", 10, 20).unwrap();
        assert!(s.begin(a.clone(), "responses", 10, 20).is_err());
        drop(m);
        let v = s.summary(&a, &day_at(now_ms()));
        assert_eq!(v["estimatedTokens"], 30);
        assert_eq!(v["reservedTokens"], 0);
        let mut m = s.begin(a.clone(), "responses", 10, 20).unwrap();
        m.finish("error", false);
        assert!(s.begin(a, "responses", 1, 1).is_err());
        fs::remove_dir_all(p).unwrap();
    }
    #[test]
    fn streaming_usage_handles_arbitrary_byte_boundaries_for_both_protocols() {
        let bytes=b"data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":12,\"output_tokens\":4}}}\r\n\r\ndata: {\"usage\":{\"prompt_tokens\":15,\"completion_tokens\":5}}\n\ndata: [DONE]\n\n";
        let mut parser = SseUsage::default();
        let mut found = Vec::new();
        for b in bytes.chunks(3) {
            found.extend(parser.feed(b));
        }
        assert_eq!(found.len(), 2);
        assert_eq!(found[0].total, 16);
        assert_eq!(found[1].total, 20);
    }
    #[test]
    fn utc8_rollover_and_restart_settle_unfinished_requests_conservatively() {
        assert_ne!(day_at(16 * 3_600_000 - 1), day_at(16 * 3_600_000));
        let (p, s) = store();
        let a = identity("a");
        let m = s.begin(a.clone(), "responses", 10, 20).unwrap();
        std::mem::forget(m);
        drop(s);
        let recovered = UsageStore::load(p.clone()).unwrap();
        let stats = recovered.summary(&a, &day_at(now_ms()));
        assert_eq!(stats["reservedTokens"], 0);
        assert_eq!(stats["estimatedTokens"], 30);
        fs::remove_dir_all(p).unwrap();
    }

    #[test]
    fn limits_persistence_failure_does_not_change_the_effective_budget() {
        let (dir, store) = store();
        store.set_limits("one", Limits::for_user(true)).unwrap();
        let before = store.summary(&identity("one"), "2030-01-01")["limits"].clone();
        let persisted = fs::read(dir.join("model_usage.json")).unwrap();
        fs::create_dir(dir.join("model_usage.json.tmp")).unwrap();
        assert!(store.set_limits("one", Limits { daily_tokens: 7, ..Limits::for_user(true) }).is_err());
        assert_eq!(store.summary(&identity("one"), "2030-01-01")["limits"], before);
        assert_eq!(fs::read(dir.join("model_usage.json")).unwrap(), persisted);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn failed_first_limits_write_preserves_defaults_and_revision() {
        let (dir, store) = store();
        fs::create_dir(dir.join("model_usage.json.tmp")).unwrap();
        assert!(matches!(store.set_limits_if_revision("new", Limits::for_user(false), Some(0)), Err(LimitsError::Persistence(_))));
        let view = store.summary(&identity("new"), "2030-01-01");
        assert_eq!(view["limitsRevision"], 0);
        assert_eq!(view["dailyLimitTokens"], 1_500_000);
        assert!(!store.inner.lock().unwrap().limits.contains_key("new"));
        fs::remove_dir(dir.join("model_usage.json.tmp")).unwrap();
        assert_eq!(store.set_limits_if_revision("new", Limits::for_user(true), Some(0)).unwrap(), 1);
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn concurrent_limits_editors_have_one_winner_and_one_stale_revision() {
        let (dir, store) = store();
        let gate = Arc::new(std::sync::Barrier::new(2));
        let threads: Vec<_> = [111, 222].into_iter().map(|budget| {
            let gate = gate.clone(); let store = store.clone();
            std::thread::spawn(move || { gate.wait(); store.set_limits_if_revision("one", Limits { daily_tokens: budget, ..Limits::for_user(true) }, Some(0)) })
        }).collect();
        let results: Vec<_> = threads.into_iter().map(|thread| thread.join().unwrap()).collect();
        assert_eq!(results.iter().filter(|r| matches!(r, Ok(1))).count(), 1);
        assert_eq!(results.iter().filter(|r| matches!(r, Err(LimitsError::Conflict { current: 1 }))).count(), 1);
        let view = store.summary(&identity("one"), "2030-01-01");
        assert_eq!(view["limitsRevision"], 1);
        assert!([111,222].contains(&view["dailyLimitTokens"].as_u64().unwrap()));
        let reloaded = UsageStore::load(dir.clone()).unwrap();
        assert_eq!(reloaded.summary(&identity("one"), "2030-01-01"), view);
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn same_value_updates_increment_and_failed_persistence_keeps_the_exact_revision() {
        let (dir, store) = store();
        assert_eq!(store.set_limits_if_revision("one", Limits::for_user(true), Some(0)).unwrap(), 1);
        assert_eq!(store.set_limits_if_revision("one", Limits::for_user(true), Some(1)).unwrap(), 2);
        let before = store.summary(&identity("one"), "2030-01-01");
        let persisted = fs::read(dir.join("model_usage.json")).unwrap();
        fs::create_dir(dir.join("model_usage.json.tmp")).unwrap();
        assert!(matches!(store.set_limits_if_revision("one", Limits::for_user(false), Some(2)), Err(LimitsError::Persistence(_))));
        assert_eq!(store.summary(&identity("one"), "2030-01-01"), before);
        assert_eq!(fs::read(dir.join("model_usage.json")).unwrap(), persisted);
        assert!(matches!(store.set_limits_if_revision("one", Limits::for_user(false), Some(1)), Err(LimitsError::Conflict { current: 2 })));
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn legacy_limits_files_start_at_revision_zero_and_upgrade_durably() {
        let (dir, store) = store();
        store.set_limits("old", Limits { daily_tokens: 123, ..Limits::for_user(true) }).unwrap();
        let mut legacy: Value = serde_json::from_slice(&fs::read(dir.join("model_usage.json")).unwrap()).unwrap();
        legacy.as_object_mut().unwrap().remove("limit_revisions");
        fs::write(dir.join("model_usage.json"), serde_json::to_vec(&legacy).unwrap()).unwrap();
        let old = UsageStore::load(dir.clone()).unwrap();
        let view = old.summary(&identity("old"), "2030-01-01");
        assert_eq!(view["limitsRevision"], 0); assert_eq!(view["dailyLimitTokens"], 123);
        assert_eq!(old.set_limits_if_revision("old", Limits::for_user(true), Some(0)).unwrap(), 1);
        assert_eq!(UsageStore::load(dir.clone()).unwrap().summary(&identity("old"), "2030-01-01")["limitsRevision"], 1);
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn exhausted_limits_revision_fails_closed_without_writes() {
        let (dir, store) = store();
        { let mut disk = store.inner.lock().unwrap(); disk.limit_revisions.insert("old".into(), u64::MAX); store.persist(&disk).unwrap(); }
        let before = fs::read(dir.join("model_usage.json")).unwrap();
        assert!(matches!(store.set_limits_if_revision("old", Limits::for_user(false), Some(u64::MAX)), Err(LimitsError::RevisionExhausted)));
        assert_eq!(fs::read(dir.join("model_usage.json")).unwrap(), before);
        assert!(!store.inner.lock().unwrap().limits.contains_key("old"));
        fs::remove_dir_all(dir).unwrap();
    }
}

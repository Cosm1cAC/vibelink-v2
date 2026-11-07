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
            daily_input_bytes: if beta { 1_500_000 } else { 0 },
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
}
#[derive(Default, Serialize, Deserialize)]
struct Disk {
    daily: BTreeMap<String, Daily>,
    pending: BTreeMap<String, Pending>,
    limits: BTreeMap<String, Limits>,
    starts: BTreeMap<String, Vec<u64>>,
    recent: Vec<Value>,
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
        };
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
        disk.recent.push(json!({"requestId":id,"userId":pending.identity.user_id,"day":pending.day,"model":crate::model_policy::DEEPSEEK_MODEL,
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
            "dailyLimitChars":limits.daily_input_bytes,"limits":limits,"warning":if ratio>=1.0 {"exhausted"} else if ratio>=0.8 {"near_limit"} else {""}})
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
                daily_input_bytes: row["dailyLimitChars"].as_u64().unwrap_or(1_500_000),
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
        if user.is_empty()
            || limits.concurrent_requests == 0
            || limits.concurrent_requests > 16
            || limits.requests_per_minute == 0
        {
            return Err("Invalid limits".into());
        }
        let mut disk = self.inner.lock().unwrap();
        disk.limits.insert(user.into(), limits);
        self.persist(&disk).map_err(|e| e.to_string())
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
}

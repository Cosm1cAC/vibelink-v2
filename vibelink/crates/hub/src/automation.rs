//! Observation-only audit. This module has no model, Work submission or tool dispatcher.
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{collections::BTreeMap, fs, io::Write, path::PathBuf, sync::Mutex};
use vibelink_protocol::now_ms;
const MAX_SCOPES: usize = 128;
const MAX_RULES: usize = 256;
const MAX_EVENTS: usize = 10_000;
const MAX_DECISIONS: usize = 50_000;
const RETURNED_DECISIONS: usize = 200;
const MAX_BYTES: usize = 16 * 1024 * 1024;
// Keep enough disk budget for all bounded revocations and off controls even at audit capacity.
const CONTROL_RESERVE: usize = 4 * 1024 * 1024;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Scope {
    pub issuer: String,
    pub edition: String,
    pub user: String,
    pub group: String,
}
impl Scope {
    fn key(&self) -> String {
        crate::auth::hash_token(&serde_json::to_string(self).unwrap())
    }
}
#[derive(Clone, Debug)]
pub struct Observation {
    pub id: String,
    pub user: String,
    pub group: String,
    pub revision: u64,
    pub status: String,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Cursor {
    revision: u64,
    status: String,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Rule {
    id: String,
    label: String,
    statuses: Vec<String>,
    version: u32,
    created_at: u64,
    baseline: BTreeMap<String, Cursor>,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Event {
    id: String,
    work_id: String,
    work_revision: u64,
    status: String,
    at: u64,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Decision {
    id: String,
    event_id: String,
    work_id: String,
    work_revision: u64,
    status: String,
    rule_id: String,
    rule_version: u32,
    at: u64,
    outcome: String,
    reason: String,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Entry {
    scope: Scope,
    mode: String,
    revision: u64,
    baseline_at: u64,
    last_scan_at: u64,
    cursor: BTreeMap<String, Cursor>,
    rules: Vec<Rule>,
    revocations: BTreeMap<String, u64>,
    events: BTreeMap<String, Event>,
    decisions: BTreeMap<String, Decision>,
    blocked: Option<String>,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Disk {
    format: u32,
    scopes: BTreeMap<String, Entry>,
}
impl Default for Disk {
    fn default() -> Self {
        Self {
            format: 1,
            scopes: BTreeMap::new(),
        }
    }
}
#[derive(Debug)]
pub enum Error {
    Invalid(String),
    Conflict(u64),
    Capacity,
    Persistence(String),
}
impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Invalid(s) | Self::Persistence(s) => write!(f, "{s}"),
            Self::Conflict(n) => write!(f, "Automation settings changed; current revision {n}"),
            Self::Capacity => write!(
                f,
                "Automation audit capacity reached; pending observations retained"
            ),
        }
    }
}
#[derive(Debug, Deserialize)]
#[serde(tag = "action", deny_unknown_fields)]
pub enum Command {
    #[serde(rename = "setMode")]
    SetMode {
        mode: String,
        #[serde(rename = "expectedRevision")]
        expected: u64,
    },
    #[serde(rename = "saveRule")]
    SaveRule {
        #[serde(rename = "ruleId")]
        id: String,
        label: String,
        statuses: Vec<String>,
        #[serde(rename = "expectedRevision")]
        expected: u64,
    },
    #[serde(rename = "revokeRule")]
    RevokeRule {
        #[serde(rename = "ruleId")]
        id: String,
        #[serde(rename = "expectedRevision")]
        expected: u64,
    },
}
fn allowed(status: &str) -> bool {
    matches!(status, "completed" | "failed" | "needs_input")
}
fn version_key(id: &str, version: u32) -> String {
    format!("{id}/{version}")
}
fn baseline(scope: &Scope, observations: &[Observation]) -> BTreeMap<String, Cursor> {
    observations
        .iter()
        .filter(|w| w.user == scope.user && w.group == scope.group)
        .map(|w| {
            (
                w.id.clone(),
                Cursor {
                    revision: w.revision,
                    status: w.status.clone(),
                },
            )
        })
        .collect()
}
fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 80
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}
impl Entry {
    fn new(scope: Scope, observations: &[Observation]) -> Self {
        Self {
            cursor: baseline(&scope, observations),
            scope,
            mode: "observe".into(),
            revision: 0,
            baseline_at: now_ms(),
            last_scan_at: now_ms(),
            rules: vec![],
            revocations: BTreeMap::new(),
            events: BTreeMap::new(),
            decisions: BTreeMap::new(),
            blocked: None,
        }
    }
    fn validate(&self, key: &str) -> Result<(), ()> {
        if key != self.scope.key()
            || self.scope.issuer.is_empty()
            || !matches!(self.scope.edition.as_str(), "dev" | "beta")
            || self.scope.user.is_empty()
            || self.scope.group.is_empty()
            || !matches!(self.mode.as_str(), "off" | "observe")
            || self.baseline_at == 0
            || self.last_scan_at == 0
            || self.rules.len() > MAX_RULES
            || self.events.len() > MAX_EVENTS
            || self.decisions.len() > MAX_DECISIONS
            || self
                .blocked
                .as_deref()
                .is_some_and(|b| b != "capacity_exhausted")
        {
            return Err(());
        }
        let mut versions = BTreeMap::new();
        for r in &self.rules {
            if !valid_id(&r.id)
                || r.label.trim().is_empty()
                || r.label.chars().count() > 120
                || r.version == 0
                || r.created_at == 0
                || r.statuses.is_empty()
                || r.statuses.len() > 3
                || r.statuses.iter().any(|s| !allowed(s))
                || r.statuses.windows(2).any(|w| w[0] >= w[1])
            {
                return Err(());
            }
            let previous = versions.insert(r.id.clone(), r.version).unwrap_or(0_u32);
            if previous.checked_add(1) != Some(r.version) {
                return Err(());
            }
        }
        for (key, at) in &self.revocations {
            if *at == 0
                || !self
                    .rules
                    .iter()
                    .any(|r| version_key(&r.id, r.version) == *key)
            {
                return Err(());
            }
        }
        for r in &self.rules {
            if versions.get(&r.id) != Some(&r.version)
                && !self
                    .revocations
                    .contains_key(&version_key(&r.id, r.version))
            {
                return Err(());
            }
        }
        for (key, event) in &self.events {
            let expected = crate::auth::hash_token(&format!(
                "{}|{}|{}|{}",
                self.scope.key(),
                event.work_id,
                event.work_revision,
                event.status
            ));
            if *key != expected
                || event.id != *key
                || !allowed(&event.status)
                || event.at == 0
                || !self
                    .cursor
                    .get(&event.work_id)
                    .is_some_and(|c| c.revision >= event.work_revision)
            {
                return Err(());
            }
        }
        for (key, d) in &self.decisions {
            let event = self.events.get(&d.event_id).ok_or(())?;
            let rule = self
                .rules
                .iter()
                .find(|r| r.id == d.rule_id && r.version == d.rule_version)
                .ok_or(())?;
            let expected = crate::auth::hash_token(&format!(
                "{}|{}|{}",
                d.event_id, d.rule_id, d.rule_version
            ));
            if *key != expected
                || d.id != *key
                || d.work_id != event.work_id
                || d.work_revision != event.work_revision
                || d.status != event.status
                || d.at == 0
                || !rule.statuses.contains(&d.status)
                || rule
                    .baseline
                    .get(&d.work_id)
                    .is_some_and(|b| b.revision >= d.work_revision)
                || !matches!(
                    (d.outcome.as_str(), d.reason.as_str()),
                    ("suggested", "matched") | ("suppressed", "off" | "revoked")
                )
            {
                return Err(());
            }
        }
        Ok(())
    }
    fn process(&mut self, observations: &[Observation]) -> Result<(), Error> {
        for w in observations
            .iter()
            .filter(|w| w.user == self.scope.user && w.group == self.scope.group)
        {
            if let Some(old) = self.cursor.get(&w.id) {
                if w.revision < old.revision
                    || (w.revision == old.revision && w.status != old.status)
                {
                    return Err(Error::Invalid(
                        "Work observation revision regressed; audit paused".into(),
                    ));
                }
                if w.revision == old.revision {
                    continue;
                }
            }
            if allowed(&w.status) {
                let event_id = crate::auth::hash_token(&format!(
                    "{}|{}|{}|{}",
                    self.scope.key(),
                    w.id,
                    w.revision,
                    w.status
                ));
                if !self.events.contains_key(&event_id) && self.events.len() >= MAX_EVENTS {
                    return Err(Error::Capacity);
                }
                let at = now_ms();
                self.events.entry(event_id.clone()).or_insert(Event {
                    id: event_id.clone(),
                    work_id: w.id.clone(),
                    work_revision: w.revision,
                    status: w.status.clone(),
                    at,
                });
                for rule in &self.rules {
                    if !rule.statuses.contains(&w.status)
                        || rule
                            .baseline
                            .get(&w.id)
                            .is_some_and(|base| w.revision <= base.revision)
                    {
                        continue;
                    }
                    let id = crate::auth::hash_token(&format!(
                        "{event_id}|{}|{}",
                        rule.id, rule.version
                    ));
                    if self.decisions.contains_key(&id) {
                        continue;
                    }
                    if self.decisions.len() >= MAX_DECISIONS {
                        return Err(Error::Capacity);
                    }
                    let reason = if self
                        .revocations
                        .contains_key(&version_key(&rule.id, rule.version))
                    {
                        "revoked"
                    } else if self.mode == "off" {
                        "off"
                    } else {
                        "matched"
                    };
                    self.decisions.insert(
                        id.clone(),
                        Decision {
                            id,
                            event_id: event_id.clone(),
                            work_id: w.id.clone(),
                            work_revision: w.revision,
                            status: w.status.clone(),
                            rule_id: rule.id.clone(),
                            rule_version: rule.version,
                            at,
                            outcome: if reason == "matched" {
                                "suggested"
                            } else {
                                "suppressed"
                            }
                            .into(),
                            reason: reason.into(),
                        },
                    );
                }
            }
            self.cursor.insert(
                w.id.clone(),
                Cursor {
                    revision: w.revision,
                    status: w.status.clone(),
                },
            );
        }
        Ok(())
    }
    fn refresh(&mut self, observations: &[Observation]) -> Result<(), Error> {
        // A full audit is explicitly paused. Later nonterminal snapshots must not erase
        // the warning or advance beyond an already observed but uncommitted event.
        if self.blocked.is_some() {
            return Ok(());
        }
        let mut next = self.clone();
        match next.process(observations) {
            Ok(()) => {
                next.blocked = None;
                if next != *self {
                    next.last_scan_at = now_ms();
                }
                *self = next;
                Ok(())
            }
            Err(Error::Capacity) => {
                self.blocked = Some("capacity_exhausted".into());
                Ok(())
            }
            Err(e) => Err(e),
        }
    }
    fn snapshot(&self) -> Value {
        let mut decisions: Vec<_> = self.decisions.values().cloned().collect();
        decisions.sort_by(|a, b| a.at.cmp(&b.at).then(a.id.cmp(&b.id)));
        if decisions.len() > RETURNED_DECISIONS {
            decisions.drain(..decisions.len() - RETURNED_DECISIONS);
        }
        json!({"ok":true,"groupId":self.scope.group,"policy":{"mode":self.mode,"revision":self.revision},
            "rules":self.rules.iter().map(|r|json!({"id":r.id,"label":r.label,"statuses":r.statuses,"version":r.version,"createdAt":r.created_at,"revokedAt":self.revocations.get(&version_key(&r.id,r.version)).copied().unwrap_or(0)})).collect::<Vec<_>>(),
            "decisions":decisions,"observer":{"coverage":"latest_work_snapshot","baselineAt":self.baseline_at,"lastScanAt":self.last_scan_at,"error":if self.blocked.is_some(){"capacity"}else{""},"decisionLimit":MAX_DECISIONS,"ruleLimit":MAX_RULES,"eventLimit":MAX_EVENTS,"returnedDecisionLimit":RETURNED_DECISIONS},
            "capacity":{"events":self.events.len(),"decisions":self.decisions.len(),"maxEvents":MAX_EVENTS,"maxDecisions":MAX_DECISIONS,"maxBytes":MAX_BYTES}})
    }
}
pub struct Store {
    file: PathBuf,
    inner: Mutex<Disk>,
}
impl Store {
    pub fn load(dir: PathBuf) -> Result<Self, String> {
        let file = dir.join("automation.json");
        let disk: Disk = match fs::read(&file) {
            Ok(bytes) if bytes.len() > MAX_BYTES => {
                return Err("Automation ledger exceeds supported capacity".into());
            }
            Ok(bytes) => serde_json::from_slice(&bytes)
                .map_err(|e| format!("Invalid automation ledger: {e}"))?,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Disk::default(),
            Err(e) => return Err(e.to_string()),
        };
        if disk.format != 1 || disk.scopes.len() > MAX_SCOPES {
            return Err("Unsupported automation ledger".into());
        }
        for (key, e) in &disk.scopes {
            e.validate(key)
                .map_err(|_| "Invalid automation ledger invariants".to_string())?;
        }
        Ok(Self {
            file,
            inner: Mutex::new(disk),
        })
    }
    fn transaction<T>(
        &self,
        control: bool,
        operation: impl FnOnce(&mut Disk) -> Result<T, Error>,
    ) -> Result<T, Error> {
        let mut disk = self.inner.lock().unwrap();
        let mut next = disk.clone();
        let result = operation(&mut next)?;
        if next == *disk {
            return Ok(result);
        }
        let bytes = serde_json::to_vec(&next).map_err(|e| Error::Persistence(e.to_string()))?;
        if bytes.len()
            > if control {
                MAX_BYTES
            } else {
                MAX_BYTES - CONTROL_RESERVE
            }
        {
            if !control {
                // Persist only a pause marker, never the oversized candidate. Cursor,
                // events, policy and decisions remain exactly at their committed values.
                let mut paused = disk.clone();
                for (key, entry) in &mut paused.scopes {
                    if next.scopes.get(key).is_some_and(|n| {
                        n.cursor != entry.cursor
                            || n.events != entry.events
                            || n.decisions != entry.decisions
                    }) {
                        entry.blocked = Some("capacity_exhausted".into());
                    }
                }
                if paused != *disk {
                    self.persist(&paused)?;
                    *disk = paused;
                }
            }
            return Err(Error::Capacity);
        }
        self.persist_bytes(&bytes)?;
        *disk = next;
        Ok(result)
    }
    fn persist(&self, disk: &Disk) -> Result<(), Error> {
        let bytes = serde_json::to_vec(disk).map_err(|e| Error::Persistence(e.to_string()))?;
        if bytes.len() > MAX_BYTES {
            return Err(Error::Capacity);
        }
        self.persist_bytes(&bytes)
    }
    fn persist_bytes(&self, bytes: &[u8]) -> Result<(), Error> {
        fs::create_dir_all(self.file.parent().unwrap())
            .map_err(|e| Error::Persistence(e.to_string()))?;
        let tmp = self.file.with_extension("tmp");
        let mut f = fs::File::create(&tmp).map_err(|e| Error::Persistence(e.to_string()))?;
        f.write_all(bytes)
            .and_then(|_| f.sync_all())
            .map_err(|e| Error::Persistence(e.to_string()))?;
        drop(f);
        fs::rename(tmp, &self.file).map_err(|e| Error::Persistence(e.to_string()))?;
        Ok(())
    }
    pub fn snapshot(&self, scope: Scope, observations: &[Observation]) -> Result<Value, Error> {
        self.transaction(false, |disk| {
            let key = scope.key();
            if !disk.scopes.contains_key(&key) && disk.scopes.len() >= MAX_SCOPES {
                return Err(Error::Capacity);
            }
            let e = disk
                .scopes
                .entry(key)
                .or_insert_with(|| Entry::new(scope, observations));
            e.refresh(observations)?;
            Ok(e.snapshot())
        })
    }
    pub fn command(
        &self,
        scope: Scope,
        command: Command,
        observations: &[Observation],
    ) -> Result<Value, Error> {
        let control = matches!(&command,Command::SetMode{mode,..} if mode=="off")
            || matches!(&command, Command::RevokeRule { .. });
        self.transaction(control, |disk| {
            let key = scope.key();
            if !disk.scopes.contains_key(&key) && disk.scopes.len() >= MAX_SCOPES {
                return Err(Error::Capacity);
            }
            let e = disk
                .scopes
                .entry(key)
                .or_insert_with(|| Entry::new(scope, observations));
            let expected = match &command {
                Command::SetMode { expected, .. }
                | Command::SaveRule { expected, .. }
                | Command::RevokeRule { expected, .. } => *expected,
            };
            if expected != e.revision {
                return Err(Error::Conflict(e.revision));
            }
            let revision = e
                .revision
                .checked_add(1)
                .ok_or_else(|| Error::Invalid("Automation revision exhausted".into()))?;
            match command {
                Command::SetMode { mode, .. } => {
                    if !matches!(mode.as_str(), "off" | "observe") {
                        return Err(Error::Invalid(
                            "Only off and observe are supported; auto dispatch is disabled".into(),
                        ));
                    }
                    // Settle the off interval before resuming observation, never backfill suggestions.
                    if mode == "observe" {
                        e.refresh(observations)?;
                        if e.blocked.is_some() {
                            return Err(Error::Capacity);
                        }
                    }
                    e.mode = mode;
                    // Explicit off never consumes the control reserve on pending audit.
                    // The scanner later records pending snapshots under the off policy.
                }
                Command::SaveRule {
                    id,
                    label,
                    mut statuses,
                    ..
                } => {
                    statuses.sort();
                    statuses.dedup();
                    if !valid_id(&id)
                        || label.trim().is_empty()
                        || label.chars().count() > 120
                        || statuses.is_empty()
                        || statuses.iter().any(|s| !allowed(s))
                    {
                        return Err(Error::Invalid("Invalid observation rule".into()));
                    }
                    if e.rules.len() >= MAX_RULES {
                        return Err(Error::Capacity);
                    }
                    let version = e
                        .rules
                        .iter()
                        .filter(|r| r.id == id)
                        .map(|r| r.version)
                        .max()
                        .unwrap_or(0)
                        .checked_add(1)
                        .ok_or_else(|| Error::Invalid("Rule version exhausted".into()))?;
                    for r in e.rules.iter().filter(|r| r.id == id) {
                        e.revocations
                            .entry(version_key(&r.id, r.version))
                            .or_insert_with(now_ms);
                    }
                    e.rules.push(Rule {
                        id,
                        label: label.trim().into(),
                        statuses,
                        version,
                        created_at: now_ms(),
                        baseline: baseline(&e.scope, observations),
                    });
                    e.refresh(observations)?;
                }
                Command::RevokeRule { id, .. } => {
                    if !e.rules.iter().any(|r| r.id == id) {
                        return Err(Error::Invalid("Rule not found".into()));
                    }
                    for r in e.rules.iter().filter(|r| r.id == id) {
                        e.revocations
                            .entry(version_key(&r.id, r.version))
                            .or_insert_with(now_ms);
                    }
                    // Explicit revocation is durable before processing pending snapshots.
                }
            }
            e.revision = revision;
            Ok(e.snapshot())
        })
    }
    pub fn scan(&self, observations: &[Observation]) -> Result<(), Error> {
        self.transaction(false, |disk| {
            for entry in disk.scopes.values_mut() {
                entry.refresh(observations)?;
            }
            Ok(())
        })
    }
}

pub fn scope(app: &crate::Shared, ctx: &crate::auth::AuthCtx, group: &str) -> Scope {
    Scope {
        issuer: app.auth.issuer().to_string(),
        edition: if app.beta_mode { "beta" } else { "dev" }.into(),
        user: crate::model_gateway::identity(ctx).user_id,
        group: group.into(),
    }
}
pub fn spawn(root: crate::Shared) {
    tokio::spawn(async move {
        loop {
            let mut apps = vec![root.clone()];
            apps.extend(root.tenant_apps.lock().unwrap().values().cloned());
            for app in apps {
                let result = app
                    .work
                    .with_observation_snapshot(|observations| app.automation.scan(observations));
                if let Err(error) = result {
                    eprintln!("[automation-observe] audit paused: {error}");
                }
            }
            tokio::time::sleep(std::time::Duration::from_secs(1)).await;
        }
    });
}
#[cfg(test)]
#[path = "automation_tests.rs"]
mod tests;

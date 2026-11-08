//! Durable workspace leases. Share ONE store across all Hub tenant Apps.
//! Resource identities must come from an authenticated Agent's canonical workspace probe,
//! never from model output, a phone path, caller ID, or a tenant-specific store directory.
//! There is deliberately no timeout/TTL: an unreachable worker may still be writing.
use serde::{Deserialize, Serialize};
use std::{fs, io::Write, path::PathBuf, sync::Mutex};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Resource {
    pub namespace: String,
    pub device: String,
    pub path: String,
    pub case_sensitive: bool,
}
impl Resource {
    /// `namespace` is server-owned metadata: dev_shared or beta_<trusted tenant ID>.
    /// Even distinct namespaces conflict when their physical paths overlap on one Agent.
    pub fn resolved(
        namespace: &str,
        device: &str,
        path: &str,
        case_sensitive: bool,
    ) -> Result<Self, String> {
        if namespace != "dev_shared" && (!namespace.starts_with("beta_") || namespace.len() <= 5) {
            return Err("Untrusted workspace lease namespace".into());
        }
        if device.is_empty()
            || device.len() > 128
            || namespace.len() > 160
            || path.len() > 8192
            || device
                .chars()
                .chain(namespace.chars())
                .any(char::is_control)
        {
            return Err("Invalid workspace lease identity".into());
        }
        Ok(Self {
            namespace: namespace.into(),
            device: device.into(),
            path: canonical_key(path, case_sensitive)?,
            case_sensitive,
        })
    }
    fn overlaps(&self, other: &Self) -> bool {
        if self.device != other.device {
            return false;
        }
        // If probes disagree on case sensitivity, take the more conservative interpretation.
        let (a, b) = if self.case_sensitive && other.case_sensitive {
            (self.path.clone(), other.path.clone())
        } else {
            (self.path.to_lowercase(), other.path.to_lowercase())
        };
        a == b
            || a.strip_prefix(&b)
                .is_some_and(|s| b.ends_with('/') || s.starts_with('/'))
            || b.strip_prefix(&a)
                .is_some_and(|s| a.ends_with('/') || s.starts_with('/'))
    }
}
fn canonical_key(path: &str, case_sensitive: bool) -> Result<String, String> {
    if path.is_empty() || path.chars().any(char::is_control) {
        return Err("Canonical absolute workspace required".into());
    }
    let mut s = path.to_string();
    let windows = s.as_bytes().get(1) == Some(&b':') || s.starts_with("\\\\");
    if windows {
        s = s.replace('\\', "/");
    }
    if let Some(rest) = s.strip_prefix("//?/UNC/") {
        s = format!("//{rest}");
    } else if let Some(rest) = s.strip_prefix("//?/") {
        s = rest.into();
    }
    if !(s.starts_with('/')
        || s.as_bytes().get(1) == Some(&b':') && s.as_bytes().get(2) == Some(&b'/'))
    {
        return Err("Canonical absolute workspace required".into());
    }
    if s.split('/').any(|c| c == "." || c == "..") || s.contains("//") && !s.starts_with("//") {
        return Err("Workspace probe returned a non-canonical path".into());
    }
    while s.len() > 1 && s.ends_with('/') && !(s.len() == 3 && s.as_bytes()[1] == b':') {
        s.pop();
    }
    Ok(if case_sensitive { s } else { s.to_lowercase() })
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Access {
    Read,
    Write,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum State {
    Held,
    Running,
    Uncertain,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LeaseToken {
    pub work_id: String,
    pub step_id: String,
    pub generation: u64,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Lease {
    pub token: LeaseToken,
    pub resource: Resource,
    pub access: Access,
    pub state: State,
    pub session_id: String,
    pub reason: String,
}
/// These are positive observations, not timeouts, absent messages, or a merely accepted interrupt.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ReleaseEvidence {
    Completed,
    StoppedConfirmed,
    NeverDispatched,
}
#[derive(Clone, Default, Serialize, Deserialize)]
struct Disk {
    generation: u64,
    leases: Vec<Lease>,
}
pub struct WorkLeases {
    file: PathBuf,
    inner: Mutex<Disk>,
    _process_lock: fs::File,
}
impl WorkLeases {
    pub fn load(dir: PathBuf) -> Result<Self, String> {
        let file = dir.join("work_leases.json");
        fs::create_dir_all(&dir)
            .map_err(|e| format!("Cannot create workspace lease directory: {e}"))?;
        let process_lock = fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(dir.join("work_leases.lock"))
            .map_err(|e| format!("Cannot open workspace lease process lock: {e}"))?;
        process_lock.try_lock().map_err(|_|"Workspace lease store is already owned by another Hub/store; share the existing Arc")?;
        let mut disk: Disk = match fs::read(&file) {
            Ok(bytes) if bytes.len() <= 16 * 1024 * 1024 => serde_json::from_slice(&bytes)
                .map_err(|e| format!("Invalid workspace lease ledger: {e}"))?,
            Ok(_) => return Err("Workspace lease ledger too large".into()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Disk::default(),
            Err(e) => return Err(format!("Cannot read workspace lease ledger: {e}")),
        };
        for (i, lease) in disk.leases.iter().enumerate() {
            let rebuilt = Resource::resolved(
                &lease.resource.namespace,
                &lease.resource.device,
                &lease.resource.path,
                lease.resource.case_sensitive,
            )?;
            if rebuilt != lease.resource
                || lease.token.generation == 0
                || lease.token.generation > disk.generation
                || disk.leases[..i].iter().any(|l| {
                    l.token.generation == lease.token.generation
                        || l.token.work_id == lease.token.work_id
                            && l.token.step_id == lease.token.step_id
                })
            {
                return Err("Invalid workspace lease fencing ledger".into());
            }
            if disk.leases[..i].iter().any(|l| {
                l.resource.overlaps(&lease.resource)
                    && (l.access == Access::Write || lease.access == Access::Write)
            }) {
                return Err("Conflicting persisted workspace leases".into());
            }
        }
        // A process restart loses knowledge of queued/in-flight dispatch, not the lease.
        for lease in &mut disk.leases {
            if lease.state != State::Uncertain {
                lease.state = State::Uncertain;
                lease.reason = "Hub restarted; Agent quiescence is unconfirmed".into();
            }
        }
        let this = Self {
            file,
            inner: Mutex::new(disk),
            _process_lock: process_lock,
        };
        if !this.inner.lock().unwrap().leases.is_empty() {
            this.persist(&this.inner.lock().unwrap())?;
        }
        Ok(this)
    }
    fn persist(&self, disk: &Disk) -> Result<(), String> {
        let bytes = serde_json::to_vec(disk).map_err(|e| e.to_string())?;
        if bytes.len() > 16 * 1024 * 1024 {
            return Err("Workspace lease ledger too large".into());
        }
        let dir = self.file.parent().ok_or("Missing lease ledger directory")?;
        fs::create_dir_all(dir).map_err(|e| e.to_string())?;
        let tmp = self.file.with_extension("tmp");
        let mut f = fs::File::create(&tmp).map_err(|e| e.to_string())?;
        f.write_all(&bytes)
            .and_then(|_| f.sync_all())
            .map_err(|e| e.to_string())?;
        drop(f);
        fs::rename(&tmp, &self.file).map_err(|e| e.to_string())?;
        #[cfg(unix)]
        fs::File::open(dir)
            .and_then(|f| f.sync_all())
            .map_err(|e| e.to_string())?;
        Ok(())
    }
    fn change<T>(&self, f: impl FnOnce(&mut Disk) -> Result<T, String>) -> Result<T, String> {
        let mut guard = self
            .inner
            .lock()
            .map_err(|_| "Workspace lease lock poisoned")?;
        let mut next = guard.clone();
        let result = f(&mut next)?;
        self.persist(&next)?;
        *guard = next;
        Ok(result)
    }
    pub fn acquire(
        &self,
        resource: Resource,
        work_id: &str,
        step_id: &str,
        access: Access,
    ) -> Result<Lease, String> {
        if work_id.is_empty() || step_id.is_empty() || work_id.len() > 160 || step_id.len() > 160 {
            return Err("Invalid lease operation identity".into());
        }
        let checked = Resource::resolved(
            &resource.namespace,
            &resource.device,
            &resource.path,
            resource.case_sensitive,
        )?;
        if checked != resource {
            return Err("Unvalidated workspace resource".into());
        }
        self.change(|disk| {
            if let Some(lease) = disk
                .leases
                .iter()
                .find(|l| l.token.work_id == work_id && l.token.step_id == step_id)
            {
                if lease.resource != resource || lease.access != access {
                    return Err("Step already holds a lease for another workspace/access".into());
                }
                return Ok(lease.clone());
            }
            if let Some(other) = disk.leases.iter().find(|l| {
                l.resource.overlaps(&resource)
                    && (l.access == Access::Write || access == Access::Write)
            }) {
                return Err(format!(
                    "workspace_busy: {} / {} ({:?})",
                    other.token.work_id, other.token.step_id, other.state
                ));
            }
            disk.generation = disk
                .generation
                .checked_add(1)
                .ok_or("Lease fencing counter exhausted")?;
            let lease = Lease {
                token: LeaseToken {
                    work_id: work_id.into(),
                    step_id: step_id.into(),
                    generation: disk.generation,
                },
                resource,
                access,
                state: State::Held,
                session_id: String::new(),
                reason: String::new(),
            };
            disk.leases.push(lease.clone());
            Ok(lease)
        })
    }
    pub fn list(&self) -> Vec<Lease> {
        self.inner.lock().unwrap().leases.clone()
    }
    pub fn get(&self, token: &LeaseToken) -> Option<Lease> {
        self.inner
            .lock()
            .unwrap()
            .leases
            .iter()
            .find(|l| l.token == *token)
            .cloned()
    }
    pub fn attach_session(&self, token: &LeaseToken, session: &str) -> Result<Lease, String> {
        if session.is_empty() || session.len() > 256 {
            return Err("Invalid lease session ID".into());
        }
        self.change(|disk| {
            let lease = disk
                .leases
                .iter_mut()
                .find(|l| l.token == *token)
                .ok_or("Stale workspace fencing token")?;
            if !lease.session_id.is_empty() && lease.session_id != session {
                return Err("Lease already bound to another Agent session".into());
            }
            lease.session_id = session.into();
            // A known SID alone does not prove an uncertain dispatch has stopped.
            if lease.state != State::Uncertain {
                lease.state = State::Running;
            }
            Ok(lease.clone())
        })
    }
    pub fn mark_uncertain(&self, token: &LeaseToken, reason: &str) -> Result<Lease, String> {
        self.change(|disk| {
            let lease = disk
                .leases
                .iter_mut()
                .find(|l| l.token == *token)
                .ok_or("Stale workspace fencing token")?;
            lease.state = State::Uncertain;
            lease.reason = reason.chars().take(1024).collect();
            Ok(lease.clone())
        })
    }
    pub fn release(&self, token: &LeaseToken, evidence: ReleaseEvidence) -> Result<(), String> {
        self.change(|disk| {
            let i = disk
                .leases
                .iter()
                .position(|l| l.token == *token)
                .ok_or("Stale workspace fencing token")?;
            if evidence == ReleaseEvidence::NeverDispatched && disk.leases[i].state != State::Held {
                return Err(
                    "Cannot release unconfirmed workspace activity as never dispatched".into(),
                );
            }
            disk.leases.remove(i);
            Ok(())
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{
        Arc, Barrier,
        atomic::{AtomicU64, Ordering},
    };
    static SEQ: AtomicU64 = AtomicU64::new(0);
    fn dir() -> PathBuf {
        std::env::temp_dir().join(format!(
            "vibelink_lease_{}_{}",
            std::process::id(),
            SEQ.fetch_add(1, Ordering::Relaxed)
        ))
    }
    fn resource(path: &str) -> Resource {
        Resource::resolved("dev_shared", "pc", path, false).unwrap()
    }
    #[test]
    fn cross_group_same_and_nested_workspaces_conflict_but_siblings_and_devices_parallel() {
        let store = WorkLeases::load(dir()).unwrap();
        store
            .acquire(resource("D:/repo"), "group_a_work", "s1", Access::Write)
            .unwrap();
        for p in ["d:/REPO", "D:/repo/src", "D:/"] {
            assert!(
                store
                    .acquire(resource(p), "group_b_work", "s1", Access::Write)
                    .is_err()
            );
        }
        assert!(
            store
                .acquire(resource("D:/repo2"), "other", "s1", Access::Write)
                .is_ok()
        );
        assert!(
            store
                .acquire(
                    Resource::resolved("dev_shared", "mac", "/repo", false).unwrap(),
                    "mac",
                    "s1",
                    Access::Write
                )
                .is_ok()
        );
    }
    #[test]
    fn consults_share_only_with_consults_and_mixed_probe_case_is_conservative() {
        let store = WorkLeases::load(dir()).unwrap();
        store
            .acquire(resource("D:/Repo"), "a", "s1", Access::Read)
            .unwrap();
        store
            .acquire(resource("D:/Repo/src"), "b", "s1", Access::Read)
            .unwrap();
        assert!(
            store
                .acquire(resource("D:/Repo"), "c", "s1", Access::Write)
                .is_err()
        );
        assert!(
            store
                .acquire(
                    Resource::resolved("dev_shared", "pc", "D:/REPO", true).unwrap(),
                    "c",
                    "s1",
                    Access::Write
                )
                .is_err()
        );
    }
    #[test]
    fn restart_retains_activity_and_never_dispatched_cannot_clear_uncertainty() {
        let path = dir();
        let store = WorkLeases::load(path.clone()).unwrap();
        let lease = store
            .acquire(resource("D:/repo"), "a", "s1", Access::Write)
            .unwrap();
        store.attach_session(&lease.token, "official-sid").unwrap();
        drop(store);
        let restored = WorkLeases::load(path).unwrap();
        assert_eq!(restored.get(&lease.token).unwrap().state, State::Uncertain);
        assert!(
            restored
                .acquire(resource("D:/repo"), "b", "s1", Access::Read)
                .is_err()
        );
        assert!(
            restored
                .release(&lease.token, ReleaseEvidence::NeverDispatched)
                .is_err()
        );
        restored
            .release(&lease.token, ReleaseEvidence::StoppedConfirmed)
            .unwrap();
        assert!(
            restored
                .acquire(resource("D:/repo"), "b", "s1", Access::Write)
                .is_ok()
        );
    }
    #[test]
    fn stale_fence_cannot_release_new_generation_or_change_session() {
        let store = WorkLeases::load(dir()).unwrap();
        let first = store
            .acquire(resource("D:/repo"), "a", "s1", Access::Write)
            .unwrap();
        assert_eq!(
            first,
            store
                .acquire(resource("D:/repo"), "a", "s1", Access::Write)
                .unwrap()
        );
        assert!(
            store
                .acquire(resource("D:/else"), "a", "s1", Access::Write)
                .is_err()
        );
        store
            .release(&first.token, ReleaseEvidence::NeverDispatched)
            .unwrap();
        let second = store
            .acquire(resource("D:/repo"), "a", "s1", Access::Write)
            .unwrap();
        assert!(second.token.generation > first.token.generation);
        assert!(
            store
                .release(&first.token, ReleaseEvidence::Completed)
                .is_err()
        );
        assert!(store.attach_session(&first.token, "evil").is_err());
        store.attach_session(&second.token, "real").unwrap();
        assert!(store.attach_session(&second.token, "other").is_err());
    }
    #[test]
    fn uncertain_is_sticky_and_explicit_completed_evidence_is_required() {
        let store = WorkLeases::load(dir()).unwrap();
        let lease = store
            .acquire(resource("/repo"), "a", "s", Access::Write)
            .unwrap();
        store
            .mark_uncertain(&lease.token, "operation_uncertain / cancelled_unknown")
            .unwrap();
        store.attach_session(&lease.token, "sid").unwrap();
        assert_eq!(store.get(&lease.token).unwrap().state, State::Uncertain);
        assert!(
            store
                .release(&lease.token, ReleaseEvidence::NeverDispatched)
                .is_err()
        );
        store
            .release(&lease.token, ReleaseEvidence::Completed)
            .unwrap();
    }
    #[test]
    fn beta_physical_scopes_parallel_but_namespaces_never_hide_a_real_collision() {
        let store = WorkLeases::load(dir()).unwrap();
        let a =
            Resource::resolved("beta_user1", "beta_pc", "/scopes/user1/workspace", false).unwrap();
        let b =
            Resource::resolved("beta_user2", "beta_pc", "/scopes/user2/workspace", false).unwrap();
        store.acquire(a, "a", "s", Access::Write).unwrap();
        store.acquire(b, "b", "s", Access::Write).unwrap();
        assert!(
            store
                .acquire(
                    Resource::resolved("beta_user2", "beta_pc", "/scopes/user1/workspace", false)
                        .unwrap(),
                    "c",
                    "s",
                    Access::Write
                )
                .is_err()
        );
        assert!(Resource::resolved("client_untrusted", "pc", "/repo", false).is_err());
    }
    #[test]
    fn simultaneous_cross_work_acquire_has_exactly_one_winner() {
        let store = Arc::new(WorkLeases::load(dir()).unwrap());
        let gate = Arc::new(Barrier::new(12));
        let threads: Vec<_> = (0..12)
            .map(|i| {
                let store = store.clone();
                let gate = gate.clone();
                std::thread::spawn(move || {
                    gate.wait();
                    store
                        .acquire(resource("D:/repo"), &format!("w{i}"), "s", Access::Write)
                        .is_ok()
                })
            })
            .collect();
        assert_eq!(
            threads
                .into_iter()
                .map(|t| t.join().unwrap())
                .filter(|won| *won)
                .count(),
            1
        );
    }
    #[test]
    fn invalid_canonical_paths_and_corrupt_disk_fail_closed() {
        for p in [
            "repo",
            "D:repo",
            "/repo/../else",
            "/repo/./src",
            "/repo//src",
        ] {
            assert!(
                Resource::resolved("dev_shared", "pc", p, false).is_err(),
                "{p}"
            );
        }
        let path = dir();
        fs::create_dir_all(&path).unwrap();
        fs::write(path.join("work_leases.json"), b"not json").unwrap();
        assert!(WorkLeases::load(path).is_err());
    }
    #[test]
    fn failed_persistence_does_not_grant_an_in_memory_lease() {
        let path = dir();
        let store = WorkLeases::load(path.clone()).unwrap();
        fs::create_dir(path.join("work_leases.tmp")).unwrap();
        assert!(
            store
                .acquire(resource("/repo"), "a", "s", Access::Write)
                .is_err()
        );
        assert!(store.list().is_empty());
    }
    #[test]
    fn multiple_hub_stores_cannot_overwrite_the_same_physical_ledger() {
        let path = dir();
        let first = WorkLeases::load(path.clone()).unwrap();
        assert!(WorkLeases::load(path.clone()).is_err());
        drop(first);
        assert!(WorkLeases::load(path).is_ok());
    }
}

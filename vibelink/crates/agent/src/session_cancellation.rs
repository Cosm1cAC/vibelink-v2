//! In-process cancellation generations for asynchronous CLI launches/queues.
//! A request captures its generation before returning acceptance. Interrupt
//! advances it and kills registered children under the same launch lock, so a
//! queued request or a spawned-but-not-yet-visible PID cannot revive afterward.
use std::{
    collections::{HashMap, HashSet},
    sync::{Arc, Mutex, OnceLock},
};

#[derive(Default)]
struct State {
    generation: u64,
    children: HashSet<u32>,
    registered: bool,
    tickets: usize,
}
type States = Mutex<HashMap<(String, String), Arc<Mutex<State>>>>;
static STATES: OnceLock<States> = OnceLock::new();

#[derive(Clone)]
pub struct Ticket {
    scope: String,
    state: Arc<Mutex<State>>,
    generation: u64,
    _activity: Arc<Activity>,
}
struct Activity {
    state: Arc<Mutex<State>>,
}
impl Drop for Activity {
    fn drop(&mut self) {
        let mut state = self.state.lock().unwrap();
        state.tickets = state.tickets.saturating_sub(1);
    }
}

fn state(scope: &str, session: &str) -> Arc<Mutex<State>> {
    STATES
        .get_or_init(Mutex::default)
        .lock()
        .unwrap()
        .entry((scope.into(), session.into()))
        .or_default()
        .clone()
}

fn stop_with(state: &Mutex<State>, kill: &impl Fn(u32), after: impl FnOnce() -> bool) -> bool {
    let mut state = state.lock().unwrap();
    let known = state.registered;
    state.generation = state.generation.wrapping_add(1);
    // Sending a signal is not evidence of child exit. Only runner.wait followed by
    // Ticket::finished may remove a child from the lifecycle proof.
    for pid in state.children.iter().copied() {
        kill(pid);
    }
    // Session visibility is updated before a new generation may be captured.
    // Otherwise a just-started explicit prompt could be marked stopped by the
    // tail of this interruption.
    after() || known
}

fn stop(state: &Mutex<State>, kill: &impl Fn(u32)) -> bool {
    stop_with(state, kill, || false)
}

pub fn interrupt(scope: &str, session: &str, kill: impl Fn(u32)) -> bool {
    stop(&state(scope, session), &kill)
}

pub fn interrupt_with(
    scope: &str,
    session: &str,
    kill: impl Fn(u32),
    after: impl FnOnce() -> bool,
) -> bool {
    stop_with(&state(scope, session), &kill, after)
}
pub fn lifecycle(scope: &str, session: &str) -> (bool, bool) {
    let states = STATES.get_or_init(Mutex::default).lock().unwrap();
    let Some(state) = states.get(&(scope.into(), session.into())).cloned() else {
        return (false, false);
    };
    drop(states);
    let state = state.lock().unwrap();
    (
        state.registered,
        state.registered && state.children.is_empty() && state.tickets == 0,
    )
}

impl Ticket {
    pub fn capture(scope: &str, session: Option<&str>) -> Self {
        let state = session.map(|sid| state(scope, sid)).unwrap_or_default();
        let generation = {
            let mut state = state.lock().unwrap();
            state.registered = true;
            state.tickets += 1;
            state.generation
        };
        let activity = Arc::new(Activity {
            state: state.clone(),
        });
        Self {
            scope: scope.into(),
            state,
            generation,
            _activity: activity,
        }
    }

    pub fn is_current(&self) -> bool {
        self.state.lock().unwrap().generation == self.generation
    }

    pub fn while_current<T>(&self, f: impl FnOnce() -> T) -> Option<T> {
        let state = self.state.lock().unwrap();
        if state.generation != self.generation {
            return None;
        }
        Some(f())
    }

    pub fn wait_until_ready(&self, mut busy: impl FnMut() -> bool, mut wait: impl FnMut()) -> bool {
        while busy() {
            if !self.is_current() {
                return false;
            }
            wait();
        }
        self.is_current()
    }

    /// Serialize spawning and PID registration with interrupt. In particular,
    /// the child is registered before a cancellation can return to its caller.
    pub fn launch<T>(&self, spawn: impl FnOnce() -> Result<(T, u32), String>) -> Result<T, String> {
        let mut state = self.state.lock().unwrap();
        if state.generation != self.generation {
            return Err("codex_cancelled: queued launch was interrupted".into());
        }
        let (child, pid) = spawn()?;
        state.children.insert(pid);
        Ok(child)
    }

    /// Temporary IDs and the official thread ID must share one cancellation
    /// generation. A cancellation observed before a new thread is bound also
    /// wins; never overwrite its state with a fresh generation.
    pub fn bind(&self, session: &str, kill: impl Fn(u32)) -> bool {
        let other = {
            let mut states = STATES.get_or_init(Mutex::default).lock().unwrap();
            let other = states
                .entry((self.scope.clone(), session.into()))
                .or_insert_with(|| self.state.clone());
            if Arc::ptr_eq(other, &self.state) {
                None
            } else {
                Some(other.clone())
            }
        };
        if let Some(other) = other {
            stop(&self.state, &kill);
            stop(&other, &kill);
            return false;
        }
        self.is_current()
    }

    pub fn finished(&self, pid: u32) {
        self.state.lock().unwrap().children.remove(&pid);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        sync::{
            atomic::{AtomicBool, AtomicUsize, Ordering},
            mpsc,
        },
        thread,
        time::Duration,
    };
    #[test]
    fn lifecycle_waits_for_pending_runner_and_all_ticket_clones() {
        assert_eq!(lifecycle("lifecycle_pending", "sid"), (false, false));
        let ticket = Ticket::capture("lifecycle_pending", Some("sid"));
        let clone = ticket.clone();
        assert_eq!(lifecycle("lifecycle_pending", "sid"), (true, false));
        interrupt("lifecycle_pending", "sid", |_| panic!("not launched"));
        drop(ticket);
        assert_eq!(lifecycle("lifecycle_pending", "sid"), (true, false));
        drop(clone);
        assert_eq!(lifecycle("lifecycle_pending", "sid"), (true, true));
    }
    #[test]
    fn cancellation_signal_or_dropped_ticket_cannot_fake_child_reaping() {
        let ticket = Ticket::capture("lifecycle_child", Some("sid"));
        ticket.launch(|| Ok(((), 707))).unwrap();
        interrupt("lifecycle_child", "sid", |_| {}); // A failed/no-op kill must not count as stopped.
        assert_eq!(lifecycle("lifecycle_child", "sid"), (true, false));
        ticket.finished(707);
        assert_eq!(lifecycle("lifecycle_child", "sid"), (true, false));
        drop(ticket);
        assert_eq!(lifecycle("lifecycle_child", "sid"), (true, true));
        let outstanding = Ticket::capture("lifecycle_orphan", Some("sid"));
        outstanding.launch(|| Ok(((), 808))).unwrap();
        drop(outstanding);
        assert_eq!(lifecycle("lifecycle_orphan", "sid"), (true, false));
        assert_eq!(lifecycle("other_scope", "sid"), (false, false));
    }
    #[test]
    fn production_lifecycle_rpc_is_readonly_and_requires_reaped_activity_for_quiescence() {
        let p = serde_json::json!({"session":"lifecycle-rpc-session","codexRuntime":{"scope":"lifecycle_rpc_scope"}});
        let data = std::env::temp_dir().join(format!(
            "vibelink_lifecycle_no_store_{}",
            std::process::id()
        ));
        assert!(!data.exists());
        assert_eq!(
            crate::handle_session_request("codex.runtime.lifecycle", &p, &data).unwrap(),
            serde_json::json!({"known":false,"quiescent":false})
        );
        let ticket = Ticket::capture("lifecycle_rpc_scope", Some("lifecycle-rpc-session"));
        assert_eq!(
            crate::handle_session_request("codex.runtime.lifecycle", &p, &data).unwrap()["quiescent"],
            false
        );
        drop(ticket);
        assert_eq!(
            crate::handle_session_request("codex.runtime.lifecycle", &p, &data).unwrap()["quiescent"],
            true
        );
        assert!(!data.exists());
    }

    #[test]
    fn cancelled_waiter_does_not_revive_when_interrupt_clears_running() {
        let ticket = Ticket::capture("queued_scope", Some("session"));
        let busy = Arc::new(AtomicBool::new(true));
        let busy2 = busy.clone();
        let (ready, waiting) = mpsc::channel();
        let worker = thread::spawn(move || {
            ticket.wait_until_ready(
                || busy2.load(Ordering::SeqCst),
                || {
                    let _ = ready.send(());
                    thread::sleep(Duration::from_millis(1));
                },
            ) && ticket
                .launch::<()>(|| panic!("cancelled queued prompt must not spawn"))
                .is_ok()
        });
        waiting.recv_timeout(Duration::from_secs(2)).unwrap();
        interrupt("queued_scope", "session", |_| {
            panic!("no child was started")
        });
        busy.store(false, Ordering::SeqCst);
        assert!(!worker.join().unwrap());
    }

    #[test]
    fn cancel_before_launch_and_fresh_user_prompt_have_distinct_generations() {
        let old = Ticket::capture("before_spawn", Some("session"));
        interrupt("before_spawn", "session", |_| {});
        assert!(
            old.launch::<()>(|| panic!("interrupted generation cannot start a CLI"))
                .is_err()
        );
        let fresh = Ticket::capture("before_spawn", Some("session"));
        assert_eq!(
            fresh.launch(|| Ok(("new explicit prompt", 101))).unwrap(),
            "new explicit prompt"
        );
        fresh.finished(101);
    }

    #[test]
    fn interrupt_finds_pid_before_live_session_registration_and_prevents_stdin() {
        let ticket = Ticket::capture("after_spawn", Some("session"));
        ticket.launch(|| Ok(((), 202))).unwrap();
        let killed = Mutex::new(Vec::new());
        interrupt("after_spawn", "session", |pid| {
            killed.lock().unwrap().push(pid)
        });
        assert_eq!(*killed.lock().unwrap(), vec![202]);
        assert!(
            ticket
                .while_current(|| panic!("cancelled spawned CLI must not receive a prompt"))
                .is_none()
        );
    }

    #[test]
    fn spawn_and_interrupt_are_serialized_even_before_spawn_returns() {
        let ticket = Ticket::capture("spawn_race", Some("session"));
        let (started_tx, started_rx) = mpsc::channel();
        let (continue_tx, continue_rx) = mpsc::channel();
        let spawn = thread::spawn(move || {
            ticket.launch(|| {
                started_tx.send(()).unwrap();
                continue_rx.recv().unwrap();
                Ok(((), 303))
            })
        });
        started_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        let killed = Arc::new(AtomicUsize::new(0));
        let killed2 = killed.clone();
        let cancel = thread::spawn(move || {
            interrupt("spawn_race", "session", |pid| {
                killed2.store(pid as usize, Ordering::SeqCst);
            })
        });
        continue_tx.send(()).unwrap();
        spawn.join().unwrap().unwrap();
        cancel.join().unwrap();
        assert_eq!(killed.load(Ordering::SeqCst), 303);
    }

    #[test]
    fn official_alias_and_temporary_id_share_a_scope_local_fence() {
        let ticket = Ticket::capture("alias_scope", Some("temporary"));
        ticket.launch(|| Ok(((), 404))).unwrap();
        assert!(ticket.bind("official", |_| {}));
        let other_scope = Ticket::capture("other_scope", Some("official"));
        let killed = Mutex::new(Vec::new());
        interrupt("alias_scope", "official", |pid| {
            killed.lock().unwrap().push(pid)
        });
        assert_eq!(*killed.lock().unwrap(), vec![404]);
        assert!(!ticket.is_current());
        assert!(other_scope.is_current());
    }

    #[test]
    fn cancellation_before_binding_a_created_thread_is_not_lost() {
        let ticket = Ticket::capture("new_session", None);
        ticket.launch(|| Ok(((), 505))).unwrap();
        interrupt("new_session", "official", |_| {});
        let killed = Mutex::new(Vec::new());
        assert!(!ticket.bind("official", |pid| killed.lock().unwrap().push(pid)));
        assert_eq!(*killed.lock().unwrap(), vec![505]);
        assert!(!ticket.is_current());
    }

    #[test]
    fn unknown_interrupt_tombstone_is_not_misreported_as_a_known_runtime() {
        assert!(!interrupt("unknown_confirmation", "unknown", |_| panic!(
            "no PID"
        )));
        assert!(!interrupt("unknown_confirmation", "unknown", |_| panic!(
            "an empty fence still is not a known CLI"
        )));
        let _ticket = Ticket::capture("unknown_confirmation", Some("known"));
        assert!(interrupt("unknown_confirmation", "known", |_| {}));
    }

    #[test]
    fn production_interrupt_reports_unknown_without_preparing_or_launching_a_cli() {
        let mut runtime = crate::codex_runtime::Runtime::default();
        runtime.scope = "interrupt_confirmation_api_test".into();
        crate::codex_runtime::with(runtime, || {
            let sid = "session-without-runtime-or-pid";
            assert_eq!(crate::agents::codex_interrupt(sid).unwrap()["known"], false);
            assert_eq!(crate::agents::codex_interrupt(sid).unwrap()["known"], false);
            let ticket = Ticket::capture("interrupt_confirmation_api_test", Some(sid));
            assert_eq!(crate::agents::codex_interrupt(sid).unwrap()["known"], true);
            assert!(!ticket.is_current());
        });
    }
}

//! Self-healing policy engine: keeps each device's egress pointed at the
//! currently healthy egress device, and restarts watched services when they die.
//!
//! v1 is intentionally declarative + dry-run by default: the hub only *observes*
//! until `dryRun` is turned off, so enabling the engine can never silently move
//! someone's system proxy.
use crate::App;
use vibelink_protocol::{now_ms, Command};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

/// Suppress repeated dry-run/action log lines for the same target (5 min).
static LAST_LOGGED: OnceLock<Mutex<HashMap<String, (String, u64)>>> = OnceLock::new();
pub static TICKS: AtomicU64 = AtomicU64::new(0);

pub fn ticks() -> u64 {
    TICKS.load(Ordering::Relaxed)
}

fn should_log(key: &str, target: &str) -> bool {
    let map = LAST_LOGGED.get_or_init(|| Mutex::new(HashMap::new()));
    let mut g = map.lock().unwrap();
    let now = now_ms();
    match g.get(key) {
        Some((t, ts)) if t == target && now.saturating_sub(*ts) < 300_000 => false,
        _ => {
            g.insert(key.to_string(), (target.to_string(), now));
            true
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Policy {
    pub enabled: bool,
    pub dry_run: bool,
    #[serde(default)]
    pub rules: Vec<Rule>,
    #[serde(default)]
    pub watchdogs: Vec<Watchdog>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Rule {
    pub id: String,
    /// device whose system proxy is managed
    pub device: String,
    /// device that provides the egress
    pub egress: String,
    pub port: u16,
    /// proxy to use when the egress is offline
    pub fallback: String,
    #[serde(default = "yes")]
    pub enabled: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Watchdog {
    pub id: String,
    pub device: String,
    pub service: String,
    /// if this port is closed on the device, restart the service
    pub port: u16,
    #[serde(default = "yes")]
    pub enabled: bool,
}

fn yes() -> bool {
    true
}

impl Default for Policy {
    fn default() -> Self {
        Self {
            enabled: true,
            dry_run: true,
            rules: vec![Rule {
                id: "pc-follows-laptop".into(),
                device: "pc-rs".into(),
                egress: "laptop-rs".into(),
                port: 7897,
                fallback: "127.0.0.1:7897".into(),
                enabled: true,
            }],
            watchdogs: vec![
                Watchdog {
                    id: "laptop-hysteria".into(),
                    device: "laptop-rs".into(),
                    service: "WProxyHysteria".into(),
                    port: 1082,
                    enabled: true,
                },
                Watchdog {
                    id: "laptop-mihomo".into(),
                    device: "laptop-rs".into(),
                    service: "WProxy".into(),
                    port: 7897,
                    enabled: true,
                },
            ],
        }
    }
}

const OFFLINE_MS: u64 = 20_000;
const COMMAND_COOLDOWN_MS: u64 = 20_000;
const RESTART_COOLDOWN_MS: u64 = 300_000;

pub fn spawn(app: Arc<App>) {
    tokio::spawn(async move {
        let mut tick = tokio::time::interval(Duration::from_secs(5));
        loop {
            tick.tick().await;
            TICKS.fetch_add(1, Ordering::Relaxed);
            if let Err(e) = reconcile(&app) {
                eprintln!("[policy] {e}");
            }
        }
    });
}

/// Best LAN address for a device: prefer the hotspot 192.168.137.x, then any
/// 192.168.x, then Tailscale (100.64/10), then whatever is left.
fn pick_ip(ips: &[String]) -> Option<String> {
    let by_prefix = |p: &str| ips.iter().find(|ip| ip.starts_with(p)).cloned();
    by_prefix("192.168.137.")
        .or_else(|| by_prefix("192.168."))
        .or_else(|| by_prefix("100."))
        .or_else(|| ips.first().cloned())
}

fn has_recent_command(store: &crate::Store, device: &str, action: &str, arg: Option<&str>, within_ms: u64) -> bool {
    let now = now_ms();
    store.commands.iter().rev().take(200).any(|c| {
        if c.device != device || c.action != action {
            return false;
        }
        if now.saturating_sub(c.created_at) > within_ms {
            return false;
        }
        match arg {
            None => true,
            Some(name) => c.args.get("name").and_then(|v| v.as_str()) == Some(name),
        }
    })
}

fn enqueue(store: &mut crate::Store, device: &str, action: &str, args: serde_json::Value) {
    let cmd = Command {
        id: App::next_id(store),
        device: device.to_string(),
        action: action.to_string(),
        args,
        status: "pending".into(),
        created_at: now_ms(),
        started_at: None,
        finished_at: None,
        result: None,
    };
    store.commands.push(cmd);
}

fn reconcile(app: &App) -> Result<(), String> {
    let policy = app.policy.lock().map_err(|_| "policy lock")?.clone();
    if !policy.enabled {
        return Ok(());
    }
    let now = now_ms();
    let mut store = app.store.lock().map_err(|_| "store lock")?;
    let mut events: Vec<(Option<String>, String, String)> = Vec::new();

    for rule in policy.rules.iter().filter(|r| r.enabled) {
        let Some(follower) = store.devices.get(&rule.device) else { continue };
        if now.saturating_sub(follower.last_seen) > OFFLINE_MS {
            continue; // follower offline: nothing to steer
        }
        let egress = store.devices.get(&rule.egress);
        let egress_online = egress
            .map(|d| now.saturating_sub(d.last_seen) <= OFFLINE_MS)
            .unwrap_or(false);
        let egress_healthy = egress_online
            && egress
                .map(|d| d.report.proxy.server.is_some() || d.report.ports.get(&rule.port.to_string()).copied().unwrap_or(false))
                .unwrap_or(false);

        let target = if egress_healthy {
            egress
                .and_then(|d| pick_ip(&d.report.ips))
                .map(|ip| format!("{ip}:{}", rule.port))
                .unwrap_or_else(|| rule.fallback.clone())
        } else {
            rule.fallback.clone()
        };

        let current = follower.report.proxy.server.clone().unwrap_or_default();
        if std::env::var("VIBELINK_POLICY_DEBUG").is_ok() {
            eprintln!(
                "[policy] rule={} follower={} current='{}' target='{}' online={} egress_proxy={:?}",
                rule.id, rule.device, current, target, egress_online,
                egress.and_then(|d| d.report.proxy.server.clone())
            );
        }
        if current == target {
            continue;
        }
        let key = format!("rule:{}", rule.id);
        let do_log = should_log(&key, &target);
        let msg = format!(
            "proxy {} -> {}（原 {}，出口 {}）",
            rule.device,
            target,
            if current.is_empty() { "未设置" } else { &current },
            if egress_healthy { "在线" } else { "离线→兜底" }
        );
        if policy.dry_run {
            if do_log {
                events.push((Some(rule.device.clone()), "policy-dry".into(), msg));
            }
        } else if !has_recent_command(&store, &rule.device, "set-proxy", None, COMMAND_COOLDOWN_MS) {
            enqueue(&mut store, &rule.device, "set-proxy", serde_json::json!({ "server": target }));
            if do_log {
                events.push((Some(rule.device.clone()), "policy".into(), msg));
            }
        }
    }

    for wd in policy.watchdogs.iter().filter(|w| w.enabled) {
        let Some(dev) = store.devices.get(&wd.device) else { continue };
        if now.saturating_sub(dev.last_seen) > OFFLINE_MS {
            continue;
        }
        let closed = dev.report.ports.get(&wd.port.to_string()).copied() == Some(false);
        if !closed {
            continue;
        }
        if has_recent_command(&store, &wd.device, "restart-service", Some(&wd.service), RESTART_COOLDOWN_MS) {
            continue;
        }
        let key = format!("wd:{}", wd.id);
        let do_log = should_log(&key, &wd.service);
        let msg = format!("{} 端口 {} 不通 → 重启 {}", wd.device, wd.port, wd.service);
        if policy.dry_run {
            if do_log {
                events.push((Some(wd.device.clone()), "policy-dry".into(), msg));
            }
        } else {
            enqueue(&mut store, &wd.device, "restart-service", serde_json::json!({ "name": wd.service }));
            if do_log {
                events.push((Some(wd.device.clone()), "policy".into(), msg));
            }
        }
    }

    let changed = !events.is_empty();
    for (dev, kind, msg) in events {
        App::event(&mut store, dev, &kind, msg);
    }
    if changed {
        app.persist(&store);
    }
    Ok(())
}




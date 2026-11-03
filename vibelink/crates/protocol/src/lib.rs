//! Shared wire types between hub, agent and GUI.
//! JSON keys are camelCase to stay compatible with the Node hub (mesh/hub/server.ts).
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub const PROTOCOL_VERSION: &str = "0.1.0";

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProxyState {
    pub enabled: bool,
    pub server: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct Latency {
    pub target: String,
    pub tcp_ms: Option<u64>,
    pub connect_ms: Option<u64>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct MemInfo {
    pub free: u64,
    pub total: u64,
}

/// What an agent reports on every heartbeat.
#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct DeviceReport {
    pub id: String,
    pub hostname: String,
    pub platform: String,
    pub arch: String,
    pub agent_version: String,
    #[serde(default)]
    pub ips: Vec<String>,
    #[serde(default)]
    pub gateway: Option<String>,
    #[serde(default)]
    pub proxy: ProxyState,
    #[serde(default)]
    pub ports: BTreeMap<String, bool>,
    #[serde(default)]
    pub opencode: Option<u16>,
    #[serde(default)]
    pub latency: Option<Latency>,
    #[serde(default)]
    pub mem: MemInfo,
    #[serde(default)]
    pub uptime_sec: u64,
    #[serde(default)]
    pub note: Option<String>,
}

/// Device as stored/returned by the hub (report + presence).
#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct Device {
    #[serde(flatten)]
    pub report: DeviceReport,
    pub first_seen: u64,
    pub last_seen: u64,
    #[serde(default)]
    pub online: bool,
    #[serde(default)]
    pub age_ms: u64,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct Command {
    pub id: String,
    pub device: String,
    pub action: String,
    #[serde(default)]
    pub args: serde_json::Value,
    pub status: String, // pending | running | done | failed
    pub created_at: u64,
    pub started_at: Option<u64>,
    pub finished_at: Option<u64>,
    pub result: Option<serde_json::Value>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct Event {
    pub at: u64,
    pub device: Option<String>,
    pub kind: String,
    pub message: String,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct ConfigInfo {
    pub name: String,
    pub versions: Vec<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Health {
    pub ok: bool,
    pub version: String,
    pub protocol: String,
    pub devices: usize,
}

pub fn now_ms() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

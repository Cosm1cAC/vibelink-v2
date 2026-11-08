//! Explicit live-test transport worker. It bypasses neither runtime policy nor
//! the production singleton: only this isolated test process omits main().
use serde::Deserialize;
use std::{fs, path::PathBuf, thread, time::Duration};

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Config {
    hub: String,
    token: String,
    device: String,
    data: PathBuf,
    stop_file: PathBuf,
}

#[test]
#[ignore = "Requires VIBELINK_GROUP_WORK_SMOKE_REQUEST for a disposable Hub/Agent integration test"]
fn temporary_worker_uses_production_session_transport() {
    let request = std::env::var_os("VIBELINK_GROUP_WORK_SMOKE_REQUEST")
        .expect("Set the disposable worker request file");
    let config: Config = serde_json::from_slice(&fs::read(request).expect("Read worker request"))
        .expect("Parse worker request");
    assert!(
        !config.token.is_empty() && !config.device.is_empty(),
        "Worker identity is required"
    );
    if config.stop_file.exists() {
        return;
    }
    fs::create_dir_all(&config.data).expect("Prepare isolated worker data directory");
    let endpoint = crate::parse_endpoint(&config.hub).expect("Use an HTTP test Hub");
    let report = vibelink_protocol::DeviceReport {
        id: config.device.clone(),
        hostname: "disposable-group-work-worker".into(),
        platform: std::env::consts::OS.into(),
        agent_version: crate::AGENT_VERSION.into(),
        ..Default::default()
    };
    let report = serde_json::to_string(&report).expect("Serialize worker heartbeat");
    let (hub, token, device, data) = (config.hub, config.token.clone(), config.device, config.data);
    // Same handler/poll/result path as production. Explicit data avoids changing
    // the process environment or touching the production Agent data directory.
    thread::spawn(move || crate::session_worker_in_data(hub, token, device, data));
    while !config.stop_file.exists() {
        let (status, _) = crate::http_call(
            &endpoint,
            "POST",
            "/api/ingest",
            &config.token,
            Some(&report),
        )
        .expect("Temporary Hub heartbeat failed");
        assert_eq!(status, 200, "Temporary Hub rejected worker heartbeat");
        thread::sleep(Duration::from_millis(500));
    }
    // The worker thread terminates with this opt-in test process; no service,
    // scheduled task or production singleton is stopped or replaced.
}

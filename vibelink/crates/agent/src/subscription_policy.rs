//! Subscription credentials stay inside the official CLI. Never inherit gateways.
use std::{fs, path::PathBuf, process::Command};

pub fn cli_home() -> PathBuf {
    let home =
        std::env::var(if cfg!(windows) { "USERPROFILE" } else { "HOME" }).unwrap_or_default();
    PathBuf::from(home)
        .join(".vibelink-cli")
        .join("dev")
        .join("codex")
}

pub fn prepare_codex() -> Result<(), String> { prepare_codex_in(&cli_home()) }
pub fn prepare_codex_in(dir: &std::path::Path) -> Result<(), String> {
    fs::create_dir_all(&dir).map_err(|_| "Cannot create isolated Codex CLI home")?;
    let auth = dir.join("auth.json");
    if !auth.exists() {
        let home = std::env::var("CODEX_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|_| {
                PathBuf::from(
                    std::env::var(if cfg!(windows) { "USERPROFILE" } else { "HOME" })
                        .unwrap_or_default(),
                )
                .join(".codex")
            });
        // Import only ChatGPT login credentials, never API keys or user config.
        let bytes = fs::read(home.join("auth.json")).map_err(
            |_| "Sign in using official codex login before using the development dispatcher",
        )?;
        let value: serde_json::Value =
            serde_json::from_slice(&bytes).map_err(|_| "Invalid Codex CLI credentials")?;
        if value["tokens"]["access_token"].as_str().is_none()
            || value["OPENAI_API_KEY"]
                .as_str()
                .is_some_and(|s| !s.is_empty())
        {
            return Err("VibeLink Codex requires official ChatGPT subscription login".into());
        }
        fs::write(&auth, bytes).map_err(|_| "Cannot initialize isolated Codex login")?;
    }
    // A dedicated home excludes personal profiles, MCP gateways and project overrides.
    fs::write(dir.join("config.toml"), "model_provider = \"openai\"\nforced_login_method = \"chatgpt\"\ncli_auth_credentials_store = \"file\"\n").map_err(|_| "Cannot configure official Codex provider")?;
    Ok(())
}

pub fn validate_project(cwd: &str) -> Result<(), String> {
    let user_home=std::env::var(if cfg!(windows){"USERPROFILE"}else{"HOME"}).unwrap_or_default();
    for dir in std::path::Path::new(cwd).ancestors() {
        if dir == std::path::Path::new(&user_home) {break;}
        if let Ok(config) = fs::read_to_string(dir.join(".codex/config.toml")) {
            if crate::codex_runtime::current().beta || crate::codex_runtime::current().group_work.is_some() {
                return Err("Public/group workspaces cannot override Codex configuration".into());
            }

            if [
                "base_url",
                "model_provider",
                "chatgpt_base_url",
                "forced_login_method",
                "cli_auth_credentials_store",
            ]
            .iter()
            .any(|key| config.contains(key))
            {
                return Err("Project Codex configuration overrides the subscription route; remove the provider override".into());
            }
        }
    }
    Ok(())
}

pub fn configure_codex(cmd: &mut Command) {
    cmd.env("CODEX_HOME", cli_home());
    clear_overrides(cmd);
    cmd.args([
        "-c",
        "model_provider=\"openai\"",
        "-c",
        "forced_login_method=\"chatgpt\"",
    ]);
}

pub fn clear_overrides(cmd: &mut Command) {
    for key in [
        "OPENAI_BASE_URL",
        "OPENAI_API_BASE",
        "OPENAI_API_KEY",
        "OPENAI_ORG_ID",
        "OPENAI_PROJECT_ID",
        "CODEX_API_KEY",
        "CODEX_BASE_URL",
        "CODEX_PROFILE",
        "HTTP_PROXY",
        "HTTPS_PROXY",
        "ALL_PROXY",
        "http_proxy",
        "https_proxy",
        "all_proxy",
    ] {
        cmd.env_remove(key);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cli_process_has_no_inherited_gateway_settings() {
        let mut cmd = Command::new("codex");
        configure_codex(&mut cmd);
        let env: Vec<_> = cmd.get_envs().collect();
        assert!(
            env.iter()
                .any(|(k, v)| *k == "OPENAI_BASE_URL" && v.is_none())
        );
        assert!(env.iter().any(|(k, v)| *k == "HTTPS_PROXY" && v.is_none()));
        let args: Vec<_> = cmd.get_args().map(|a| a.to_string_lossy()).collect();
        assert!(args.contains(&"forced_login_method=\"chatgpt\"".into()));
        assert!(args.contains(&"model_provider=\"openai\"".into()));
    }
}

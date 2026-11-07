//! Request-scoped official Codex profiles. Only DeepSeek is allowed a gateway provider.
use serde_json::Value;
use std::{cell::RefCell, fs, path::PathBuf, process::Command, thread};

pub const DEEPSEEK: &str = "auto/deepseek-v4.1-flash";
#[derive(Clone, Debug)]
pub struct Runtime {
    pub scope: String,
    pub model: String,
    pub beta: bool,
    pub read_only: bool,
    pub gateway: String,
    pub token: String,
}
impl Default for Runtime {
    fn default() -> Self {
        Self {
            scope: "dev_gpt".into(),
            model: String::new(),
            beta: false,
            read_only: false,
            gateway: String::new(),
            token: String::new(),
        }
    }
}
thread_local! {static CURRENT:RefCell<Runtime>=RefCell::new(Runtime::default());}
pub fn current() -> Runtime {
    CURRENT.with(|r| r.borrow().clone())
}
pub fn with<T>(runtime: Runtime, f: impl FnOnce() -> T) -> T {
    struct Restore(Runtime);
    impl Drop for Restore {
        fn drop(&mut self) {
            CURRENT.with(|r| *r.borrow_mut() = self.0.clone());
        }
    }
    let old = CURRENT.with(|r| r.replace(runtime));
    let _restore = Restore(old);
    f()
}
pub fn spawn<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> thread::JoinHandle<T> {
    let r = current();
    thread::spawn(move || with(r, f))
}
impl Runtime {
    pub fn parse(payload: &Value) -> Result<Self, String> {
        let Some(value) = payload.get("codexRuntime") else {
            return Ok(Self::default());
        };
        let r = Self {
            scope: value["scope"].as_str().unwrap_or("").into(),
            model: value["model"].as_str().unwrap_or("").into(),
            beta: value["beta"].as_bool().unwrap_or(false),
            read_only: value["readOnly"].as_bool().unwrap_or(false),
            gateway: value["gateway"].as_str().unwrap_or("").into(),
            token: value["token"].as_str().unwrap_or("").into(),
        };
        if r.scope.is_empty()
            || !r
                .scope
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || c == b'_')
        {
            return Err("Invalid Codex runtime scope".into());
        }
        if r.beta && r.model != DEEPSEEK {
            return Err("Public workers may only use DeepSeek".into());
        }
        if !r.model.is_empty() && r.model != DEEPSEEK && !r.model.starts_with("gpt-") {
            return Err("Unsupported model".into());
        }
        if r.model == DEEPSEEK
            && !r.gateway.is_empty()
            && !(r.gateway.starts_with("http://") || r.gateway.starts_with("https://"))
        {
            return Err("Invalid model gateway".into());
        }
        Ok(r)
    }
    pub fn home(&self) -> PathBuf {
        if self.scope == "dev_gpt" {
            crate::subscription_policy::cli_home()
        } else {
            crate::subscription_policy::cli_home()
                .parent()
                .unwrap()
                .parent()
                .unwrap()
                .join("scopes")
                .join(&self.scope)
                .join("codex")
        }
    }
    pub fn workspace(&self) -> PathBuf {
        self.home().parent().unwrap().join("workspace")
    }
    pub fn prepare(&self) -> Result<(), String> {
        if self.model != DEEPSEEK {
            return crate::subscription_policy::prepare_codex_in(&self.home());
        }
        fs::create_dir_all(self.home()).map_err(|e| e.to_string())?;
        // Never copy ChatGPT auth or provider settings into a DeepSeek profile.
        let mut config = String::from("cli_auth_credentials_store = \"file\"\n");
        if self.beta {
            config.push_str(&self.permissions_config());
        }
        fs::write(self.home().join("config.toml"), config).map_err(|e| e.to_string())?;
        if self.beta {
            fs::create_dir_all(self.workspace()).map_err(|e| e.to_string())?;
        }
        Ok(())
    }
    fn permissions_config(&self) -> String {
        // Legacy workspace-write permits reads outside the workspace. Never use it
        // as a tenant boundary. Unsupported restricted-read backends must refuse tools.
        let access = if self.read_only { "read" } else { "write" };
        format!(
            r#"
default_permissions = "vibelink_beta"
[permissions.vibelink_beta]
extends = ":workspace"
[permissions.vibelink_beta.filesystem]
":root" = "deny"
":minimal" = "read"
":tmpdir" = "deny"
":slash_tmp" = "deny"
[permissions.vibelink_beta.filesystem.":workspace_roots"]
"." = "{access}"
[permissions.vibelink_beta.network]
enabled = false
"#
        )
    }
    pub fn configure(&self, cmd: &mut Command) -> Result<(), String> {
        if self.beta {
            cmd.env_clear();
            for key in [
                "PATH",
                "SystemRoot",
                "WINDIR",
                "COMSPEC",
                "PATHEXT",
                "TEMP",
                "TMP",
            ] {
                if let Some(value) = std::env::var_os(key) {
                    cmd.env(key, value);
                }
            }
            let isolated = self.home().parent().unwrap().to_path_buf();
            cmd.env("HOME", &isolated)
                .env("USERPROFILE", &isolated)
                .env("APPDATA", isolated.join("AppData/Roaming"))
                .env("LOCALAPPDATA", isolated.join("AppData/Local"));
        }
        crate::subscription_policy::clear_overrides(cmd);
        cmd.env("CODEX_HOME", self.home());
        if self.model == DEEPSEEK {
            if self.gateway.is_empty() || self.token.is_empty() {
                return Err("Metered DeepSeek gateway grant is required".into());
            }
            cmd.env("VIBELINK_MODEL_GATEWAY_TOKEN", &self.token);
            // JSON string escaping is also valid TOML basic-string escaping.
            let url = serde_json::to_string(&self.gateway).unwrap();
            let table = format!(
                "model_providers.vibelink={{name=\"VibeLink DeepSeek\",base_url={url},wire_api=\"responses\",env_key=\"VIBELINK_MODEL_GATEWAY_TOKEN\",requires_openai_auth=false}}"
            );
            cmd.args([
                "-c",
                "model_provider=\"vibelink\"",
                "-c",
                &table,
                "--model",
                DEEPSEEK,
                "-c",
                "model_supports_reasoning=false",
            ]);
        } else {
            crate::subscription_policy::configure_codex(cmd);
            cmd.env("CODEX_HOME", self.home());
            if !self.model.is_empty() {
                cmd.args(["--model", &self.model]);
            }
        }
        if self.beta {
            cmd.args([
                "--strict-config",
                "--ask-for-approval",
                "never",
                "-c",
                "default_permissions=\"vibelink_beta\"",
            ]);
        }
        Ok(())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn deepseek_uses_official_cli_custom_provider_without_chatgpt_credentials() {
        let r=Runtime::parse(&json!({"codexRuntime":{"scope":"beta_01","model":DEEPSEEK,"beta":true,"gateway":"http://127.0.0.1:18995/api/llm/v1","token":"test-grant"}})).unwrap();
        let mut cmd = Command::new("codex");
        cmd.env("VIBELINK_TOKEN", "must-not-reach-tools");
        r.configure(&mut cmd).unwrap();
        let args: Vec<_> = cmd
            .get_args()
            .map(|v| v.to_string_lossy().to_string())
            .collect();
        assert!(args.iter().any(|v| v.contains("wire_api=\"responses\"")));
        assert!(!args.iter().any(|v| v.contains("forced_login_method")));
        assert!(!cmd.get_envs().any(|(key, _)| key == "VIBELINK_TOKEN"));
        assert_ne!(r.home(), Runtime::default().home());
        assert!(args.iter().any(|v| v.contains("default_permissions=")));
        let policy = r.permissions_config();
        assert!(policy.contains("\":root\" = \"deny\""));
        assert!(policy.contains("enabled = false"));
        assert!(args.iter().any(|v| v == "--strict-config"));
    }
    #[test]
    fn new_runtime_protocol_rejects_requests_without_binding_before_any_cli_launch() {
        let error = crate::handle_session_request(
            "codex.runtime.create",
            &json!({"text":"test"}),
            &std::env::temp_dir(),
        )
        .unwrap_err();
        assert!(error.contains("Runtime binding required"));
    }
    #[test]
    fn invalid_scope_and_public_gpt_binding_are_rejected() {
        assert!(
            Runtime::parse(&json!({"codexRuntime":{"scope":"../owner","model":DEEPSEEK}})).is_err()
        );
        assert!(
            Runtime::parse(
                &json!({"codexRuntime":{"scope":"beta_01","beta":true,"model":"gpt-5"}})
            )
            .is_err()
        );
    }
    #[test]
    fn runtime_scope_is_preserved_in_background_threads_and_restored() {
        let mut r = Runtime::default();
        r.scope = "other".into();
        with(r, || {
            assert_eq!(spawn(|| current().scope).join().unwrap(), "other")
        });
        assert_eq!(current().scope, "dev_gpt");
    }
}

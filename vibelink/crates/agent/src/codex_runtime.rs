//! Request-scoped official Codex profiles. Only DeepSeek is allowed a gateway provider.
use serde_json::Value;
use std::{cell::RefCell, fs, path::PathBuf, process::Command, thread};

fn checked_tool_root(candidate: &std::path::Path, owner_home: &std::path::Path) -> Option<PathBuf> {
    let root = crate::roots::normalize(&fs::canonicalize(candidate).ok()?);
    let home = crate::roots::normalize(&fs::canonicalize(owner_home).ok()?);
    let key = |p: &std::path::Path| {
        p.to_string_lossy()
            .replace('\\', "/")
            .trim_end_matches('/')
            .to_lowercase()
    };
    let root_key = key(&root);
    let home_key = key(&home);
    if !root.is_dir() || root.components().filter(|c|matches!(c,std::path::Component::Normal(_))).count()==0
        || home_key==root_key || home_key.starts_with(&format!("{root_key}/"))
        // Builtin discovery never follows an installation junction into unrelated user data.
        || root_key!=key(candidate)
        || root.components().any(|c|matches!(c.as_os_str().to_string_lossy().to_lowercase().as_str(),".vibelink-cli"|".vibelink-worktrees"|".codex"|".claude"|".gemini"|".ssh"|"workspace"|"workspaces"|"scopes"|"sessions"))
    {
        return None;
    }
    Some(root)
}
/// Host-owned builtin installation discovery only; neither payloads nor models add read roots.
fn trusted_tool_roots() -> Vec<PathBuf> {
    if !cfg!(windows) {
        return Vec::new();
    }
    let owner = std::env::var_os("USERPROFILE")
        .map(PathBuf::from)
        .unwrap_or_default();
    let mut candidates = Vec::new();
    for variable in ["LOCALAPPDATA", "ProgramFiles"] {
        if let Some(base) = std::env::var_os(variable) {
            let root = if variable == "LOCALAPPDATA" {
                PathBuf::from(base).join("Programs/PowerShell/7")
            } else {
                PathBuf::from(base).join("PowerShell/7")
            };
            if root.join("pwsh.exe").is_file() {
                candidates.push(root);
            }
        }
    }
    let drive = std::env::var("SystemDrive").unwrap_or_else(|_| "C:".into());
    if let Ok(entries) = fs::read_dir(format!("{drive}\\")) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if name.strip_prefix("Python").is_some_and(|version| {
                !version.is_empty()
                    && version.len() <= 10
                    && version.bytes().all(|b| b.is_ascii_digit() || b == b'.')
            }) && entry.path().join("python.exe").is_file()
            {
                candidates.push(entry.path());
            }
        }
    }
    let mut roots: Vec<_> = candidates
        .into_iter()
        .filter_map(|p| checked_tool_root(&p, &owner))
        .collect();
    roots.sort();
    roots.dedup();
    roots
}

pub const DEEPSEEK: &str = "auto/deepseek-v4.1-flash";
#[derive(Clone, Debug, serde::Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GroupWork {
    pub workspace: String,
    pub work_id: String,
    pub step_id: String,
    pub mandate_id: String,
    pub version: u64,
    pub allow_write: bool,
}
#[derive(Clone, Debug)]
pub struct Runtime {
    pub scope: String,
    pub model: String,
    pub beta: bool,
    pub read_only: bool,
    pub gateway: String,
    pub token: String,
    pub group_work: Option<GroupWork>,
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
            group_work: None,
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
        let mut r = Self {
            scope: value["scope"].as_str().unwrap_or("").into(),
            model: value["model"].as_str().unwrap_or("").into(),
            beta: value["beta"].as_bool().unwrap_or(false),
            read_only: value["readOnly"].as_bool().unwrap_or(false),
            gateway: value["gateway"].as_str().unwrap_or("").into(),
            token: value["token"].as_str().unwrap_or("").into(),
            group_work: value
                .get("groupWork")
                .filter(|v| !v.is_null())
                .map(|v| {
                    serde_json::from_value(v.clone())
                        .map_err(|_| "Invalid group work runtime binding")
                })
                .transpose()?,
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
        if let Some(group) = &r.group_work {
            if !std::path::Path::new(&group.workspace).is_absolute()
                || group.workspace.len() > 8192
                || group.version == 0
                || [&group.work_id, &group.step_id, &group.mandate_id]
                    .iter()
                    .any(|s| {
                        s.is_empty()
                            || s.len() > 160
                            || !s
                                .bytes()
                                .all(|c| c.is_ascii_alphanumeric() || matches!(c, b'_' | b'-'))
                    })
            {
                return Err("Invalid group work authority/workspace".into());
            }
            // The group mandate may narrow existing permissions, never raise a plan/read-only Bot.
            r.read_only |= !group.allow_write;
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
    pub fn inputs_root(&self) -> PathBuf {
        self.home().parent().unwrap().join("inputs")
    }
    pub fn validate_group_cwd(&self, cwd: &str) -> Result<(), String> {
        let Some(group) = &self.group_work else {
            return Ok(());
        };
        let approved = canonical_workspace(std::path::Path::new(&group.workspace), false)?;
        let actual = canonical_workspace(std::path::Path::new(cwd), false)?;
        if approved != group.workspace || actual != group.workspace {
            return Err("group_workspace_changed: execution directory differs from approved canonical workspace".into());
        }
        if self.beta && canonical_workspace(&self.workspace(), false)? != group.workspace {
            return Err("Group work cannot override the tenant workspace".into());
        }
        Ok(())
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
            if cfg!(windows) {
                config.push_str("\n[windows]\nsandbox = \"mxc\"\n");
            }
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
        let inputs = serde_json::to_string(&self.inputs_root().to_string_lossy()).unwrap();
        let tools = trusted_tool_roots()
            .iter()
            .map(|root| {
                format!(
                    "{} = \"read\"\n",
                    serde_json::to_string(&root.to_string_lossy()).unwrap()
                )
            })
            .collect::<String>();
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
{inputs} = "read"
{tools}
[permissions.vibelink_beta.filesystem.":workspace_roots"]
"." = "{access}"
[permissions.vibelink_beta.network]
enabled = false
"#
        )
    }
    pub fn configure(&self, cmd: &mut Command) -> Result<(), String> {
        if self.beta || self.group_work.is_some() {
            cmd.env_clear();
            for key in [
                "PATH",
                "SystemRoot",
                "SystemDrive",
                "WINDIR",
                "COMSPEC",
                "PATHEXT",
                "TEMP",
                "TMP",
                "TMPDIR",
                "LANG",
                "LC_ALL",
                "TZ",
            ] {
                if let Some(value) = std::env::var_os(key) {
                    cmd.env(key, value);
                }
            }
            if self.beta {
                let isolated = self.home().parent().unwrap().to_path_buf();
                cmd.env("HOME", &isolated)
                    .env("USERPROFILE", &isolated)
                    .env("APPDATA", isolated.join("AppData/Roaming"))
                    .env("LOCALAPPDATA", isolated.join("AppData/Local"));
            } else {
                for key in [
                    "HOME",
                    "USERPROFILE",
                    "APPDATA",
                    "LOCALAPPDATA",
                    "USER",
                    "LOGNAME",
                    "SHELL",
                ] {
                    if let Some(value) = std::env::var_os(key) {
                        cmd.env(key, value);
                    }
                }
            }
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
            ]);
        } else {
            crate::subscription_policy::configure_codex(cmd);
            cmd.env("CODEX_HOME", self.home());
            if !self.model.is_empty() {
                cmd.args(["--model", &self.model]);
            }
        }
        if self.beta {
            if cfg!(windows) {
                // Explicit selection fails closed on hosts without MXC. Never
                // prefer-and-fallback to a legacy backend for public users.
                cmd.args(["-c", "windows.sandbox=\"mxc\""]);
            }
            cmd.args([
                "--no-daemon",
                "--strict-config",
                "--ask-for-approval",
                "never",
                "-c",
                "default_permissions=\"vibelink_beta\"",
            ]);
            // Freeze this turn's permission despite concurrent preparation of a shared
            // same-tenant profile. CLI overrides take precedence over the config file.
            let inputs = serde_json::to_string(&self.inputs_root().to_string_lossy()).unwrap();
            let tools = trusted_tool_roots()
                .iter()
                .map(|root| {
                    format!(
                        ",{}=\"read\"",
                        serde_json::to_string(&root.to_string_lossy()).unwrap()
                    )
                })
                .collect::<String>();
            cmd.args([
                "-c",
                &format!(
                    "permissions.vibelink_beta.filesystem={{\":root\"=\"deny\",\":minimal\"=\"read\",\":tmpdir\"=\"deny\",\":slash_tmp\"=\"deny\",\":workspace_roots\"={{\".\"=\"{}\"}},{inputs}=\"read\"{tools}}}",
                    if self.read_only { "read" } else { "write" }
                ),
            ]);
        } else if self.group_work.is_some() {
            if cfg!(windows) {
                // Standalone CLI profiles otherwise select no Windows backend. Require
                // native isolation, as for public workers; unsupported/policy-denied MXC
                // must fail closed rather than falling back to an unsandboxed command.
                cmd.args(["-c", "windows.sandbox=\"mxc\""]);
            }
            cmd.args([
                "--no-daemon",
                "--strict-config",
                "--ask-for-approval",
                "never",
                "--sandbox",
                if self.read_only {
                    "read-only"
                } else {
                    "workspace-write"
                },
                "-c",
                "sandbox_workspace_write.writable_roots=[]",
                "-c",
                "sandbox_workspace_write.exclude_tmpdir_env_var=true",
                "-c",
                "sandbox_workspace_write.exclude_slash_tmp=true",
                "-c",
                "sandbox_workspace_write.network_access=false",
            ]);
        }
        if self.group_work.is_some() {
            // The CLI consumes the grant, but model-generated tool subprocesses must not.
            cmd.args([
                "-c",
                "shell_environment_policy.filters={\"VIBELINK_*\"=\"exclude\"}",
                "-c",
                "shell_environment_policy.experimental_use_profile=false",
            ]);
        }
        Ok(())
    }
}

/// Resolve symlinks on the Agent, including existing ancestors of a new private tenant root.
/// No directories, CLI profiles, worktrees, processes or credentials are created by a probe.
pub fn canonical_workspace(path: &std::path::Path, allow_missing: bool) -> Result<String, String> {
    if !path.is_absolute() {
        return Err("Absolute Agent workspace required".into());
    }
    let mut existing = path.to_path_buf();
    let mut missing = Vec::new();
    while !existing.exists() && allow_missing {
        missing.push(
            existing
                .file_name()
                .ok_or("Invalid private workspace")?
                .to_os_string(),
        );
        existing = existing
            .parent()
            .ok_or("Workspace ancestor missing")?
            .to_path_buf();
    }
    let mut canonical = crate::roots::normalize(
        &fs::canonicalize(&existing)
            .map_err(|_| "Workspace is unavailable or cannot be canonicalized")?,
    );
    if !canonical.is_dir() {
        return Err("Workspace must be a directory".into());
    }
    for component in missing.into_iter().rev() {
        if component == ".." || component == "." {
            return Err("Noncanonical private workspace".into());
        }
        canonical.push(component);
    }
    Ok(crate::roots::normalize(&canonical)
        .to_string_lossy()
        .to_string())
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn group_payload(workspace: &str) -> Value {
        json!({"codexRuntime":{"scope":"dev_group_test","model":DEEPSEEK,"gateway":"http://127.0.0.1:18995/api/llm/v1","token":"disposable-grant","groupWork":{"workspace":workspace,"workId":"w1","stepId":"s1","mandateId":"m1","version":1,"allowWrite":true}}})
    }
    #[test]
    fn builtin_tool_read_roots_reject_home_drive_roots_profiles_and_workspaces() {
        let owner = std::env::var_os(if cfg!(windows) { "USERPROFILE" } else { "HOME" })
            .map(PathBuf::from)
            .unwrap();
        assert!(checked_tool_root(&owner, &owner).is_none());
        for ancestor in owner.ancestors().skip(1) {
            assert!(checked_tool_root(ancestor, &owner).is_none());
        }
        let path =
            std::env::temp_dir().join(format!("vibelink_tool_root_test_{}", std::process::id()));
        let leaf = path.join("tool-leaf");
        fs::create_dir_all(&leaf).unwrap();
        let canonical = crate::roots::normalize(&fs::canonicalize(&leaf).unwrap());
        assert!(checked_tool_root(&canonical, &owner).is_some());
        for name in [
            ".vibelink-cli",
            ".codex",
            "workspaces",
            "workspace",
            "scopes",
        ] {
            let forbidden = path.join(name);
            fs::create_dir(&forbidden).unwrap();
            assert!(checked_tool_root(&forbidden, &owner).is_none());
        }
        fs::remove_dir_all(path).unwrap();
        let mut runtime = Runtime::default();
        runtime.beta = true;
        runtime.model = DEEPSEEK.into();
        runtime.token = "disposable".into();
        runtime.gateway = "http://127.0.0.1:9/v1".into();
        let mut cmd = Command::new("codex");
        runtime.configure(&mut cmd).unwrap();
        let table = cmd
            .get_args()
            .map(|a| a.to_string_lossy())
            .find(|a| a.starts_with("permissions.vibelink_beta.filesystem="))
            .unwrap();
        for root in trusted_tool_roots() {
            assert!(table.contains(&format!(
                "{}=\"read\"",
                serde_json::to_string(&root.to_string_lossy()).unwrap()
            )));
            assert!(!table.contains(&format!(
                "{}=\"write\"",
                serde_json::to_string(&root.to_string_lossy()).unwrap()
            )));
        }
        assert!(table.contains("\":root\"=\"deny\""));
    }
    #[test]
    fn group_runtime_limits_writes_and_removes_host_admin_environment_before_cli_launch() {
        let cwd =
            canonical_workspace(std::path::Path::new(env!("CARGO_MANIFEST_DIR")), false).unwrap();
        let r = Runtime::parse(&group_payload(&cwd)).unwrap();
        let mut cmd = Command::new("codex");
        cmd.env("VIBELINK_TOKEN", "owner-secret")
            .env("VIBELINK_HUB", "admin-hub")
            .env("UNRELATED_SECRET", "secret");
        r.configure(&mut cmd).unwrap();
        let args: Vec<_> = cmd.get_args().map(|s| s.to_string_lossy()).collect();
        assert!(args.iter().any(|s| s == "workspace-write"));
        assert!(
            args.iter()
                .any(|s| s == "sandbox_workspace_write.writable_roots=[]")
        );
        assert!(
            args.iter()
                .any(|s| s == "sandbox_workspace_write.exclude_slash_tmp=true")
        );
        assert!(
            args.iter()
                .any(|s| s == "sandbox_workspace_write.network_access=false")
        );
        assert!(!args.iter().any(|s| s.contains("dangerously")));
        if cfg!(windows) {
            assert!(args.iter().any(|s| s == "windows.sandbox=\"mxc\""));
        }
        assert!(
            args.iter()
                .any(|s| s.contains("VIBELINK_*") && s.contains("exclude"))
        );
        let env: Vec<_> = cmd
            .get_envs()
            .filter_map(|(k, v)| v.map(|v| (k.to_string_lossy(), v.to_string_lossy())))
            .collect();
        assert!(!env.iter().any(|(k, _)| matches!(
            k.as_ref(),
            "VIBELINK_TOKEN" | "VIBELINK_HUB" | "UNRELATED_SECRET"
        )));
        assert!(env.iter().any(|(k, _)| k == "VIBELINK_MODEL_GATEWAY_TOKEN"));
        r.validate_group_cwd(&cwd).unwrap();
        assert!(
            r.validate_group_cwd(
                std::path::Path::new(&cwd)
                    .parent()
                    .unwrap()
                    .to_str()
                    .unwrap()
            )
            .is_err()
        );
    }
    #[test]
    fn group_authority_cannot_elevate_readonly_bot_or_public_worker() {
        let cwd =
            canonical_workspace(std::path::Path::new(env!("CARGO_MANIFEST_DIR")), false).unwrap();
        let mut p = group_payload(&cwd);
        p["codexRuntime"]["readOnly"] = json!(true);
        let readonly = Runtime::parse(&p).unwrap();
        assert!(readonly.read_only);
        let mut cmd = Command::new("codex");
        readonly.configure(&mut cmd).unwrap();
        assert!(cmd.get_args().any(|a| a == "read-only"));
        p["codexRuntime"]["beta"] = json!(true);
        let beta = Runtime::parse(&p).unwrap();
        let mut cmd = Command::new("codex");
        beta.configure(&mut cmd).unwrap();
        assert!(
            cmd.get_args()
                .any(|a| a == "default_permissions=\"vibelink_beta\"")
        );
        assert!(!cmd.get_args().any(|a| a == "--sandbox"));
        let fs = cmd
            .get_args()
            .map(|a| a.to_string_lossy())
            .find(|a| a.starts_with("permissions.vibelink_beta.filesystem="))
            .unwrap();
        assert!(fs.contains("\":workspace_roots\"={\".\"=\"read\"}"));
        for rule in [
            "\":root\"=\"deny\"",
            "\":minimal\"=\"read\"",
            "\":tmpdir\"=\"deny\"",
            "\":slash_tmp\"=\"deny\"",
        ] {
            assert!(fs.contains(rule));
        }
        assert!(!cmd.get_args().any(|a| {
            a.to_string_lossy()
                .starts_with("permissions.vibelink_beta.filesystem.\"")
        }));
        assert!(beta.permissions_config().contains("\":root\" = \"deny\""));
        assert!(beta.permissions_config().contains("\".\" = \"read\""));
        assert!(beta.validate_group_cwd(&cwd).is_err());
        let mut denied = group_payload(&cwd);
        denied["codexRuntime"]["groupWork"]["allowWrite"] = json!(false);
        assert!(Runtime::parse(&denied).unwrap().read_only);
    }
    #[test]
    fn group_probe_is_readonly_and_worktree_initialization_never_falls_back_to_shared() {
        let runtime = Runtime::default();
        let base =
            canonical_workspace(std::path::Path::new(env!("CARGO_MANIFEST_DIR")), false).unwrap();
        let id = format!("probe_only_{}", std::process::id());
        let candidate = std::path::Path::new(&base)
            .join(".vibelink-worktrees")
            .join(crate::pty_and_worktree::sanitize_slug(&id));
        assert!(!candidate.exists());
        with(runtime, || {
            let value=crate::handle_session_request("codex.runtime.workspace",&json!({"codexRuntime":{"scope":"dev_gpt"},"cwd":base,"botId":id,"isolationMode":"worktree"}),&std::env::temp_dir()).unwrap();
            assert_eq!(value["needsWorktreeInit"], true);
            assert_eq!(value["baseCwd"], base);
            assert_eq!(value["cwd"], base);
            assert!(!candidate.exists());
            let read=crate::handle_session_request("codex.runtime.workspace",&json!({"codexRuntime":{"scope":"dev_gpt"},"cwd":base,"botId":id,"isolationMode":"worktree","groupWorkConsult":true}),&std::env::temp_dir()).unwrap();
            assert_eq!(read["needsWorktreeInit"], false);
            assert!(!candidate.exists());
            assert!(
                crate::agents::codex_workspace_prepare(
                    &json!({"cwd":base,"botId":id,"isolationMode":"worktree"})
                )
                .is_err()
            );
        });
    }
    #[test]
    fn malformed_group_metadata_and_relative_workspaces_are_refused() {
        let mut p = group_payload("relative/path");
        assert!(Runtime::parse(&p).is_err());
        let root =
            canonical_workspace(std::path::Path::new(env!("CARGO_MANIFEST_DIR")), false).unwrap();
        p = group_payload(&root);
        p["codexRuntime"]["groupWork"]["allowWrite"] = json!("true");
        assert!(Runtime::parse(&p).is_err());
        p = group_payload(&root);
        p["codexRuntime"]["groupWork"]["workId"] = json!("../other");
        assert!(Runtime::parse(&p).is_err());
        p = group_payload(&root);
        p["codexRuntime"]["groupWork"]["version"] = json!(0);
        assert!(Runtime::parse(&p).is_err());
    }
    #[test]
    fn readonly_group_attachment_staging_never_mutates_the_leased_source_directory() {
        let cwd =
            canonical_workspace(std::path::Path::new(env!("CARGO_MANIFEST_DIR")), false).unwrap();
        let mut payload = group_payload(&cwd);
        payload["codexRuntime"]["scope"] = json!(format!("dev_input_test_{}", std::process::id()));
        payload["codexRuntime"]["groupWork"]["allowWrite"] = json!(false);
        let runtime = Runtime::parse(&payload).unwrap();
        let root = runtime.inputs_root();
        with(runtime, || {
            let saved = crate::agents::save_uploaded_files(
                &cwd,
                Some(&json!([{"filename":"evidence.txt","data":"b2s="}])),
                true,
            );
            assert_eq!(saved.len(), 1);
            let file = std::path::Path::new(&saved[0].0);
            assert!(file.starts_with(&root));
            assert!(!file.starts_with(&cwd));
            assert_eq!(fs::read(file).unwrap(), b"ok");
        });
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn group_probe_rejects_invalid_explicit_cwd_instead_of_clamping_to_default() {
        let cwd =
            canonical_workspace(std::path::Path::new(env!("CARGO_MANIFEST_DIR")), false).unwrap();
        with(Runtime::default(), || {
            for raw in [
                "relative_directory".to_string(),
                std::path::Path::new(&cwd)
                    .join("missing_workspace_probe_directory")
                    .to_string_lossy()
                    .into_owned(),
            ] {
                assert!(crate::agents::codex_workspace(&json!({"cwd":raw})).is_err());
            }
            let outside = std::env::temp_dir();
            if !crate::roots::within(&outside, &crate::roots::roots()) {
                let error = crate::agents::codex_workspace(&json!({"cwd":outside})).unwrap_err();
                assert!(error.contains("group_workspace_denied"));
            }
            assert!(crate::agents::codex_workspace(&json!({"cwd":""})).is_ok());
        });
    }
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
        if cfg!(windows) {
            assert!(args.iter().any(|v| v == "windows.sandbox=\"mxc\""));
            assert!(
                cmd.get_envs()
                    .any(|(key, value)| key == "SystemDrive" && value.is_some())
            );
        }
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

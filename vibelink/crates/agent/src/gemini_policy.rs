//! Official Gemini subscription auth cannot be replaced by project dotenv/settings.
use serde_json::{Value, json};
use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
    process::Command,
};
const KEYS: &[&str] = &[
    "CODE_ASSIST_ENDPOINT",
    "CODE_ASSIST_API_VERSION",
    "GEMINI_API_KEY",
    "GOOGLE_API_KEY",
    "GOOGLE_GEMINI_BASE_URL",
    "GOOGLE_GEMINI_API_ENDPOINT",
    "GOOGLE_GENAI_USE_VERTEXAI",
    "GOOGLE_CLOUD_PROJECT",
    "GOOGLE_CLOUD_LOCATION",
    "GOOGLE_APPLICATION_CREDENTIALS",
    "GEMINI_CLI_HOME",
    "GEMINI_CLI_SYSTEM_SETTINGS_PATH",
    "GEMINI_CLI_SYSTEM_DEFAULTS_PATH",
];
fn check_env(text: &str) -> Result<(), String> {
    for line in text.lines() {
        let line = line.trim();
        if line.starts_with('#') {
            continue;
        }
        let line = line.strip_prefix("export ").unwrap_or(line);
        if let Some((key, value)) = line.split_once('=') {
            if KEYS.iter().any(|k| key.trim().eq_ignore_ascii_case(k)) && !value.trim().is_empty() {
                return Err("Gemini project environment overrides subscription auth/provider; remove the override before using this runner".into());
            }
        }
    }
    Ok(())
}
fn invalid_settings(value: &Value) -> bool {
    match value {
        Value::Object(map) => map.iter().any(|(key, value)| {
            let k = key.to_ascii_lowercase();
            (matches!(
                k.as_str(),
                "selectedtype" | "enforcedtype" | "selectedauthtype"
            ) && value.as_str().is_some_and(|v| v != "oauth-personal"))
                || matches!(
                    k.as_str(),
                    "baseurl" | "api_base" | "apiendpoint" | "apikey" | "apikeyhelper"
                )
                || (KEYS.iter().any(|name| key.eq_ignore_ascii_case(name)) && !value.is_null())
                || invalid_settings(value)
        }),
        Value::Array(v) => v.iter().any(invalid_settings),
        _ => false,
    }
}
fn guard_settings(file: &Path) -> Result<(), String> {
    if !file.exists() {
        return Ok(());
    }
    let bytes = fs::read(file).map_err(|_| "Cannot inspect Gemini subscription configuration")?;
    if bytes.len() > 1024 * 1024 {
        return Err("Gemini subscription configuration exceeds safe inspection size".into());
    }
    let value: Value =
        serde_json::from_slice(&bytes).map_err(|_| "Invalid Gemini subscription configuration")?;
    if invalid_settings(&value) {
        return Err("Gemini settings override official subscription auth/provider; remove the override before using this runner".into());
    }
    Ok(())
}
pub fn guard(home: &Path, cwd: &Path) -> Result<(), String> {
    let mut roots: Vec<PathBuf> = cwd.ancestors().map(Path::to_path_buf).collect();
    roots.push(home.to_path_buf());
    for root in roots {
        for name in [".env", ".gemini/.env"] {
            let file = root.join(name);
            if file.exists() {
                let bytes = fs::read(file)
                    .map_err(|_| "Cannot inspect Gemini environment configuration")?;
                if bytes.len() > 1024 * 1024 {
                    return Err(
                        "Gemini environment configuration exceeds safe inspection size".into(),
                    );
                }
                check_env(
                    std::str::from_utf8(&bytes)
                        .map_err(|_| "Invalid Gemini environment configuration")?,
                )?;
            }
        }
        guard_settings(&root.join(".gemini/settings.json"))?;
    }
    Ok(())
}
fn atomic(path: &Path, value: &Value) -> Result<(), String> {
    let temp = path.with_extension(format!("{}.tmp", crate::agents::new_uuid_v4()));
    let mut f = fs::File::create(&temp).map_err(|_| "Cannot prepare official Gemini policy")?;
    f.write_all(&serde_json::to_vec(value).unwrap())
        .and_then(|_| f.sync_all())
        .map_err(|_| "Cannot persist official Gemini policy")?;
    drop(f);
    fs::rename(temp, path).map_err(|_| "Cannot install official Gemini policy".into())
}
pub fn prepare(cmd: &mut Command, home: &Path, cwd: &Path) -> Result<(), String> {
    guard(home, cwd)?;
    fs::create_dir_all(home).map_err(|_| "Cannot prepare official Gemini profile")?;
    let policy = home.join("managed-official-settings.json");
    let defaults = home.join("managed-official-defaults.json");
    atomic(
        &policy,
        &json!({"security":{"auth":{"selectedType":"oauth-personal","enforcedType":"oauth-personal"}},"advanced":{"ignoreLocalEnv":true,"excludedEnvVars":KEYS}}),
    )?;
    atomic(&defaults, &json!({}))?;
    cmd.env_remove("CODE_ASSIST_ENDPOINT")
        .env_remove("CODE_ASSIST_API_VERSION")
        .env(
            "CODE_ASSIST_ENDPOINT",
            "https://cloudcode-pa.googleapis.com",
        )
        .env("CODE_ASSIST_API_VERSION", "v1internal")
        .env("GEMINI_CLI_SYSTEM_SETTINGS_PATH", policy)
        .env("GEMINI_CLI_SYSTEM_DEFAULTS_PATH", defaults);
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn dotenv_provider_values_are_rejected_without_echoing_the_value() {
        let secret = "test-only-secret";
        for key in KEYS {
            let error = check_env(&format!("export {key}={secret}")).unwrap_err();
            assert!(!error.contains(secret));
        }
        assert!(check_env("# GEMINI_API_KEY=example\nAPP_MODE=test").is_ok());
    }
    #[test]
    fn project_auth_and_baseurl_overrides_are_refused_but_personal_oauth_is_allowed() {
        assert!(invalid_settings(
            &json!({"security":{"auth":{"selectedType":"gemini-api-key"}}})
        ));
        assert!(invalid_settings(
            &json!({"env":{"GOOGLE_GEMINI_BASE_URL":"https://example.invalid"}})
        ));
        assert!(!invalid_settings(
            &json!({"security":{"auth":{"selectedType":"oauth-personal"}},"general":{"theme":"Default"}})
        ));
    }
    #[test]
    fn code_assist_oauth_endpoint_cannot_be_redirected_by_dotenv_or_inherited_env() {
        assert!(check_env("CODE_ASSIST_ENDPOINT=https://example.invalid").is_err());
        assert!(check_env("CODE_ASSIST_API_VERSION=custom").is_err());
        let root = std::env::temp_dir().join(format!(
            "vibelink_gemini_endpoint_{}",
            crate::agents::new_uuid_v4()
        ));
        let home = root.join("official");
        let cwd = root.join("workspace");
        fs::create_dir_all(&cwd).unwrap();
        let mut cmd = Command::new("unused");
        cmd.env("CODE_ASSIST_ENDPOINT", "https://example.invalid");
        prepare(&mut cmd, &home, &cwd).unwrap();
        assert!(cmd.get_envs().any(|(k, v)| k == "CODE_ASSIST_ENDPOINT"
            && v == Some(std::ffi::OsStr::new("https://cloudcode-pa.googleapis.com"))));
        assert!(cmd.get_envs().any(|(k, v)| k == "CODE_ASSIST_API_VERSION"
            && v == Some(std::ffi::OsStr::new("v1internal"))));
        let p: Value =
            serde_json::from_slice(&fs::read(home.join("managed-official-settings.json")).unwrap())
                .unwrap();
        assert!(
            p["advanced"]["excludedEnvVars"]
                .as_array()
                .unwrap()
                .contains(&json!("CODE_ASSIST_ENDPOINT"))
        );
    }
    #[test]
    fn managed_system_sources_enforce_oauth_and_disable_local_dotenv() {
        let root = std::env::temp_dir().join(format!(
            "vibelink_gemini_policy_{}",
            crate::agents::new_uuid_v4()
        ));
        let home = root.join("official");
        let cwd = root.join("workspace");
        fs::create_dir_all(&cwd).unwrap();
        let mut cmd = Command::new("unused");
        prepare(&mut cmd, &home, &cwd).unwrap();
        let p: Value =
            serde_json::from_slice(&fs::read(home.join("managed-official-settings.json")).unwrap())
                .unwrap();
        assert_eq!(p["security"]["auth"]["enforcedType"], "oauth-personal");
        assert_eq!(p["advanced"]["ignoreLocalEnv"], true);
        assert!(
            cmd.get_envs()
                .any(|(k, v)| k == "GEMINI_CLI_SYSTEM_SETTINGS_PATH"
                    && v == Some(home.join("managed-official-settings.json").as_os_str()))
        );
    }
}

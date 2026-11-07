//! Role recipes never select providers. Edition policy owns runtime bindings.
use std::path::PathBuf;

pub const DEEPSEEK_MODEL: &str = "deepseek-flash";

pub fn default_engine(template_id: &str, beta: bool) -> &'static str {
    if !beta && template_id == "tpl_grok_cos" {
        "codex"
    } else {
        "deepseek"
    }
}

pub fn validate_engine(engine: &str, beta: bool) -> Result<(), String> {
    if engine == "deepseek" || (!beta && engine == "codex") {
        Ok(())
    } else {
        Err("Only DeepSeek API and development Codex official CLI are enabled".into())
    }
}

pub fn data_dir(base: PathBuf, beta: bool) -> PathBuf {
    base.join(if beta { "beta" } else { "dev" })
}

/// Preserve the owner's existing pairing and fleet on the first scoped-data upgrade.
/// Legacy private data is imported only into dev, never into beta.
pub fn migrate_legacy(base: &std::path::Path) -> std::io::Result<()> {
    let dev = base.join("dev");
    if !base.join("token.txt").is_file() || dev.join("token.txt").exists() {
        return Ok(());
    }
    fn copy_tree(source: &std::path::Path, destination: &std::path::Path) -> std::io::Result<()> {
        std::fs::create_dir_all(destination)?;
        for entry in std::fs::read_dir(source)? {
            let entry = entry?;
            if entry.file_type()?.is_symlink() {
                continue;
            }
            let target = destination.join(entry.file_name());
            if entry.file_type()?.is_dir() {
                copy_tree(&entry.path(), &target)?;
            } else if !target.exists() {
                std::fs::copy(entry.path(), target)?;
            }
        }
        Ok(())
    }
    std::fs::create_dir_all(&dev)?;
    for name in [
        "auth.json",
        "tokens.json",
        "devices.json",
        "events.json",
        "commands.json",
        "policy.json",
        "bots_fleet.json",
        "approvals.json",
        "voice.json",
        "cindy-join.json",
    ] {
        if base.join(name).is_file() && !dev.join(name).exists() {
            std::fs::copy(base.join(name), dev.join(name))?;
        }
    }
    for name in ["configs", "apk", "agent"] {
        if base.join(name).is_dir() {
            copy_tree(&base.join(name), &dev.join(name))?;
        }
    }
    // Write the completion marker last, so an interrupted copy can be retried.
    std::fs::copy(base.join("token.txt"), dev.join("token.txt"))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn editions_have_distinct_resources_and_bindings() {
        assert_ne!(
            data_dir("data".into(), true),
            data_dir("data".into(), false)
        );
        assert_eq!(default_engine("tpl_grok_cos", false), "codex");
        assert_eq!(default_engine("tpl_grok_cos", true), "deepseek");
        assert_eq!(default_engine("tpl_benjamin_code", false), "deepseek");
        for engine in ["claude", "antigravity", "gemini", "opencode", "gpt"] {
            assert!(validate_engine(engine, false).is_err());
        }
        assert!(validate_engine("codex", true).is_err());
    }
}

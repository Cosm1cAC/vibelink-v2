//! Role recipes never select providers. Edition policy owns runtime bindings.
use std::path::PathBuf;

pub const DEEPSEEK_MODEL: &str = "auto/deepseek-v4.1-flash";

pub fn engine_for_model(model: &str) -> Result<&'static str, String> {
    if model == DEEPSEEK_MODEL { Ok("opencode2") }
    else if model.is_empty() || model.starts_with("gpt-") { Ok("codex") }
    else if model=="claude" || model.starts_with("claude-") { Ok("claude") }
    else if model=="gemini" || model.starts_with("gemini-") { Ok("gemini") }
    else { Err("Unsupported model family".into()) }
}
pub fn default_engine(template_id: &str, beta: bool) -> &'static str { engine_for_model(default_model(template_id,beta)).unwrap() }
pub fn default_model(template_id: &str, beta: bool) -> &'static str {
    if !beta && template_id == "tpl_grok_cos" { "" } else { DEEPSEEK_MODEL }
}
pub fn validate_engine(engine: &str, beta: bool) -> Result<(), String> {
    if (beta && engine=="opencode2") || (!beta && matches!(engine,"codex"|"opencode2"|"claude"|"gemini")) { Ok(()) }
    else { Err("Unsupported CLI runner for this edition".into()) }
}
pub fn validate_model(model: &str, beta: bool) -> Result<(), String> {
    if beta && model!=DEEPSEEK_MODEL {return Err("Public edition supports the metered DeepSeek route only".into());}
    engine_for_model(model).map(|_|())
}
pub fn validate_binding(engine:&str,model:&str,beta:bool)->Result<(),String>{
    validate_engine(engine,beta)?;validate_model(model,beta)?;
    if engine_for_model(model)?!=engine {return Err("Model must use its corresponding CLI runner".into());}Ok(())
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
    #[test]fn model_runner_bindings_are_exact_and_beta_never_loads_subscriptions(){
        for (model,engine) in [("","codex"),("gpt-6.1-sol","codex"),(DEEPSEEK_MODEL,"opencode2"),("claude","claude"),("claude-sonnet-4-6","claude"),("gemini","gemini")] {
            assert!(validate_binding(engine,model,false).is_ok());
            assert_eq!(validate_binding(engine,model,true).is_ok(),model==DEEPSEEK_MODEL);
            for other in ["codex","opencode2","claude","gemini"] {if other!=engine {assert!(validate_binding(other,model,false).is_err());}}
        }
    }
    #[test]
    fn editions_have_distinct_resources_and_bindings() {
        assert_ne!(
            data_dir("data".into(), true),
            data_dir("data".into(), false)
        );
        assert_eq!(default_engine("tpl_grok_cos", false), "codex");
        assert_eq!(default_engine("tpl_grok_cos", true), "opencode2");
        assert_eq!(default_engine("tpl_benjamin_code", false), "opencode2");
        for engine in ["antigravity", "opencode", "gpt"] {
            assert!(validate_engine(engine, false).is_err());
        }
        assert!(validate_model("", true).is_err());
        assert_eq!(default_model("tpl_grok_cos", true), DEEPSEEK_MODEL);
    }
}

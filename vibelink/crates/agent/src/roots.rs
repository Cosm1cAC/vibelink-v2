//! 工作区边界：手机 / Cindy 经 hub 读写 PC 文件时，只允许落在白名单根目录里，并且拒绝凭据类文件。
//!
//! 根目录来源（按顺序）：
//! 1. 环境变量 `VIBELINK_WORKSPACE_ROOTS`（`;` 分隔）
//! 2. `%USERPROFILE%\Desktop\项目`（存在时）
//! 3. 兜底：用户主目录下的 `Desktop\项目\VibeLink` 或主目录本身
use std::path::{Component, Path, PathBuf};

fn home() -> PathBuf {
    std::env::var_os("USERPROFILE")
        .or_else(|| std::env::var_os("HOME"))
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
}

/// 规范化路径：能 canonicalize 就用（解析 `..` 和符号链接），不能就按组件折叠 `..`。
pub fn normalize(p: &Path) -> PathBuf {
    if let Ok(c) = std::fs::canonicalize(p) {
        return strip_verbatim(c);
    }
    let mut out = PathBuf::new();
    for c in p.components() {
        match c {
            Component::ParentDir => {
                out.pop();
            }
            Component::CurDir => {}
            other => out.push(other.as_os_str()),
        }
    }
    out
}

/// Windows 的 canonicalize 会返回 `\\?\C:\...`，比较前去掉。
fn strip_verbatim(p: PathBuf) -> PathBuf {
    let s = p.to_string_lossy();
    if let Some(rest) = s.strip_prefix(r"\\?\") {
        if !rest.starts_with("UNC\\") {
            return PathBuf::from(rest.to_string());
        }
    }
    p
}

fn key(p: &Path) -> String {
    let s = normalize(p).to_string_lossy().replace('\\', "/");
    let s = s.trim_end_matches('/').to_string();
    if cfg!(windows) { s.to_lowercase() } else { s }
}

pub fn roots() -> Vec<PathBuf> {
    roots_from(std::env::var("VIBELINK_WORKSPACE_ROOTS").ok().as_deref(), &home())
}

pub fn roots_from(env: Option<&str>, home: &Path) -> Vec<PathBuf> {
    if let Some(list) = env {
        let v: Vec<PathBuf> = list
            .split(';')
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(PathBuf::from)
            .collect();
        if !v.is_empty() {
            return v;
        }
    }
    let mut found = Vec::new();
    if home.is_dir() {
        let d_ai = PathBuf::from(r"D:\AI");
        if d_ai.is_dir() {
            found.push(d_ai);
        }
    }
    let projects = home.join("Desktop").join("项目");
    if projects.is_dir() {
        found.push(projects);
    } else {
        let stack = home.join("Desktop").join("项目").join("VibeLink");
        if stack.is_dir() {
            found.push(stack);
        }
    }
    if !found.is_empty() {
        return found;
    }
    vec![home.to_path_buf()]
}

pub fn within(path: &Path, roots: &[PathBuf]) -> bool {
    let k = key(path);
    roots.iter().any(|r| {
        let rk = key(r);
        !rk.is_empty() && (k == rk || k.starts_with(&format!("{rk}/")))
    })
}

/// 凭据类文件 / 目录：即使在根目录里也不给读。
pub fn is_secret(path: &Path) -> bool {
    path.components().any(|c| {
        let n = c.as_os_str().to_string_lossy().to_lowercase();
        matches!(
            n.as_str(),
            ".ssh" | ".gnupg" | ".aws" | ".azure" | ".kube" | ".docker" | "token.txt" | "tokens.json"
                | "owner-secrets.json" | "legacy-gateway.sha256" | ".git-credentials" | ".netrc" | ".npmrc"
                | ".pypirc" | "credentials.json" | "opencode.json"
        ) || n == ".env"
            || n.starts_with(".env.")
            || n.starts_with("id_rsa")
            || n.starts_with("id_ed25519")
            || n.starts_with("id_ecdsa")
            || n.ends_with(".pem")
            || n.ends_with(".key")
            || n.ends_with(".p12")
            || n.ends_with(".pfx")
            || n.ends_with(".kdbx")
    })
}

/// 检查一个要读的文件：必须在根目录里、不是凭据。
pub fn check_read(path: &Path) -> Result<PathBuf, String> {
    let roots = roots();
    let n = normalize(path);
    if !within(&n, &roots) {
        return Err(format!("path outside allowed workspace roots: {}", path.display()));
    }
    if is_secret(&n) {
        return Err(format!("refusing to read credential-like file: {}", path.display()));
    }
    Ok(n)
}

/// 工作目录：给了且在根目录里就用，否则退回第一个根目录。
pub fn clamp_cwd(cwd: &str, fallback: &str) -> String {
    let roots = roots();
    for cand in [cwd.trim(), fallback.trim()] {
        if !cand.is_empty() {
            let p = Path::new(cand);
            if p.is_dir() && within(p, &roots) {
                return normalize(p).to_string_lossy().to_string();
            }
        }
    }
    roots
        .iter()
        .find(|r| r.is_dir())
        .map(|r| normalize(r).to_string_lossy().to_string())
        .unwrap_or_else(|| home().to_string_lossy().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn within_and_traversal() {
        let base = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        let roots = vec![base.clone()];
        assert!(within(&base.join("src").join("main.rs"), &roots));
        assert!(within(&base, &roots));
        assert!(!within(&base.join("..").join("hub"), &roots), "`..` must not escape");
        let sibling = PathBuf::from(format!("{}-evil", base.display()));
        assert!(!within(&sibling, &roots), "prefix match must respect separators");
    }

    #[test]
    fn secrets_are_refused() {
        assert!(is_secret(Path::new("C:/u/.ssh/config")));
        assert!(is_secret(Path::new("/p/.env")));
        assert!(is_secret(Path::new("/p/.env.local")));
        assert!(is_secret(Path::new("/p/data/token.txt")));
        assert!(is_secret(Path::new("/p/server.pem")));
        assert!(!is_secret(Path::new("/p/src/env.rs")));
        assert!(!is_secret(Path::new("/p/README.md")));
    }

    #[test]
    fn env_roots_win() {
        let r = roots_from(Some("C:/a; D:/b ;"), Path::new("C:/home"));
        assert_eq!(r, vec![PathBuf::from("C:/a"), PathBuf::from("D:/b")]);
        let r = roots_from(None, Path::new("Z:/definitely-missing-home"));
        assert_eq!(r, vec![PathBuf::from("Z:/definitely-missing-home")]);
    }
}

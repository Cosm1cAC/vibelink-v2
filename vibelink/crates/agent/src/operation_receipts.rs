//! Durable, scope-bound acceptance receipts for model/tool operations.
//!
//! An intent is immutable and is flushed before the executor is entered. A
//! receipt means the asynchronous create/prompt RPC was accepted, not that its
//! model turn finished. An intent without a receipt is deliberately never replayed.
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    fs::{self, File, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    sync::{Arc, Mutex, OnceLock, Weak},
};

#[path = "operation_sha256.rs"]
mod digest;

const VERSION: u32 = 1;
const UNCERTAIN: &str = "operation_uncertain: durable intent exists without a confirmed acceptance receipt; automatic replay is unsafe";

#[derive(Serialize, Deserialize)]
struct Intent {
    version: u32,
    operation_id: String,
    kind: String,
    fingerprint: String,
    created_ms: u64,
}

#[derive(Serialize, Deserialize)]
struct Receipt {
    version: u32,
    fingerprint: String,
    payload: Value,
}

type OperationLocks = Mutex<HashMap<PathBuf, Weak<Mutex<()>>>>;
static LOCKS: OnceLock<OperationLocks> = OnceLock::new();

fn operation_lock(path: &Path) -> Result<Arc<Mutex<()>>, String> {
    let mut locks = LOCKS
        .get_or_init(Mutex::default)
        .lock()
        .map_err(|_| "operation_store_unavailable: operation lock poisoned")?;
    locks.retain(|_, value| value.strong_count() != 0);
    if let Some(lock) = locks.get(path).and_then(Weak::upgrade) {
        return Ok(lock);
    }
    let lock = Arc::new(Mutex::new(()));
    locks.insert(path.to_owned(), Arc::downgrade(&lock));
    Ok(lock)
}

fn hex_hash(bytes: &[u8]) -> String {
    digest::sha256(bytes)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

fn operation_dir(data: &Path, scope: &str, id: &str) -> PathBuf {
    data.join("operation_receipts")
        .join(hex_hash(scope.as_bytes()))
        .join(hex_hash(id.as_bytes()))
}

/// Normalize object order even if serde_json's preserve_order feature is enabled.
/// Only transport capabilities are omitted; instructions, files, session, model,
/// bot ownership and tool permissions remain part of the semantic identity.
fn canonical_payload(value: &Value, runtime: bool, root: bool) -> Value {
    match value {
        Value::Object(map) => {
            let mut keys: Vec<_> = map.keys().collect();
            keys.sort();
            Value::Object(
                keys.into_iter()
                    .filter_map(|key| {
                        if (root && key == "operationId")
                            || (runtime && matches!(key.as_str(), "token" | "gateway"))
                        {
                            return None;
                        }
                        Some((
                            key.clone(),
                            canonical_payload(&map[key], root && key == "codexRuntime", false),
                        ))
                    })
                    .collect(),
            )
        }
        Value::Array(items) => Value::Array(
            items
                .iter()
                .map(|item| canonical_payload(item, false, false))
                .collect(),
        ),
        other => other.clone(),
    }
}

fn fingerprint(kind: &str, payload: &Value) -> Result<String, String> {
    let semantic = json!({"kind": kind, "payload": canonical_payload(payload, false, true)});
    let bytes = serde_json::to_vec(&semantic)
        .map_err(|_| "operation_store_unavailable: cannot fingerprint request")?;
    Ok(hex_hash(&bytes))
}

fn operation_id(payload: &Value) -> Result<Option<&str>, String> {
    let Some(id) = payload.get("operationId") else {
        return Ok(None);
    };
    id.as_str()
        .filter(|id| {
            !id.is_empty()
                && id.len() <= 256
                && id
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-'))
        })
        .map(Some)
        .ok_or_else(|| {
            "invalid_operation_id: expected 1..256 ASCII letters, digits, '_' or '-'".into()
        })
}

fn new_private_file(path: &Path) -> std::io::Result<File> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(path)
}

fn write_synced(file: &mut File, value: &impl Serialize) -> std::io::Result<()> {
    serde_json::to_writer(&mut *file, value)?;
    file.write_all(b"\n")?;
    file.sync_all()
}

fn sync_dir(path: &Path) -> std::io::Result<()> {
    // Unix requires directory fsync for a durable create/rename. On Windows
    // sync_all on each file uses FlushFileBuffers (including file metadata);
    // directory handles are not supported by the portable std implementation.
    #[cfg(unix)]
    {
        File::open(path)?.sync_all()
    }
    #[cfg(not(unix))]
    {
        let _ = path;
        Ok(())
    }
}

fn read_existing(dir: &Path, id: &str, kind: &str, expected: &str) -> Result<Value, String> {
    let intent: Intent = fs::read(dir.join("intent.json"))
        .ok()
        .and_then(|bytes| serde_json::from_slice(&bytes).ok())
        .ok_or_else(|| UNCERTAIN.to_string())?;
    if intent.version != VERSION
        || intent.operation_id != id
        || intent.kind != kind
        || intent.fingerprint != expected
    {
        return Err(
            "operation_content_mismatch: operationId was already used with different content"
                .into(),
        );
    }
    let receipt: Receipt = fs::read(dir.join("receipt.json"))
        .ok()
        .and_then(|bytes| serde_json::from_slice(&bytes).ok())
        .ok_or_else(|| UNCERTAIN.to_string())?;
    if receipt.version != VERSION || receipt.fingerprint != expected {
        return Err(UNCERTAIN.into());
    }
    Ok(receipt.payload)
}

pub fn execute(
    data: &Path,
    scope: &str,
    kind: &str,
    payload: &Value,
    action: impl FnOnce() -> Result<Value, String>,
) -> Result<Value, String> {
    let Some(id) = operation_id(payload)? else {
        return action();
    };
    let expected = fingerprint(kind, payload)?;
    let dir = operation_dir(data, scope, id);
    fs::create_dir_all(&dir)
        .map_err(|_| "operation_store_unavailable: cannot create operation directory")?;
    let dir = fs::canonicalize(dir)
        .map_err(|_| "operation_store_unavailable: cannot resolve operation directory")?;
    let mutex = operation_lock(&dir)?;
    let _guard = mutex.lock().map_err(|_| UNCERTAIN)?;

    let intent_path = dir.join("intent.json");
    // A damaged/deleted intent is not permission to redispatch an operation
    // whose result (even an uncommitted result) still exists.
    if !intent_path.exists()
        && (dir.join("receipt.json").exists() || dir.join("receipt.pending").exists())
    {
        return Err(UNCERTAIN.into());
    }
    let mut file = match new_private_file(&intent_path) {
        Ok(file) => file,
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
            return read_existing(&dir, id, kind, &expected);
        }
        Err(_) => return Err("operation_store_unavailable: cannot claim operation intent".into()),
    };
    let intent = Intent {
        version: VERSION,
        operation_id: id.into(),
        kind: kind.into(),
        fingerprint: expected.clone(),
        created_ms: vibelink_protocol::now_ms(),
    };
    write_synced(&mut file, &intent).map_err(|_| UNCERTAIN)?;
    // Persist each newly created namespace before any model or tool can start.
    for directory in dir.ancestors().take(4) {
        sync_dir(directory).map_err(|_| UNCERTAIN)?;
    }
    drop(file);

    let result = action().map_err(|_| UNCERTAIN)?;
    let receipt = Receipt {
        version: VERSION,
        fingerprint: expected,
        payload: result.clone(),
    };
    let pending = dir.join("receipt.pending");
    let mut file = new_private_file(&pending).map_err(|_| UNCERTAIN)?;
    write_synced(&mut file, &receipt).map_err(|_| UNCERTAIN)?;
    drop(file);
    fs::rename(pending, dir.join("receipt.json")).map_err(|_| UNCERTAIN)?;
    sync_dir(&dir).map_err(|_| UNCERTAIN)?;
    Ok(result)
}

/// Reconcile a lost acceptance response without submitting anything to Codex.
/// `missing` is a point-in-time observation, not a cancellation fence for a
/// still-queued future request. An uncertain outcome never authorizes replay.
pub fn lookup(data: &Path, scope: &str, payload: &Value) -> Result<Value, String> {
    let id = operation_id(payload)?.ok_or("invalid_operation_id: operationId is required")?;
    let dir = operation_dir(data, scope, id);
    match fs::metadata(&dir) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Ok(json!({"status":"missing"}));
        }
        Err(_) => return Ok(json!({"status":"uncertain"})),
        Ok(_) => {}
    }
    let intent: Option<Intent> = fs::read(dir.join("intent.json"))
        .ok()
        .and_then(|bytes| serde_json::from_slice(&bytes).ok());
    if let Some(intent) = intent {
        if let Ok(result) = read_existing(&dir, id, &intent.kind, &intent.fingerprint) {
            return Ok(json!({"status":"accepted", "result":result}));
        }
    }
    Ok(json!({"status":"uncertain"}))
}

#[cfg(test)]
mod tests;

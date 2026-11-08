//! Hub 鉴权：按设备签发的 token + 作用域 + 一次性配对码。
//!
//! - `data/token.txt`：管理员 token（明文，只给本机 GUI / CLI 用）。
//! - `data/tokens.json`：设备 token，只存 SHA-256，明文只在签发时返回一次。
//! - 作用域：`admin` ⊇ `client`（手机 / Cindy / 网关）；`agent` 只能走 agent 通道。
//! - 配对：管理员 `POST /api/pair/start` 拿一个二维码链接（128 位一次性密钥，10 分钟），
//!   手机扫码后 `POST /api/pair {code: 密钥, name}` 换一个 client token。
//!
//! 注意：Cloudflare 隧道（cloudflared）从 127.0.0.1 连进来，**不能信任回环地址**。
use serde::{Deserialize, Serialize};
use std::{fs, path::PathBuf, sync::Mutex};
use vibelink_protocol::now_ms;

// ---------------- sha256（避免引入新依赖；带标准向量测试） ----------------

const K: [u32; 64] = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];

pub fn sha256(data: &[u8]) -> [u8; 32] {
    let mut h: [u32; 8] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    ];
    let mut msg = data.to_vec();
    let bit_len = (data.len() as u64).wrapping_mul(8);
    msg.push(0x80);
    while msg.len() % 64 != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&bit_len.to_be_bytes());
    for chunk in msg.chunks(64) {
        let mut w = [0u32; 64];
        for i in 0..16 {
            w[i] = u32::from_be_bytes([chunk[i * 4], chunk[i * 4 + 1], chunk[i * 4 + 2], chunk[i * 4 + 3]]);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16].wrapping_add(s0).wrapping_add(w[i - 7]).wrapping_add(s1);
        }
        let mut a = h;
        for i in 0..64 {
            let s1 = a[4].rotate_right(6) ^ a[4].rotate_right(11) ^ a[4].rotate_right(25);
            let ch = (a[4] & a[5]) ^ (!a[4] & a[6]);
            let t1 = a[7].wrapping_add(s1).wrapping_add(ch).wrapping_add(K[i]).wrapping_add(w[i]);
            let s0 = a[0].rotate_right(2) ^ a[0].rotate_right(13) ^ a[0].rotate_right(22);
            let maj = (a[0] & a[1]) ^ (a[0] & a[2]) ^ (a[1] & a[2]);
            let t2 = s0.wrapping_add(maj);
            a[7] = a[6];
            a[6] = a[5];
            a[5] = a[4];
            a[4] = a[3].wrapping_add(t1);
            a[3] = a[2];
            a[2] = a[1];
            a[1] = a[0];
            a[0] = t1.wrapping_add(t2);
        }
        for i in 0..8 {
            h[i] = h[i].wrapping_add(a[i]);
        }
    }
    let mut out = [0u8; 32];
    for i in 0..8 {
        out[i * 4..i * 4 + 4].copy_from_slice(&h[i].to_be_bytes());
    }
    out
}

pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

pub fn hash_token(token: &str) -> String {
    hex(&sha256(token.trim().as_bytes()))
}

// ---------------- 系统随机数 ----------------

#[cfg(windows)]
fn os_random(buf: &mut [u8]) -> bool {
    #[link(name = "bcrypt")]
    unsafe extern "system" {
        fn BCryptGenRandom(alg: *mut core::ffi::c_void, buf: *mut u8, len: u32, flags: u32) -> i32;
    }
    const BCRYPT_USE_SYSTEM_PREFERRED_RNG: u32 = 0x0000_0002;
    unsafe { BCryptGenRandom(core::ptr::null_mut(), buf.as_mut_ptr(), buf.len() as u32, BCRYPT_USE_SYSTEM_PREFERRED_RNG) == 0 }
}

#[cfg(not(windows))]
fn os_random(buf: &mut [u8]) -> bool {
    use std::io::Read;
    fs::File::open("/dev/urandom").and_then(|mut f| f.read_exact(buf)).is_ok()
}

/// 256 位随机 token（64 个 hex 字符）。拿不到系统随机数就直接 panic，绝不退回可预测的值。
pub fn random_token() -> String {
    let mut buf = [0u8; 32];
    assert!(os_random(&mut buf), "system RNG unavailable");
    hex(&buf)
}

// ---------------- 作用域 ----------------

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Scope {
    Admin,
    Client,
    Agent,
}

impl Scope {
    pub fn parse(s: &str) -> Option<Scope> {
        match s.trim().to_ascii_lowercase().as_str() {
            "admin" => Some(Scope::Admin),
            "client" => Some(Scope::Client),
            "agent" => Some(Scope::Agent),
            _ => None,
        }
    }
    pub fn as_str(&self) -> &'static str {
        match self {
            Scope::Admin => "admin",
            Scope::Client => "client",
            Scope::Agent => "agent",
        }
    }
}

/// 某个请求需要的权限。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Need {
    Public,
    AnyAuth,
    Agent,
    Client,
    Admin,
}

/// Cindy 中继里只允许本机调用的接口：经 hub / 隧道转进来一律拒绝。
pub fn is_blocked_relay_path(path: &str) -> bool {
    let p = path.to_ascii_lowercase();
    p.contains("/relay/local") || p.contains("/relay/admin/")
}

pub fn required(method: &str, path: &str) -> Need {
    let post = method.eq_ignore_ascii_case("POST");
    let get = method.eq_ignore_ascii_case("GET") || method.eq_ignore_ascii_case("HEAD");
    // 公开：健康检查、配对兑换、内测邀请码激活、安装包与 agent 二进制（都不含秘密）、Cindy 中继（自带设备证书校验）
    if path == "/api/health"
        || (post && matches!(path,"/api/auth/beta"|"/api/auth/qr/start"|"/api/auth/qr/scan"|"/api/auth/qr/poll"|"/api/auth/qr/ack"))
        || (post && (path == "/api/pair" || path == "/api/pair/scan" || path == "/api/beta/activate"))
        || (get && (path == "/apk" || path.starts_with("/apk/") || path == "/api/apk/latest"))
        || (get && (path == "/api/agent/latest" || path.starts_with("/agent/")))
        || path == "/api/device-link"
        || path.starts_with("/api/device-link/")
    {
        return Need::Public;
    }
    // agent 通道
    if (post && path == "/api/ingest")
        || (get && path == "/api/commands")
        || (post && path.starts_with("/api/commands/") && (path.ends_with("/claim") || path == "/api/commands/result"))
        || (get && path == "/api/agent/session-requests")
        || (post && path == "/api/agent/session-result")
    {
        return Need::Agent;
    }
    if path.starts_with("/api/usage/") { return Need::Admin; }
    // 客户端与受托 Agent 均可调用的 Fleet / Bot 上下文 / 审批请求接口
    if (get && path.starts_with("/api/bots/"))
        || (post && (path == "/api/fleet/action" || path == "/api/approvals/request"))
    {
        return Need::AnyAuth;
    }
    // 只给管理员
    if path == "/api/tokens"
        || path.starts_with("/api/tokens/")
        || path == "/api/beta/tenants"
        || (post && path == "/api/pair/start")
        || path.starts_with("/api/pair/status/")
        || (post && path == "/api/policy")
        || (post && path.starts_with("/api/config/"))
        || (post && path == "/api/commands")
    {
        return Need::Admin;
    }
    Need::Client
}

pub fn allows(scope: Scope, need: Need) -> bool {
    match need {
        Need::Public => true,
        Need::AnyAuth => matches!(scope, Scope::Admin | Scope::Client | Scope::Agent),
        Need::Admin => scope == Scope::Admin,
        Need::Client => matches!(scope, Scope::Admin | Scope::Client),
        // GET /api/commands 也给 client 看（手机看命令执行结果），所以 client 也放行 agent 通道里的只读部分由路由决定；
        // 这里保持严格：agent 通道只给 admin 和 agent。
        Need::Agent => matches!(scope, Scope::Admin | Scope::Agent),
    }
}

// ---------------- 存储 ----------------

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TokenRecord {
    pub id: String,
    pub name: String,
    pub scope: Scope,
    pub hash: String,
    pub created_at: u64,
    #[serde(default)]
    pub last_used_at: u64,
    #[serde(default)]
    pub revoked: bool,
    #[serde(default)]
    pub principal_id: Option<String>,
    #[serde(default)]
    pub principal_label: Option<String>,
    #[serde(default)]
    pub expires_at: u64,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TokenView {
    pub id: String,
    pub name: String,
    pub scope: Scope,
    pub created_at: u64,
    pub last_used_at: u64,
    pub revoked: bool,
}

#[derive(Clone, Debug)]
pub struct AuthCtx {
    pub scope: Scope,
    pub token_id: String,
    pub name: String,
    pub principal_id: Option<String>,
    pub principal_label: Option<String>,
    pub expires_at: u64,
    fingerprint: String,
}

struct PairCode {
    id: String,
    code_hash: String,
    name_hint: String,
    expires_at: u64,
    /// 手机扫到码、还没点「连接」时上报的设备名（桌面端显示「请在手机上确认」）
    scanned_by: Option<String>,
}

struct PairDone {
    id: String,
    name: String,
    at: u64,
}

#[derive(Default, Serialize, Deserialize)]
struct TokenFile {
    tokens: Vec<TokenRecord>,
}

pub struct Auth {
    dir: PathBuf,
    admin_hash: Mutex<String>,
    inner: Mutex<Inner>,
    issuer: String,
}

/// 过渡期：旧 token 的哈希（`data/legacy-gateway.sha256`）。只在 CONNECT 网关（局域网 / tailnet，
/// 不经公网隧道）上放行，HTTP API 一律不认。手机重新配对后删掉这个文件即可。
pub const LEGACY_GATEWAY_FILE: &str = "legacy-gateway.sha256";

#[derive(Default)]
struct Inner {
    tokens: Vec<TokenRecord>,
    pair_codes: Vec<PairCode>,
    /// 最近完成的配对（给 GUI 显示「已配对：xx」）
    pair_done: Vec<PairDone>,
    /// 最近的兑换失败时间。密钥是 128 位的，猜不中；限流只是挡住刷日志 / 刷 CPU。
    failures: Vec<u64>,
    dirty_since: u64,
}

pub const PAIR_TTL_MS: u64 = 10 * 60_000;
const FAIL_WINDOW_MS: u64 = 10 * 60_000;
const FAIL_LIMIT: usize = 200;

impl Auth {
    /// 读 `token.txt`（没有就生成随机管理员 token）和 `tokens.json`。
    pub fn load(dir: PathBuf) -> (Auth, String) {
        fs::create_dir_all(&dir).expect("create auth directory");
        let issuer_file=dir.join("auth-issuer.txt");
        let issuer=match fs::read_to_string(&issuer_file) {
            Ok(value)=>value.trim().to_string(),
            Err(error) if error.kind()==std::io::ErrorKind::NotFound=>{
                use std::io::Write;
                let value=format!("hub_{}",&random_token()[..24]);
                let mut file=fs::OpenOptions::new().write(true).create_new(true).open(&issuer_file).expect("create persistent login issuer");
                file.write_all(value.as_bytes()).and_then(|_|file.sync_all()).expect("persist login issuer");value
            },
            Err(_)=>panic!("cannot read persistent login issuer"),
        };
        assert!(issuer.len()==28&&issuer.starts_with("hub_")&&issuer[4..].bytes().all(|b|b.is_ascii_hexdigit()),"invalid persistent login issuer");
        let token_file = dir.join("token.txt");
        let admin = match fs::read_to_string(&token_file) {
            Ok(t) if !t.trim().is_empty() => t.trim().to_string(),
            _ => {
                let t = random_token();
                let _ = fs::write(&token_file, &t);
                t
            }
        };
        let tokens = fs::read_to_string(dir.join("tokens.json"))
            .ok()
            .and_then(|s| serde_json::from_str::<TokenFile>(&s).ok())
            .map(|f| f.tokens)
            .unwrap_or_default();
        let auth = Auth {
            dir,
            admin_hash: Mutex::new(hash_token(&admin)),
            inner: Mutex::new(Inner { tokens, ..Default::default() }),
            issuer,
        };
        (auth, admin)
    }
    pub fn issuer(&self)->&str {&self.issuer}

    fn persist(&self, inner: &Inner) {
        let file = TokenFile { tokens: inner.tokens.clone() };
        if let Ok(s) = serde_json::to_vec_pretty(&file) {
            crate::write_atomic(&self.dir.join("tokens.json"), &s);
        }
    }

    /// CONNECT 网关：admin / client token，或过渡期的旧 token。
    pub fn gateway_ok(&self, presented: &str) -> bool {
        if self.verify(presented).map(|c| allows(c.scope, Need::Client)).unwrap_or(false) {
            return true;
        }
        let legacy = fs::read_to_string(self.dir.join(LEGACY_GATEWAY_FILE)).unwrap_or_default();
        let legacy = legacy.trim();
        !legacy.is_empty() && !presented.trim().is_empty() && legacy == hash_token(presented)
    }

    pub fn verify(&self, presented: &str) -> Option<AuthCtx> {
        let presented = presented.trim();
        if presented.is_empty() {
            return None;
        }
        let h = hash_token(presented);
        if *self.admin_hash.lock().unwrap() == h {
            return Some(AuthCtx { scope: Scope::Admin, token_id: "admin".into(), name: "admin".into(), principal_id:Some("owner".into()), principal_label:Some("本机工作区".into()), expires_at:0,fingerprint:h });
        }
        let mut inner = self.inner.lock().unwrap();
        let now = now_ms();
        let found = inner.tokens.iter_mut().find(|t| !t.revoked && t.hash == h && (t.expires_at==0 || t.expires_at>now)).map(|t| {
            // last_used 只记到分钟级，避免每个请求都写盘
            let stale = now.saturating_sub(t.last_used_at) > 60_000;
            if stale {
                t.last_used_at = now;
            }
            (AuthCtx { scope: t.scope, token_id: t.id.clone(), name: t.name.clone(),principal_id:t.principal_id.clone(),principal_label:t.principal_label.clone(),expires_at:t.expires_at,fingerprint:h.clone() }, stale)
        });
        let (ctx, stale) = found?;
        if stale {
            if inner.dirty_since == 0 {
                inner.dirty_since = now;
            }
            if now.saturating_sub(inner.dirty_since) > 5 * 60_000 {
                inner.dirty_since = 0;
                self.persist(&inner);
            }
        }
        Some(ctx)
    }

    pub fn issue(&self, name: &str, scope: Scope) -> (TokenView, String) {
        let plain = random_token();
        let rec = TokenRecord {
            id: format!("tk_{}", &random_token()[..12]),
            name: sanitize_name(name),
            scope,
            hash: hash_token(&plain),
            created_at: now_ms(),
            last_used_at: 0,
            revoked: false,
            principal_id:None,principal_label:None,expires_at:0,
        };
        let view = to_view(&rec);
        let mut inner = self.inner.lock().unwrap();
        inner.tokens.push(rec);
        self.persist(&inner);
        (view, plain)
    }

    pub fn ensure_fixed_token(&self, id: &str, name: &str, scope: Scope, plain: &str) {
        let h = hash_token(plain);
        let mut inner = self.inner.lock().unwrap();
        if let Some(existing) = inner.tokens.iter_mut().find(|t| t.id == id) {
            if existing.hash != h || existing.name != name || existing.scope != scope || existing.revoked {
                existing.hash = h;
                existing.name = sanitize_name(name);
                existing.scope = scope;
                existing.revoked = false;
                self.persist(&inner);
            }
            return;
        }
        inner.tokens.push(TokenRecord {
            id: id.to_string(),
            name: sanitize_name(name),
            scope,
            hash: h,
            created_at: now_ms(),
            last_used_at: 0,
            revoked: false,
            principal_id:None,principal_label:None,expires_at:0,
        });
        self.persist(&inner);
    }

    pub fn revoke(&self, id: &str) -> bool {
        let mut inner = self.inner.lock().unwrap();
        let mut hit = false;
        for t in inner.tokens.iter_mut() {
            if t.id == id && !t.revoked {
                t.revoked = true;
                hit = true;
            }
        }
        if hit {
            self.persist(&inner);
        }
        hit
    }

    pub fn list(&self) -> Vec<TokenView> {
        self.inner.lock().unwrap().tokens.iter().map(to_view).collect()
    }

    /// 换一个新的管理员 token，写回 token.txt，返回明文。
    pub fn rotate_admin(&self) -> String {
        let _sessions=self.inner.lock().unwrap();
        let t = random_token();
        let _ = fs::write(self.dir.join("token.txt"), &t);
        *self.admin_hash.lock().unwrap() = hash_token(&t);
        t
    }

    /// 开一次配对：返回 (配对 id, 一次性密钥, 过期时间)。密钥放进二维码，不需要人手输入，
    /// 所以用 128 位随机数而不是 6 位数字（猜不中，也就不需要靠「失败太多作废」来防爆破）。
    pub fn start_pairing(&self, name_hint: &str) -> (String, String, u64) {
        let secret = random_token()[..32].to_string();
        let id = format!("pr_{}", &random_token()[..12]);
        let now = now_ms();
        let expires_at = now + PAIR_TTL_MS;
        let mut inner = self.inner.lock().unwrap();
        inner.pair_codes.retain(|c| c.expires_at > now);
        inner.pair_done.retain(|d| now.saturating_sub(d.at) < PAIR_TTL_MS);
        // 同时最多 3 个有效码
        while inner.pair_codes.len() >= 3 {
            inner.pair_codes.remove(0);
        }
        inner.pair_codes.push(PairCode {
            id: id.clone(),
            code_hash: hash_token(&secret),
            name_hint: sanitize_name(name_hint),
            expires_at,
            scanned_by: None,
        });
        (id, secret, expires_at)
    }

    /// 手机扫到码后先报到一次（还没确认）：校验密钥但不消耗它，记下设备名。
    /// 返回密钥剩余有效期，手机据此显示确认页。
    pub fn mark_scanned(&self, code: &str, name: &str) -> Result<u64, &'static str> {
        let now = now_ms();
        let mut inner = self.inner.lock().unwrap();
        inner.failures.retain(|t| now.saturating_sub(*t) < FAIL_WINDOW_MS);
        if inner.failures.len() >= FAIL_LIMIT {
            return Err("too many failed attempts, try again later");
        }
        inner.pair_codes.retain(|c| c.expires_at > now);
        let h = hash_token(code.trim());
        let Some(c) = inner.pair_codes.iter_mut().find(|c| c.code_hash == h) else {
            inner.failures.push(now);
            return Err("invalid or expired pairing code");
        };
        c.scanned_by = Some(sanitize_name(name));
        Ok(c.expires_at)
    }

    /// 配对进行到哪了：pending / scanned（手机已扫码待确认，带设备名）/ paired（带设备名）/ expired。
    /// 给出码的那一方（GUI）轮询用。
    pub fn pairing_status(&self, id: &str) -> (&'static str, String) {
        let now = now_ms();
        let inner = self.inner.lock().unwrap();
        if let Some(d) = inner.pair_done.iter().find(|d| d.id == id) {
            return ("paired", d.name.clone());
        }
        if let Some(c) = inner.pair_codes.iter().find(|c| c.id == id && c.expires_at > now) {
            return match &c.scanned_by {
                Some(n) => ("scanned", n.clone()),
                None => ("pending", String::new()),
            };
        }
        ("expired", String::new())
    }

    /// 用二维码里的一次性密钥换一个 client token。错误时返回给用户看的原因。
    pub fn redeem(&self, code: &str, name: &str) -> Result<(TokenView, String), &'static str> {
        let now = now_ms();
        let (pair_id, name_hint);
        {
            let mut inner = self.inner.lock().unwrap();
            inner.failures.retain(|t| now.saturating_sub(*t) < FAIL_WINDOW_MS);
            if inner.failures.len() >= FAIL_LIMIT {
                return Err("too many failed attempts, try again later");
            }
            inner.pair_codes.retain(|c| c.expires_at > now);
            let h = hash_token(code.trim());
            let Some(pos) = inner.pair_codes.iter().position(|c| c.code_hash == h) else {
                inner.failures.push(now);
                return Err("invalid or expired pairing code");
            };
            let c = inner.pair_codes.remove(pos);
            pair_id = c.id;
            name_hint = c.name_hint;
        }
        let name = if name.trim().is_empty() { name_hint } else { name.to_string() };
        let issued = self.issue_session(&name,"owner","本机工作区")
            .map_err(|_|"无法保存登录会话，请重试")?;
        self.inner.lock().unwrap().pair_done.push(PairDone { id: pair_id, name: issued.0.name.clone(), at: now });
        Ok(issued)
    }

    /// New device credentials share a server-selected principal, never a caller-supplied user ID.
    pub fn issue_session(&self,name:&str,principal:&str,label:&str)->Result<(TokenView,String),String> {
        let mut inner=self.inner.lock().unwrap();self.mint_session(&mut inner,name,principal,label)
    }
    pub fn issue_derived_session(&self,source:&AuthCtx,name:&str,label:&str)->Result<(TokenView,String),String> {
        let mut inner=self.inner.lock().unwrap();
        let active=if source.scope==Scope::Admin {*self.admin_hash.lock().unwrap()==source.fingerprint}
            else {inner.tokens.iter().any(|t|t.id==source.token_id&&t.hash==source.fingerprint&&!t.revoked&&(t.expires_at==0||t.expires_at>now_ms()))};
        if !active||!allows(source.scope,Need::Client){return Err("登录凭据已失效".into());}
        let principal=crate::model_gateway::identity(source).user_id;
        self.mint_session(&mut inner,name,&principal,label)
    }
    fn mint_session(&self,inner:&mut Inner,name:&str,principal:&str,label:&str)->Result<(TokenView,String),String> {
        let name=sanitize_name(name);
        if principal.is_empty()||principal.len()>160||!principal.bytes().all(|b|b.is_ascii_alphanumeric()||b==b'_'||b==b'-') {
            return Err("Invalid login principal".into());
        }
        // Legacy routing encodes tenancy in a trusted token name. Device labels
        // must never claim another tenant when creating a new device credential.
        if crate::tenants::extract_tenant_id(&name).is_some_and(|tenant|tenant!=principal) {
            return Err("Device name cannot select a different account".into());
        }
        if inner.tokens.iter().filter(|t|t.principal_id.as_deref()==Some(principal)&&!t.revoked&&t.expires_at>now_ms()).count()>=64 {
            return Err("此账号的登录设备过多，请先退出不使用的设备".into());
        }
        let plain=random_token();
        let rec=TokenRecord{id:format!("ss_{}",&random_token()[..24]),name,scope:Scope::Client,
            hash:hash_token(&plain),created_at:now_ms(),last_used_at:0,revoked:false,principal_id:Some(principal.into()),principal_label:Some(label.into()),expires_at:now_ms()+30*24*60*60_000};
        let view=to_view(&rec);
        let previous=inner.tokens.clone();
        inner.tokens.retain(|t|t.expires_at==0||(!t.revoked&&t.expires_at>now_ms()));
        inner.tokens.push(rec);
        if let Err(error)=self.persist_checked(inner) {inner.tokens=previous;return Err(error);}
        Ok((view,plain))
    }
    fn persist_checked(&self,inner:&Inner)->Result<(),String> {
        use std::io::Write;
        fs::create_dir_all(&self.dir).map_err(|e|e.to_string())?;
        let bytes=serde_json::to_vec(&TokenFile{tokens:inner.tokens.clone()}).map_err(|e|e.to_string())?;
        let tmp=self.dir.join("tokens.json.tmp");
        let mut file=fs::File::create(&tmp).map_err(|e|e.to_string())?;
        file.write_all(&bytes).and_then(|_|file.sync_all()).map_err(|e|e.to_string())?;drop(file);
        fs::rename(tmp,self.dir.join("tokens.json")).map_err(|e|e.to_string())
    }
    pub fn source_active(&self,ctx:&AuthCtx)->bool {
        if ctx.scope==Scope::Admin {return *self.admin_hash.lock().unwrap()==ctx.fingerprint;}
        self.inner.lock().unwrap().tokens.iter().any(|t|t.id==ctx.token_id&&t.hash==ctx.fingerprint&&!t.revoked&&(t.expires_at==0||t.expires_at>now_ms()))
    }
    pub fn logout(&self,ctx:&AuthCtx)->Result<(),String> {
        if ctx.scope!=Scope::Client {return Err("请先建立设备登录会话".into());}
        let mut inner=self.inner.lock().unwrap();
        let index=inner.tokens.iter().position(|t|t.id==ctx.token_id&&t.hash==ctx.fingerprint).ok_or("Session not found")?;
        if inner.tokens[index].expires_at==0 {return Err("旧共享凭据不能作为单设备退出，请先升级登录会话".into());}
        inner.tokens[index].revoked=true;
        if let Err(error)=self.persist_checked(&inner) {inner.tokens[index].revoked=false;return Err(error);}
        Ok(())
    }
}

/// 二维码里装的配对链接：`vibelink://pair?v=1&s=<密钥>&n=<电脑名>&u=<hub 地址>&u=...`。
/// 地址不做百分号编码（只含 `:` `/` `.`，在查询串里合法），二维码更小、更好扫。
/// 电脑名只保留字母数字和 `-_.`，手机在确认页显示「连接到 xx？」。
pub fn pair_link(secret: &str, host: &str, urls: &[String]) -> String {
    let mut s = format!("vibelink://pair?v=1&s={secret}");
    let host: String = host.chars().filter(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.')).take(40).collect();
    if !host.is_empty() {
        s.push_str("&n=");
        s.push_str(&host);
    }
    for u in urls {
        let clean: String = u.chars().filter(|c| !c.is_whitespace() && *c != '&' && *c != '#').collect();
        if !clean.is_empty() {
            s.push_str("&u=");
            s.push_str(&clean);
        }
    }
    s
}

/// 手机该试哪些地址：热点 / 局域网在前，tailnet（100.64/10）其次，172.16/12（多半是 WSL、Hyper-V 虚拟网卡）
/// 靠后，公网入口最后。去掉回环、链路本地和重复。
pub fn order_hub_urls(ips: &[std::net::Ipv4Addr], port: u16, public: &[String]) -> Vec<String> {
    let rank = |ip: &std::net::Ipv4Addr| -> Option<u8> {
        let o = ip.octets();
        if ip.is_loopback() || ip.is_link_local() || ip.is_unspecified() {
            return None;
        }
        Some(match o {
            [192, 168, 137, _] => 0, // Windows 移动热点
            [192, 168, _, _] | [10, _, _, _] => 1,
            [100, b, _, _] if (64..128).contains(&b) => 2,
            [172, b, _, _] if (16..32).contains(&b) => 3,
            _ => return None, // 公网 IP 直连不安全（明文），只走 https 公网入口
        })
    };
    let mut v: Vec<(u8, String)> = ips.iter().filter_map(|ip| rank(ip).map(|r| (r, format!("http://{ip}:{port}")))).collect();
    v.sort_by_key(|(r, _)| *r);
    let mut out: Vec<String> = Vec::new();
    for u in v.into_iter().map(|(_, u)| u).chain(public.iter().map(|p| p.trim().trim_end_matches('/').to_string())) {
        if !u.is_empty() && !out.contains(&u) {
            out.push(u);
        }
    }
    out
}

fn to_view(t: &TokenRecord) -> TokenView {
    TokenView {
        id: t.id.clone(),
        name: t.name.clone(),
        scope: t.scope,
        created_at: t.created_at,
        last_used_at: t.last_used_at,
        revoked: t.revoked,
    }
}

fn sanitize_name(s: &str) -> String {
    let t: String = s.trim().chars().filter(|c| !c.is_control()).take(40).collect();
    if t.is_empty() { "device".into() } else { t }
}

/// 从请求里取出调用方给的 token：`x-mesh-token` 头、`Authorization: Bearer`、或 `?t=`。
pub fn presented_token(headers: &axum::http::HeaderMap, query: Option<&str>) -> Option<String> {
    if let Some(v) = headers.get("x-mesh-token").and_then(|v| v.to_str().ok()) {
        if !v.trim().is_empty() {
            return Some(v.trim().to_string());
        }
    }
    if let Some((scheme, tok)) = headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.split_once(' '))
    {
        if scheme.eq_ignore_ascii_case("bearer") && !tok.trim().is_empty() {
            return Some(tok.trim().to_string());
        }
    }
    query.and_then(|q| {
        q.split('&')
            .find_map(|kv| kv.strip_prefix("t="))
            .map(|v| v.to_string())
            .filter(|v| !v.is_empty())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sha256_vectors() {
        assert_eq!(hex(&sha256(b"")), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assert_eq!(hex(&sha256(b"abc")), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assert_eq!(
            hex(&sha256(b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")),
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
        );
        let million_a = vec![b'a'; 1_000_000];
        assert_eq!(hex(&sha256(&million_a)), "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0");
    }

    #[test]
    fn random_values_look_random() {
        let a = random_token();
        let b = random_token();
        assert_eq!(a.len(), 64);
        assert_ne!(a, b);
    }

    #[test]
    fn scope_rules() {
        assert_eq!(required("GET", "/api/health"), Need::Public);
        assert_eq!(required("GET", "/apk"), Need::Public);
        assert_eq!(required("POST", "/api/pair"), Need::Public);
        assert_eq!(required("POST", "/api/pair/scan"), Need::Public);
        assert_eq!(required("POST", "/api/pair/start"), Need::Admin);
        assert_eq!(required("GET", "/api/pair/status/pr_abc"), Need::Admin);
        assert_eq!(required("GET", "/api/cindy/join-link"), Need::Client);
        assert_eq!(required("POST", "/api/ingest"), Need::Agent);
        assert_eq!(required("GET", "/api/agent/session-requests"), Need::Agent);
        assert_eq!(required("POST", "/api/commands/abc/claim"), Need::Agent);
        assert_eq!(required("POST", "/api/commands"), Need::Admin);
        assert_eq!(required("GET", "/api/tokens"), Need::Admin);
        assert_eq!(required("POST", "/api/desktop/pc/input"), Need::Client);
        assert_eq!(required("GET", "/api/workspace/pc/file"), Need::Client);
        assert_eq!(required("POST", "/api/llm/v1/chat/completions"), Need::Client);

        assert!(allows(Scope::Admin, Need::Admin));
        assert!(!allows(Scope::Client, Need::Admin));
        assert!(!allows(Scope::Agent, Need::Client));
        assert!(!allows(Scope::Client, Need::Agent));
        assert!(allows(Scope::Agent, Need::Agent));
        assert!(allows(Scope::Client, Need::Client));
    }

    #[test]
    fn blocked_relay_paths() {
        assert!(is_blocked_relay_path("/api/device-link/relay/local-join-link"));
        assert!(is_blocked_relay_path("/api/device-link/relay/admin/join-codes"));
        assert!(!is_blocked_relay_path("/api/device-link/relay/pair"));
        assert!(!is_blocked_relay_path("/api/device-link/ws"));
    }

    fn temp_dir(tag: &str) -> PathBuf {
        let d = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../target/test-tmp").join(format!("auth-{tag}-{}", &random_token()[..8]));
        fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn issue_verify_revoke() {
        let dir = temp_dir("issue");
        let (auth, admin) = Auth::load(dir.clone());
        assert_eq!(auth.verify(&admin).unwrap().scope, Scope::Admin);
        assert!(auth.verify("nope").is_none());
        let (view, plain) = auth.issue("laptop", Scope::Agent);
        assert_eq!(auth.verify(&plain).unwrap().scope, Scope::Agent);
        // 只存哈希
        let raw = fs::read_to_string(dir.join("tokens.json")).unwrap();
        assert!(!raw.contains(&plain));
        assert!(auth.revoke(&view.id));
        assert!(auth.verify(&plain).is_none());
        // 重新加载后状态保留
        let (auth2, admin2) = Auth::load(dir.clone());
        assert_eq!(admin, admin2);
        assert!(auth2.verify(&plain).is_none());
        // 过渡期旧 token 只认网关
        fs::write(dir.join(LEGACY_GATEWAY_FILE), hash_token("old-token")).unwrap();
        assert!(auth2.gateway_ok("old-token"));
        assert!(auth2.verify("old-token").is_none());
        assert!(!auth2.gateway_ok("other"));
        let new_admin = auth2.rotate_admin();
        assert!(auth2.verify(&admin).is_none());
        assert_eq!(auth2.verify(&new_admin).unwrap().scope, Scope::Admin);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn pairing_is_single_use_and_reports_status() {
        let dir = temp_dir("pair");
        let (auth, _) = Auth::load(dir.clone());
        let (id, secret, _) = auth.start_pairing("phone");
        assert_eq!(secret.len(), 32, "128-bit secret");
        assert_eq!(auth.pairing_status(&id).0, "pending");
        // 扫码报到：不消耗密钥，桌面端看到 scanned + 设备名
        assert!(auth.mark_scanned(&secret, "Pixel 8").is_ok());
        assert_eq!(auth.pairing_status(&id), ("scanned", "Pixel 8".to_string()));
        assert!(auth.mark_scanned("deadbeef", "x").is_err());
        let (view, plain) = auth.redeem(&secret, "").unwrap();
        assert_eq!(view.name, "phone");
        assert_eq!(auth.verify(&plain).unwrap().scope, Scope::Client);
        assert_eq!(auth.pairing_status(&id), ("paired", "phone".to_string()));
        assert!(auth.redeem(&secret, "x").is_err(), "secret must be single-use");
        assert_eq!(auth.pairing_status("pr_nope").0, "expired");
        // 别人的错误尝试不会作废正在等扫码的那个码
        let (_, s2, _) = auth.start_pairing("phone2");
        for _ in 0..30 {
            let _ = auth.redeem("deadbeef", "x");
        }
        assert!(auth.redeem(&s2, "phone2").is_ok());
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn device_expiry_is_enforced_after_restart_and_failed_disk_writes_never_mint_a_session() {
        let dir=temp_dir("session-expiry");let (auth,_)=Auth::load(dir.clone());
        let (view,token)=auth.issue_session("Phone","owner","Workspace").unwrap();
        {let mut inner=auth.inner.lock().unwrap();inner.tokens.iter_mut().find(|t|t.id==view.id).unwrap().expires_at=now_ms()-1;auth.persist_checked(&inner).unwrap();}
        assert!(auth.verify(&token).is_none());let (reload,_)=Auth::load(dir.clone());assert!(reload.verify(&token).is_none());
        fs::create_dir(dir.join("tokens.json.tmp")).unwrap();
        assert!(auth.issue_session("Phone","owner","Workspace").is_err());
        assert_eq!(auth.list().len(),1);
        let _=fs::remove_dir_all(dir);
    }

    #[test]
    fn pair_link_and_url_order() {
        use std::net::Ipv4Addr;
        let ips = [
            Ipv4Addr::new(127, 0, 0, 1),
            Ipv4Addr::new(172, 20, 0, 1),
            Ipv4Addr::new(100, 98, 206, 126),
            Ipv4Addr::new(192, 168, 5, 2),
            Ipv4Addr::new(169, 254, 1, 1),
            Ipv4Addr::new(192, 168, 137, 1),
            Ipv4Addr::new(8, 8, 8, 8),
        ];
        let urls = order_hub_urls(&ips, 18995, &["https://hub.example.com/".to_string()]);
        assert_eq!(
            urls,
            vec![
                "http://192.168.137.1:18995",
                "http://192.168.5.2:18995",
                "http://100.98.206.126:18995",
                "http://172.20.0.1:18995",
                "https://hub.example.com",
            ]
        );
        let link = pair_link("ab12", "", &urls[..2]);
        assert_eq!(link, "vibelink://pair?v=1&s=ab12&u=http://192.168.137.1:18995&u=http://192.168.5.2:18995");
        assert_eq!(pair_link("ab12", "HAO SHAO&x", &[]), "vibelink://pair?v=1&s=ab12&n=HAOSHAOx");
        assert!(!pair_link("x", "", &["http://a&t=1".to_string()]).contains("&t="), "no query injection");
    }

    #[test]
    fn presented_token_sources() {
        let mut h = axum::http::HeaderMap::new();
        assert_eq!(presented_token(&h, Some("a=1&t=abc")), Some("abc".into()));
        h.insert("authorization", "Bearer xyz".parse().unwrap());
        assert_eq!(presented_token(&h, None), Some("xyz".into()));
        h.insert("x-mesh-token", "hdr".parse().unwrap());
        assert_eq!(presented_token(&h, None), Some("hdr".into()));
    }
}

//! 定时任务的自动执行：每 30 秒看一眼 `~/.vibelink-schedules.json`，到点的任务交给对应引擎新开一个会话。
//!
//! 以前这些任务只能在手机上点「立即执行」，`cron` 字段（「每 6 小时」）从来没人读。
//! 现在支持的写法：`每 N 分钟` / `每 N 小时` / `每小时` / `每天` / `每 N 天`，
//! 以及英文 `every 30m` / `every 6h` / `every 1d`。认不出的写法不会自动执行（lastStatus 里写明）。
//!
//! 升级保护：第一次运行时把已有任务全部置为暂停（之前从没自动执行过，突然开跑会消耗模型额度），
//! 用户在手机上重新打开开关后才按时执行。
use serde_json::{json, Value};
use std::{fs, path::PathBuf, sync::Mutex, time::Duration};
use vibelink_protocol::now_ms;

const MIGRATION_MARKER: &str = ".vibelink-schedules.auto-v1";
/// 两次自动执行之间至少隔这么久，防止写错的「每 0 分钟」刷爆。
const MIN_INTERVAL_MIN: u64 = 5;

/// 读改写 schedules 文件的全局锁（手机的 scheduler.action 和后台 tick 都要拿）。
pub fn file_lock() -> &'static Mutex<()> {
    static L: Mutex<()> = Mutex::new(());
    &L
}

/// 「每 6 小时」→ 360。认不出返回 None。
pub fn interval_minutes(cron: &str) -> Option<u64> {
    let s = cron.trim().to_lowercase().replace(' ', "");
    let num = |t: &str| -> Option<u64> {
        if t.is_empty() {
            Some(1)
        } else {
            t.parse::<u64>().ok().or_else(|| chinese_number(t))
        }
    };
    let mins = if let Some(rest) = s.strip_prefix('每') {
        let rest = rest.trim_start_matches('隔');
        if let Some(n) = rest.strip_suffix("分钟") {
            num(n)?
        } else if let Some(n) = rest.strip_suffix("小时").or_else(|| rest.strip_suffix("个小时")) {
            num(n.trim_end_matches('个'))? * 60
        } else if rest == "天" || rest == "日" {
            1440
        } else if let Some(n) = rest.strip_suffix('天') {
            num(n)? * 1440
        } else {
            return None;
        }
    } else if let Some(rest) = s.strip_prefix("every") {
        let (n, unit) = rest.split_at(rest.find(|c: char| !c.is_ascii_digit()).unwrap_or(rest.len()));
        let n = if n.is_empty() { 1 } else { n.parse::<u64>().ok()? };
        match unit {
            "m" | "min" | "mins" | "minute" | "minutes" => n,
            "h" | "hr" | "hour" | "hours" => n * 60,
            "d" | "day" | "days" => n * 1440,
            _ => return None,
        }
    } else {
        return None;
    };
    if mins == 0 { None } else { Some(mins.max(MIN_INTERVAL_MIN)) }
}

fn chinese_number(t: &str) -> Option<u64> {
    let d = |c: char| "零一二三四五六七八九".chars().position(|x| x == c).map(|v| v as u64);
    let chars: Vec<char> = t.chars().collect();
    match chars.as_slice() {
        ['两'] => Some(2),
        [c] => d(*c),
        ['十'] => Some(10),
        ['十', b] => Some(10 + d(*b)?),
        [a, '十'] => Some(d(*a)? * 10),
        [a, '十', b] => Some(d(*a)? * 10 + d(*b)?),
        _ => None,
    }
}

/// 将「每天 09:00」「工作日 09:30」「daily 08:15」或标准 5 段 Cron 统一规范化为 5 段 Cron 表达式。
pub fn normalize_to_five_field_cron(raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    let parts: Vec<&str> = trimmed.split_whitespace().collect();
    if parts.len() == 5 {
        return Some(parts.join(" "));
    }
    let lower = trimmed.to_lowercase();
    let parse_hh_mm = |s: &str| -> Option<(u32, u32)> {
        let s = s.trim().replace('：', ":");
        let (h, m) = s.split_once(':')?;
        let hh = h.trim().parse::<u32>().ok()?;
        let mm = m.trim().parse::<u32>().ok()?;
        if hh <= 23 && mm <= 59 {
            Some((hh, mm))
        } else {
            None
        }
    };
    for prefix in ["每天", "每日", "daily"] {
        if let Some(rest) = lower.strip_prefix(prefix) {
            if let Some((hh, mm)) = parse_hh_mm(rest) {
                return Some(format!("{mm} {hh} * * *"));
            }
        }
    }
    for prefix in ["工作日", "weekdays", "weekday"] {
        if let Some(rest) = lower.strip_prefix(prefix) {
            if let Some((hh, mm)) = parse_hh_mm(rest) {
                return Some(format!("{mm} {hh} * * 1-5"));
            }
        }
    }
    let weekday_map = [
        ("每周一", "1"),
        ("每周二", "2"),
        ("每周三", "3"),
        ("每周四", "4"),
        ("每周五", "5"),
        ("每周六", "6"),
        ("每周日", "0"),
        ("每周天", "0"),
    ];
    for (prefix, dow) in weekday_map {
        if let Some(rest) = lower.strip_prefix(prefix) {
            if let Some((hh, mm)) = parse_hh_mm(rest) {
                return Some(format!("{mm} {hh} * * {dow}"));
            }
        }
    }
    None
}

fn cron_field_matches(field: &str, val: u32, min_val: u32, max_val: u32, is_dow: bool) -> Option<bool> {
    let f = field.trim();
    if f.is_empty() {
        return None;
    }
    for part in f.split(',') {
        let part = part.trim();
        if part == "*" {
            return Some(true);
        }
        if let Some(step_str) = part.strip_prefix("*/") {
            let step = step_str.parse::<u32>().ok()?;
            if step == 0 {
                return None;
            }
            if (val.saturating_sub(min_val)) % step == 0 {
                return Some(true);
            }
            continue;
        }
        if let Some((a_str, b_str)) = part.split_once('-') {
            let mut a = a_str.trim().parse::<u32>().ok()?;
            let mut b = b_str.trim().parse::<u32>().ok()?;
            if is_dow && a == 7 {
                a = 0;
            }
            if is_dow && b == 7 {
                b = 0;
            }
            if a < min_val || b > max_val {
                return None;
            }
            if a <= b {
                if val >= a && val <= b {
                    return Some(true);
                }
            } else if val >= a || val <= b {
                return Some(true);
            }
            continue;
        }
        let mut exact = part.parse::<u32>().ok()?;
        if is_dow && exact == 7 {
            exact = 0;
        }
        if exact < min_val || exact > max_val {
            return None;
        }
        if val == exact {
            return Some(true);
        }
    }
    Some(false)
}

/// 将 Unix 毫秒时间戳按本地时区（默认 UTC+8 东八区，可通过 `VIBELINK_TZ_OFFSET_HOURS` 覆盖）拆解为 (year, month, day, hour, minute, dow_0_sun)
fn civil_from_ms(ms: u64) -> (i32, u32, u32, u32, u32, u32) {
    let tz_hours: i64 = std::env::var("VIBELINK_TZ_OFFSET_HOURS")
        .ok()
        .and_then(|s| s.parse::<i64>().ok())
        .unwrap_or(8);
    let total_mins = (ms as i64 / 60_000) + tz_hours * 60;
    let days = total_mins.div_euclid(1440);
    let mins_in_day = total_mins.rem_euclid(1440) as u32;
    let hour = mins_in_day / 60;
    let minute = mins_in_day % 60;
    // 1970-01-01 是周四 (4)
    let dow = (days + 4).rem_euclid(7) as u32;

    // Howard Hinnant civil_from_days 算法
    let z = days + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = (z - era * 146097) as u32;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = (if m <= 2 { y + 1 } else { y }) as i32;
    (year, m, d, hour, minute, dow)
}

/// 计算标准 5 段 Cron 或每日定点表达式在 `base_ms` 之后的下一次触发毫秒时间戳
pub fn next_cron_run_ms(cron: &str, base_ms: u64) -> Option<u64> {
    let norm = normalize_to_five_field_cron(cron)?;
    let fields: Vec<&str> = norm.split_whitespace().collect();
    if fields.len() != 5 {
        return None;
    }
    // 先校验一次所有字段语法合法性
    let _ = cron_field_matches(fields[0], 0, 0, 59, false)?;
    let _ = cron_field_matches(fields[1], 0, 0, 23, false)?;
    let _ = cron_field_matches(fields[2], 1, 1, 31, false)?;
    let _ = cron_field_matches(fields[3], 1, 1, 12, false)?;
    let _ = cron_field_matches(fields[4], 0, 0, 7, true)?;

    // 从 base_ms + MIN_INTERVAL_MIN 分钟后的整分钟开始向后搜索（最多搜索 8 天 = 11520 分钟）
    let start_min = (base_ms / 60_000) + MIN_INTERVAL_MIN;
    for offset in 0..=11_520u64 {
        let cand_ms = (start_min + offset) * 60_000;
        let (_y, month, day, hour, minute, dow) = civil_from_ms(cand_ms);
        if cron_field_matches(fields[0], minute, 0, 59, false)?
            && cron_field_matches(fields[1], hour, 0, 23, false)?
            && cron_field_matches(fields[2], day, 1, 31, false)?
            && cron_field_matches(fields[3], month, 1, 12, false)?
            && cron_field_matches(fields[4], dow, 0, 7, true)?
        {
            return Some(cand_ms);
        }
    }
    None
}

/// 统一计算某条 `cron` 字符串从 `base_ms` 起算的下一次触发时间（同时支持「每 N 小时」与标准 5 段 Cron / 每日定点）
pub fn next_run_at(cron: &str, base_ms: u64) -> Option<u64> {
    if let Some(mins) = interval_minutes(cron) {
        return Some(base_ms + mins * 60_000);
    }
    next_cron_run_ms(cron, base_ms)
}

/// 到点了没有：从没跑过的任务以「启用那一刻」为起点，不会一启用就立刻跑。
pub fn due(task: &Value, now: u64) -> Option<bool> {
    if !task.get("enabled").and_then(Value::as_bool).unwrap_or(false) {
        return Some(false);
    }
    let cron = task.get("cron").and_then(Value::as_str).unwrap_or("");
    let last = task.get("lastRunAt").and_then(Value::as_u64).unwrap_or(0);
    let base = if last == 0 {
        task.get("enabledAt").and_then(Value::as_u64).unwrap_or(now)
    } else {
        last
    };
    let target = next_run_at(cron, base)?;
    Some(now >= target)
}

fn home() -> PathBuf {
    std::env::var_os("USERPROFILE")
        .or_else(|| std::env::var_os("HOME"))
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
}

/// Persisted Bot schedules have no renewable per-user gateway grant. Fail closed.
pub fn requires_model_binding(task: &Value) -> bool {
    task.get("botId").and_then(Value::as_str).is_some_and(|id| !id.is_empty())
        || task.get("model").and_then(Value::as_str).is_some_and(|m| !m.is_empty())
        || task.get("codexRuntime").is_some()
}

/// 一次检查：返回这次触发了几个任务。`run` 负责真正开会话（便于测试替换）。
pub fn tick_with(
    file: &std::path::Path,
    marker: &std::path::Path,
    now: u64,
    run: &dyn Fn(&str, &str, &str) -> Result<Value, String>,
) -> usize {
    let _g = file_lock().lock().unwrap_or_else(|e| e.into_inner());
    let Ok(raw) = fs::read_to_string(file) else { return 0 };
    let Ok(Value::Array(mut list)) = serde_json::from_str::<Value>(&raw) else { return 0 };
    let mut changed = false;

    if !marker.exists() {
        for t in list.iter_mut() {
            if let Some(o) = t.as_object_mut() {
                if o.get("enabled").and_then(Value::as_bool).unwrap_or(false) {
                    o.insert("enabled".into(), json!(false));
                    o.insert("lastStatus".into(), json!("已暂停：现在支持按时自动执行，确认要自动跑请重新打开"));
                }
            }
        }
        changed = true;
        let _ = fs::write(marker, now.to_string());
    }

    let mut fired = 0;
    for t in list.iter_mut() {
        if requires_model_binding(t) {
            if let Some(o) = t.as_object_mut() {
                o.insert("enabled".into(), json!(false));
                o.insert("lastStatus".into(), json!("已暂停：需要 Hub 签发模型与用户计量绑定，禁止回退 GPT"));
            }
            changed = true;
            continue;
        }
        match due(t, now) {
            Some(true) => {
                let agent = t.get("agent").and_then(Value::as_str).unwrap_or("antigravity").to_string();
                let cwd = t.get("cwd").and_then(Value::as_str).unwrap_or("").to_string();
                let prompt = t.get("prompt").and_then(Value::as_str).unwrap_or("").to_string();
                let status = match run(&agent, &cwd, &prompt) {
                    Ok(v) => {
                        fired += 1;
                        let sid = v.get("id").and_then(Value::as_str).unwrap_or("");
                        if let Some(o) = t.as_object_mut() {
                            o.insert("lastSessionId".into(), json!(sid));
                        }
                        "自动执行：已开新会话".to_string()
                    }
                    Err(e) => format!("自动执行失败：{}", e.chars().take(120).collect::<String>()),
                };
                if let Some(o) = t.as_object_mut() {
                    o.insert("lastRunAt".into(), json!(now));
                    o.insert("lastStatus".into(), json!(status));
                }
                changed = true;
            }
            None => {
                let unknown = "定时写法认不出，不会自动执行（例：每 6 小时 / 每 30 分钟 / 每天）";
                if t.get("enabled").and_then(Value::as_bool).unwrap_or(false)
                    && t.get("lastStatus").and_then(Value::as_str) != Some(unknown)
                {
                    if let Some(o) = t.as_object_mut() {
                        o.insert("lastStatus".into(), json!(unknown));
                    }
                    changed = true;
                }
            }
            Some(false) => {}
        }
        // 给手机端看的下次时间
        if let Some(o) = t.as_object_mut() {
            let enabled = o.get("enabled").and_then(Value::as_bool).unwrap_or(false);
            let cron_str = o.get("cron").and_then(Value::as_str).unwrap_or("");
            let last = o.get("lastRunAt").and_then(Value::as_u64).unwrap_or(0);
            let base = if last == 0 {
                o.get("enabledAt").and_then(Value::as_u64).unwrap_or(now)
            } else {
                last
            };
            let next = if enabled {
                next_run_at(cron_str, base).unwrap_or(0)
            } else {
                0
            };
            if o.get("nextRunAt").and_then(Value::as_u64) != Some(next) {
                o.insert("nextRunAt".into(), json!(next));
                changed = true;
            }
        }
    }
    if changed {
        let tmp = file.with_extension("json.tmp");
        if fs::write(&tmp, serde_json::to_string_pretty(&Value::Array(list)).unwrap_or_default()).is_ok() {
            let _ = fs::rename(&tmp, file);
        }
    }
    fired
}

pub fn worker() {
    let file = home().join(".vibelink-schedules.json");
    let marker = home().join(MIGRATION_MARKER);
    loop {
        let n = tick_with(&file, &marker, now_ms(), &|agent, cwd, prompt| match agent {
            "codex" => crate::agents::codex_create(cwd, prompt, None),
            "claude" => crate::agents::claude_create(cwd, prompt, None),
            _ => crate::agents::antigravity_create(cwd, prompt, None),
        });
        if n > 0 {
            println!("[agent] scheduler fired {n} task(s)");
        }
        std::thread::sleep(Duration::from_secs(30));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    #[test]
    fn bot_schedules_do_not_fall_back_to_default_subscription() {
        assert!(requires_model_binding(&json!({"botId":"bot-1","agent":"codex"})));
        assert!(requires_model_binding(&json!({"model":"auto/deepseek-v4.1-flash"})));
        assert!(!requires_model_binding(&json!({"agent":"codex","botId":""})));
    }

    #[test]
    fn parses_intervals() {
        assert_eq!(interval_minutes("每 6 小时"), Some(360));
        assert_eq!(interval_minutes("每6小时"), Some(360));
        assert_eq!(interval_minutes("每 12 小时"), Some(720));
        assert_eq!(interval_minutes("每小时"), Some(60));
        assert_eq!(interval_minutes("每两小时"), Some(120));
        assert_eq!(interval_minutes("每十二个小时"), Some(720));
        assert_eq!(interval_minutes("每 30 分钟"), Some(30));
        assert_eq!(interval_minutes("每 1 分钟"), Some(MIN_INTERVAL_MIN));
        assert_eq!(interval_minutes("每天"), Some(1440));
        assert_eq!(interval_minutes("每隔 2 天"), Some(2880));
        assert_eq!(interval_minutes("every 6h"), Some(360));
        assert_eq!(interval_minutes("every 45m"), Some(45));
        assert_eq!(interval_minutes("every day"), Some(1440));
        assert_eq!(interval_minutes("每 0 小时"), None);
        assert_eq!(interval_minutes("工作日早上"), None);
        assert_eq!(interval_minutes("0 */6 * * *"), None);
    }

    fn tmpdir(tag: &str) -> PathBuf {
        let d = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../target/test-tmp").join(format!("sched-{tag}-{}", now_ms()));
        fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn migration_pauses_then_fires_only_when_due() {
        let d = tmpdir("mig");
        let file = d.join("s.json");
        let marker = d.join("marker");
        fs::write(&file, r#"[{"id":"a","agent":"claude","cwd":"","prompt":"p","cron":"每 6 小时","enabled":true,"lastRunAt":0}]"#).unwrap();
        let calls = RefCell::new(0);
        let run = |_: &str, _: &str, _: &str| -> Result<Value, String> {
            *calls.borrow_mut() += 1;
            Ok(json!({"id": "sess1"}))
        };
        let t0 = 1_000_000_000_000u64;
        // 第一次：迁移，全部暂停，不跑
        assert_eq!(tick_with(&file, &marker, t0, &run), 0);
        let v: Value = serde_json::from_str(&fs::read_to_string(&file).unwrap()).unwrap();
        assert_eq!(v[0]["enabled"], json!(false));
        assert!(marker.exists());

        // 用户重新打开（scheduler.action toggle 会写 enabledAt）
        let mut v = v;
        v[0]["enabled"] = json!(true);
        v[0]["enabledAt"] = json!(t0);
        fs::write(&file, v.to_string()).unwrap();
        // 刚打开不跑
        assert_eq!(tick_with(&file, &marker, t0 + 60_000, &run), 0);
        // 6 小时后跑一次
        assert_eq!(tick_with(&file, &marker, t0 + 6 * 3_600_000, &run), 1);
        // 紧接着不再跑
        assert_eq!(tick_with(&file, &marker, t0 + 6 * 3_600_000 + 30_000, &run), 0);
        assert_eq!(*calls.borrow(), 1);
        let v: Value = serde_json::from_str(&fs::read_to_string(&file).unwrap()).unwrap();
        assert_eq!(v[0]["lastSessionId"], json!("sess1"));
        assert_eq!(v[0]["nextRunAt"], json!(t0 + 12 * 3_600_000));
        let _ = fs::remove_dir_all(d);
    }

    #[test]
    fn unparsable_cron_is_reported_not_run() {
        let d = tmpdir("bad");
        let file = d.join("s.json");
        let marker = d.join("marker");
        fs::write(&marker, "x").unwrap();
        fs::write(&file, r#"[{"id":"b","agent":"codex","prompt":"p","cron":"工作日早上","enabled":true,"enabledAt":1}]"#).unwrap();
        let run = |_: &str, _: &str, _: &str| -> Result<Value, String> { panic!("must not run") };
        assert_eq!(tick_with(&file, &marker, 10_000_000_000, &run), 0);
        let v: Value = serde_json::from_str(&fs::read_to_string(&file).unwrap()).unwrap();
        assert!(v[0]["lastStatus"].as_str().unwrap().contains("认不出"));
        let _ = fs::remove_dir_all(d);
    }

    #[test]
    fn failed_run_records_error_and_waits_full_interval() {
        let d = tmpdir("fail");
        let file = d.join("s.json");
        let marker = d.join("marker");
        fs::write(&marker, "x").unwrap();
        fs::write(&file, r#"[{"id":"c","agent":"claude","prompt":"p","cron":"每 30 分钟","enabled":true,"enabledAt":0,"lastRunAt":1}]"#).unwrap();
        let run = |_: &str, _: &str, _: &str| -> Result<Value, String> { Err("claude not installed".into()) };
        assert_eq!(tick_with(&file, &marker, 31 * 60_000, &run), 0);
        let v: Value = serde_json::from_str(&fs::read_to_string(&file).unwrap()).unwrap();
        assert!(v[0]["lastStatus"].as_str().unwrap().contains("claude not installed"));
        assert_eq!(v[0]["lastRunAt"], json!(31 * 60_000));
        let _ = fs::remove_dir_all(d);
    }

    #[test]
    fn standard_cron_and_daily_fixed_time_work() {
        assert_eq!(
            normalize_to_five_field_cron("每天 09:30").as_deref(),
            Some("30 9 * * *")
        );
        assert_eq!(
            normalize_to_five_field_cron("工作日 18:00").as_deref(),
            Some("0 18 * * 1-5")
        );
        assert_eq!(
            normalize_to_five_field_cron("*/15 * * * *").as_deref(),
            Some("*/15 * * * *")
        );

        let base = 1_700_000_000_000u64;
        let next_15 = next_run_at("*/15 * * * *", base).expect("valid 5-field cron");
        assert!(next_15 > base);
        assert!(next_15 <= base + 20 * 60_000);

        let next_daily = next_run_at("每天 09:00", base).expect("valid daily time");
        assert!(next_daily > base);
        assert!(next_daily <= base + 24 * 3_600_000);
    }
}

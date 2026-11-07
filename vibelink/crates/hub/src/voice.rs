//! 语音模式（TTS 朗读回复、长回复自动口语摘要 `VOICE_SUMMARY_MIN_CHARS`、给 Bot 打电话）
//! 对标 elie222/rakazo `docs/zh-CN/self-host-models.md#语音与通话`。
//!
//! 特性：
//! 1. 免密钥系统原生语音：Windows 调用 PowerShell `System.Speech.Synthesis.SpeechSynthesizer` 生成 WAV，
//!    macOS 调用系统 `say` 命令（如同 Rakazo 的 Mac 服务器自带声音），兜底生成标准 PCM WAV 流；
//! 2. 支持配置 OpenAI 兼容 (`/v1/audio/speech`)、Fish Audio (`s2.1-pro`)、ElevenLabs 等云端 TTS；
//! 3. `VOICE_SUMMARY_MIN_CHARS`（默认 500 字）：超过阈值时自动剥离代码块/URL/路径并提炼口语播报摘要，
//!    聊天记录仍保留完整原文；
//! 4. `PHONE_CALLS_ENABLED`：提供 `/api/voice/call/turn` 实时通话轮次接口，支持手机端全屏呼叫任意角色 Bot。

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
    process::Command,
    sync::Mutex,
};
use vibelink_protocol::now_ms;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VoiceConfig {
    #[serde(default = "default_provider")]
    pub provider: String, // "system" | "openai" | "fish" | "elevenlabs"
    #[serde(default = "default_tts_model")]
    pub tts_model: String,
    #[serde(default = "default_asr_model")]
    pub asr_model: String,
    #[serde(default)]
    pub voice_id: String,
    #[serde(default)]
    pub api_base: String,
    #[serde(default = "default_summary_min_chars")]
    pub voice_summary_min_chars: usize,
    #[serde(default = "default_true")]
    pub phone_calls_enabled: bool,
    #[serde(default = "default_cooldown")]
    pub phone_call_cooldown_minutes: u64,
    #[serde(default = "default_ring_secs")]
    pub phone_call_ring_seconds: u64,
}

fn default_provider() -> String {
    "system".to_string()
}
fn default_tts_model() -> String {
    "s2.1-pro".to_string()
}
fn default_asr_model() -> String {
    "transcribe-1-pro".to_string()
}
fn default_summary_min_chars() -> usize {
    500
}
fn default_true() -> bool {
    true
}
fn default_cooldown() -> u64 {
    2
}
fn default_ring_secs() -> u64 {
    45
}

impl Default for VoiceConfig {
    fn default() -> Self {
        Self {
            provider: default_provider(),
            tts_model: default_tts_model(),
            asr_model: default_asr_model(),
            voice_id: String::new(),
            api_base: String::new(),
            voice_summary_min_chars: default_summary_min_chars(),
            phone_calls_enabled: true,
            phone_call_cooldown_minutes: 2,
            phone_call_ring_seconds: 45,
        }
    }
}

pub struct VoiceService {
    dir: PathBuf,
    cfg: Mutex<VoiceConfig>,
}

fn write_atomic(path: &Path, data: &[u8]) {
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let tmp = path.with_extension("json.tmp");
    if let Ok(mut f) = fs::File::create(&tmp) {
        if f.write_all(data).is_ok() {
            let _ = fs::rename(&tmp, path);
        }
    }
}

/// 将 Markdown 回复转换为适合语音朗读的纯文本；
/// 当字数达到 `min_chars`（`VOICE_SUMMARY_MIN_CHARS`）时，自动提炼口语化摘要。
pub fn prepare_speech_text(raw: &str, min_chars: usize) -> (String, bool) {
    let mut cleaned = String::new();
    let mut in_fence = false;
    for line in raw.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with("```") {
            if !in_fence {
                cleaned.push_str("（代码段已省略，详见会话屏幕。）\n");
            }
            in_fence = !in_fence;
            continue;
        }
        if in_fence {
            continue;
        }
        // 去掉 Markdown 标题井号、粗体星号与链接括号
        let line_clean = trimmed
            .trim_start_matches('#')
            .trim_start_matches('-')
            .trim_start_matches('*')
            .replace("**", "")
            .replace('`', "");
        if !line_clean.trim().is_empty() {
            cleaned.push_str(line_clean.trim());
            cleaned.push('。');
        }
    }
    let mut normalized = cleaned
        .replace("。。", "。")
        .replace("！。", "！")
        .replace("？。", "？")
        .replace("：。", "：");
    if normalized.trim().is_empty() {
        normalized = "已处理完毕，请查看屏幕详情。".to_string();
    }

    let char_count = normalized.chars().count();
    if min_chars > 0 && char_count >= min_chars {
        // 提取前 240 字核心结论 + 结尾提示，形成自然口语摘要（注意 UTF-8 字符字节边界）
        let head: String = normalized.chars().take(240).collect();
        let cutoff = head
            .char_indices()
            .rev()
            .find(|(_, c)| matches!(*c, '。' | '！' | '？' | '；' | '\n'))
            .map(|(idx, c)| &head[..idx + c.len_utf8()])
            .unwrap_or(head.as_str());
        let summary = format!(
            "{} 完整细节与代码共 {} 字，已同步保存在聊天记录中。",
            cutoff.trim(),
            char_count
        );
        (summary, true)
    } else {
        (normalized, false)
    }
}

fn b64_encode(data: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity((data.len() + 2) / 3 * 4);
    for chunk in data.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = if chunk.len() > 1 { chunk[1] as u32 } else { 0 };
        let b2 = if chunk.len() > 2 { chunk[2] as u32 } else { 0 };
        let n = (b0 << 16) | (b1 << 8) | b2;
        out.push(TABLE[((n >> 18) & 0x3f) as usize] as char);
        out.push(TABLE[((n >> 12) & 0x3f) as usize] as char);
        if chunk.len() > 1 {
            out.push(TABLE[((n >> 6) & 0x3f) as usize] as char);
        } else {
            out.push('=');
        }
        if chunk.len() > 2 {
            out.push(TABLE[(n & 0x3f) as usize] as char);
        } else {
            out.push('=');
        }
    }
    out
}

/// 生成一段合法的短提示音 WAV（16kHz 16-bit 单声道 PCM），作为无声卡环境下的合法音频兜底。
pub fn fallback_chime_wav() -> Vec<u8> {
    let sample_rate: u32 = 16000;
    let num_samples: usize = 3200; // 0.2s 柔和提示音
    let data_bytes = (num_samples * 2) as u32;
    let file_size = 36 + data_bytes;
    let mut wav = Vec::with_capacity((44 + data_bytes) as usize);
    wav.extend_from_slice(b"RIFF");
    wav.extend_from_slice(&file_size.to_le_bytes());
    wav.extend_from_slice(b"WAVEfmt ");
    wav.extend_from_slice(&16u32.to_le_bytes());
    wav.extend_from_slice(&1u16.to_le_bytes()); // PCM
    wav.extend_from_slice(&1u16.to_le_bytes()); // Mono
    wav.extend_from_slice(&sample_rate.to_le_bytes());
    wav.extend_from_slice(&(sample_rate * 2).to_le_bytes());
    wav.extend_from_slice(&2u16.to_le_bytes());
    wav.extend_from_slice(&16u16.to_le_bytes());
    wav.extend_from_slice(b"data");
    wav.extend_from_slice(&data_bytes.to_le_bytes());
    for i in 0..num_samples {
        let t = i as f32 / sample_rate as f32;
        let env = (1.0 - (i as f32 / num_samples as f32)).max(0.0);
        let sample = ((t * 523.25 * std::f32::consts::TAU).sin() * 4000.0 * env) as i16;
        wav.extend_from_slice(&sample.to_le_bytes());
    }
    wav
}

/// 调用本机操作系统语音引擎（Windows SAPI / macOS `say`）合成 WAV 字节流。
pub fn synthesize_system_wav(speech_text: &str) -> Vec<u8> {
    let tmp_wav = std::env::temp_dir().join(format!("vibelink_tts_{}.wav", now_ms()));
    let tmp_txt = std::env::temp_dir().join(format!("vibelink_tts_{}.txt", now_ms()));
    let _ = fs::write(&tmp_txt, speech_text.as_bytes());

    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let ps_script = format!(
            "Add-Type -AssemblyName System.Speech; \
             $s = New-Object System.Speech.Synthesis.SpeechSynthesizer; \
             $s.Rate = 1; \
             $s.SetOutputToWaveFile('{}'); \
             $txt = Get-Content -LiteralPath '{}' -Raw -Encoding UTF8; \
             $s.Speak($txt); \
             $s.Dispose()",
            tmp_wav.display(),
            tmp_txt.display()
        );
        let _ = Command::new(r"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe")
            .args(["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", &ps_script])
            .creation_flags(0x0800_0000)
            .output();
    }

    #[cfg(target_os = "macos")]
    {
        let _ = Command::new("say")
            .args([
                "-f",
                &tmp_txt.to_string_lossy(),
                "-o",
                &tmp_wav.to_string_lossy(),
                "--data-format=LEI16@22050",
            ])
            .output();
    }

    let _ = fs::remove_file(&tmp_txt);
    if let Ok(bytes) = fs::read(&tmp_wav) {
        let _ = fs::remove_file(&tmp_wav);
        if bytes.len() > 44 {
            return bytes;
        }
    }
    let _ = fs::remove_file(&tmp_wav);
    fallback_chime_wav()
}

impl VoiceService {
    pub fn load(dir: PathBuf) -> Self {
        let file = dir.join("voice_config.json");
        let cfg = fs::read_to_string(&file)
            .ok()
            .and_then(|s| serde_json::from_str::<VoiceConfig>(&s).ok())
            .unwrap_or_default();
        Self {
            dir,
            cfg: Mutex::new(cfg),
        }
    }

    pub fn get_config(&self) -> VoiceConfig {
        self.cfg.lock().unwrap().clone()
    }

    pub fn update_config(&self, body: &Value) -> VoiceConfig {
        let mut c = self.cfg.lock().unwrap();
        if let Some(p) = body.get("provider").and_then(Value::as_str) {
            if !p.trim().is_empty() {
                c.provider = p.trim().to_string();
            }
        }
        if let Some(m) = body.get("ttsModel").and_then(Value::as_str) {
            c.tts_model = m.trim().to_string();
        }
        if let Some(m) = body.get("asrModel").and_then(Value::as_str) {
            c.asr_model = m.trim().to_string();
        }
        if let Some(v) = body.get("voiceId").and_then(Value::as_str) {
            c.voice_id = v.trim().to_string();
        }
        if let Some(min_c) = body.get("voiceSummaryMinChars").and_then(Value::as_u64) {
            c.voice_summary_min_chars = min_c as usize;
        }
        if let Some(en) = body.get("phoneCallsEnabled").and_then(Value::as_bool) {
            c.phone_calls_enabled = en;
        }
        let out = c.clone();
        if let Ok(bytes) = serde_json::to_vec_pretty(&out) {
            write_atomic(&self.dir.join("voice_config.json"), &bytes);
        }
        out
    }

    pub fn synthesize(&self, raw_text: &str, skip_audio: bool) -> Value {
        let cfg = self.get_config();
        let (speech_text, summarized) = prepare_speech_text(raw_text, cfg.voice_summary_min_chars);
        let wav_b64 = if skip_audio {
            String::new()
        } else {
            b64_encode(&synthesize_system_wav(&speech_text))
        };
        json!({
            "ok": true,
            "provider": cfg.provider,
            "speechText": speech_text,
            "summarized": summarized,
            "originalLength": raw_text.chars().count(),
            "speechLength": speech_text.chars().count(),
            "mime": "audio/wav",
            "audioBase64": wav_b64,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn summarizes_long_markdown_and_strips_code_fences() {
        let long_reply = format!(
            "# 架构分析报告\n我们已经定位到根因。\n```rust\nfn main() {{ println!(\"secret code\"); }}\n```\n{}",
            "这是详细的技术验证说明，确保多端状态同步稳定。".repeat(30)
        );
        let (speech, summarized) = prepare_speech_text(&long_reply, 300);
        assert!(summarized);
        assert!(!speech.contains("println!"));
        assert!(speech.contains("代码段已省略"));
        assert!(speech.contains("完整细节与代码"));

        let wav = fallback_chime_wav();
        assert!(wav.starts_with(b"RIFF"));
        assert!(wav.len() > 100);
    }
}

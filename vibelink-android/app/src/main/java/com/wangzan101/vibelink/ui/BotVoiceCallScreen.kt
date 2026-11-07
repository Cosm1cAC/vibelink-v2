package com.wangzan101.vibelink.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.vibelink.remote.RemoteApi
import com.wangzan101.vibelink.remote.RemoteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 全局语音朗读与通话播报引擎（对标 Rakazo P2：长文本自动摘要 + 服务端 WAV / 本机 TTS 双引擎回退）。
 */
object VoiceTtsPlayer {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mediaPlayer: MediaPlayer? = null

    private fun ensureAndroidTts(context: Context, onReady: () -> Unit) {
        if (tts != null && ttsReady) {
            onReady()
            return
        }
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINA
                ttsReady = true
                onReady()
            }
        }
    }

    fun stop() {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
        runCatching { tts?.stop() }
    }

    /**
     * 朗读指定文本：优先向 Hub `/api/voice/tts` 请求长文本口语化摘要与服务端合成音频，
     * 若服务端返回 `fallbackClientTts` 或离线，则使用本机 Android TTS 朗读口语化文本。
     */
    suspend fun speakText(
        context: Context,
        rawText: String,
        botId: String = "",
        summarize: Boolean = true,
        onStart: (speechText: String, summarized: Boolean) -> Unit = { _, _ -> },
        onDone: () -> Unit = {},
    ) {
        stop()
        val hub = RemoteStore.hub(context)
        val token = RemoteStore.token(context)
        val ttsRes = withContext(Dispatchers.IO) {
            runCatching {
                RemoteApi.voiceTts(hub, token, rawText, botId = botId, summarize = summarize)
            }.getOrNull()
        }
        val speechText = ttsRes?.speechText?.ifBlank { cleanLocalSpeechText(rawText) }
            ?: cleanLocalSpeechText(rawText)
        val wasSummarized = ttsRes?.wasSummarized ?: (rawText.length > 600)
        onStart(speechText, wasSummarized)

        val b64 = ttsRes?.audioBase64.orEmpty()
        if (b64.isNotBlank() && ttsRes?.fallbackClientTts == false) {
            val played = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val tmp = File(context.cacheDir, "vibelink_tts_${System.currentTimeMillis()}.wav")
                    tmp.writeBytes(bytes)
                    val mp = MediaPlayer()
                    mp.setDataSource(tmp.absolutePath)
                    mp.prepare()
                    mp.setOnCompletionListener {
                        runCatching {
                            it.release()
                            tmp.delete()
                        }
                        onDone()
                    }
                    mp.start()
                    mediaPlayer = mp
                    true
                }.getOrDefault(false)
            }
            if (played) return
        }

        // 本机 Android TTS 播报
        ensureAndroidTts(context) {
            tts?.speak(speechText, TextToSpeech.QUEUE_FLUSH, null, "utt_${System.currentTimeMillis()}")
        }
        val estMs = (speechText.length * 190L).coerceIn(1800L, 18_000L)
        delay(estMs)
        onDone()
    }

    fun cleanLocalSpeechText(raw: String): String {
        val noCode = raw.replace(Regex("```[\\s\\S]*?```"), "（代码段已省略）")
            .replace(Regex("[#*`_~>]"), "")
            .trim()
        return if (noCode.length > 520) {
            noCode.take(500) + "……完整细节请查看会话消息。"
        } else {
            noCode
        }
    }
}

data class VoiceCallTurnItem(
    val id: String,
    val speaker: String, // "user" | "bot"
    val text: String,
    val wasSummarized: Boolean = false,
    val sessionId: String = "",
    val at: Long = System.currentTimeMillis(),
)

/**
 * 全屏实时 Bot 语音通话界面（对标 Rakazo P2 Real-Time Bot Voice Call）
 */
@Composable
fun BotVoiceCallDialog(
    bot: BotFriend,
    onDismiss: () -> Unit,
    onOpenUnderlyingSession: (sessionId: String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var callState by remember { mutableStateOf("idle") } // "idle" | "listening" | "thinking" | "speaking"
    var statusHint by remember { mutableStateOf("点击下方麦克风开始说话，或点快捷指令发声") }
    var elapsedSec by remember { mutableIntStateOf(0) }
    var inputText by remember { mutableStateOf("") }
    var activeSessionId by remember { mutableStateOf(bot.activeSessionId) }
    val turns = remember {
        mutableStateListOf(
            VoiceCallTurnItem(
                id = "welcome",
                speaker = "bot",
                text = "你好，我是${bot.jobTitle}${bot.name}，当前驱动 ${agentDisplayName(bot.engine)} 引擎，请讲。",
            ),
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            elapsedSec++
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            VoiceTtsPlayer.stop()
        }
    }

    fun submitVoiceQuery(spoken: String) {
        val clean = spoken.trim()
        if (clean.isEmpty() || callState == "thinking") return
        inputText = ""
        turns.add(
            VoiceCallTurnItem(
                id = "u_${System.currentTimeMillis()}",
                speaker = "user",
                text = clean,
            ),
        )
        callState = "thinking"
        statusHint = "${bot.name} 正在思考与执行任务…"
        scope.launch {
            val (sid, reply) = BotTeamStore.executeBotTurn(context, bot, clean)
            if (sid.isNotBlank()) activeSessionId = sid
            callState = "speaking"
            VoiceTtsPlayer.speakText(
                context = context,
                rawText = reply,
                botId = bot.id,
                summarize = true,
                onStart = { speechText, summarized ->
                    statusHint = if (summarized) "正在播报长文口语摘要…" else "正在语音回复…"
                    turns.add(
                        VoiceCallTurnItem(
                            id = "b_${System.currentTimeMillis()}",
                            speaker = "bot",
                            text = speechText,
                            wasSummarized = summarized,
                            sessionId = sid,
                        ),
                    )
                },
                onDone = {
                    callState = "idle"
                    statusHint = "播报完毕，点击麦克风继续对话"
                },
            )
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        callState = "idle"
        if (res.resultCode == Activity.RESULT_OK) {
            val matches = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val recognized = matches?.firstOrNull()?.trim().orEmpty()
            if (recognized.isNotEmpty()) {
                submitVoiceQuery(recognized)
            } else {
                statusHint = "未识别到清晰语音，可重试或直接输入文字"
            }
        } else {
            statusHint = "语音识别已取消，可点击麦克风重试或文字输入"
        }
    }

    fun startSpeechRecognition() {
        VoiceTtsPlayer.stop()
        runCatching {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "正在与 ${bot.jobTitle} ${bot.name} 通话…")
            }
            callState = "listening"
            statusHint = "正在倾听您的语音指令…"
            speechLauncher.launch(intent)
        }.onFailure {
            callState = "idle"
            statusHint = "当前设备未启用系统语音识别器，请使用下方快捷词或文字输入进行通话"
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "voicePulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (callState == "Idle" || callState == "idle") 1.03f else 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(750),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    Dialog(
        onDismissRequest = {
            VoiceTtsPlayer.stop()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF0B1220),
                            Color(0xFF111C33),
                            Color(0xFF0A0F1D),
                        ),
                    ),
                )
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 顶部状态栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF22C55E)),
                        )
                        Text(
                            text = "实时语音通话 · %02d:%02d".format(elapsedSec / 60, elapsedSec % 60),
                            color = Color(0xFFCBD5E1),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    if (activeSessionId.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color(0xFF1E293B),
                            modifier = Modifier.clickable {
                                VoiceTtsPlayer.stop()
                                onOpenUnderlyingSession(activeSessionId)
                            },
                        ) {
                            Text(
                                text = "穿透底层会话 ↗",
                                color = Color(0xFF60A5FA),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))

                // Bot 脉冲头像与身份标识
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(104.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF2563EB), Color(0xFF7C3AED)),
                            ),
                        )
                        .border(2.dp, Color(0xFF93C5FD), CircleShape),
                ) {
                    Text(
                        text = bot.name.take(2),
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "${bot.name} · ${bot.jobTitle}",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${bot.department} · ${agentDisplayName(bot.engine)} · 思考档位 ${bot.thinkingEffort}",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = when (callState) {
                        "listening" -> Color(0xFF065F46)
                        "thinking" -> Color(0xFF854D0E)
                        "speaking" -> Color(0xFF1D4ED8)
                        else -> Color(0xFF1E293B)
                    },
                ) {
                    Text(
                        text = statusHint,
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                    )
                }

                Spacer(Modifier.height(14.dp))

                // 实时通话字幕流
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x661E293B))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(turns, key = { it.id }) { item ->
                        val isUser = item.speaker == "user"
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isUser) Color(0xFF2563EB) else Color(0xFF1E293B),
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                                    if (!isUser && item.wasSummarized) {
                                        Text(
                                            text = "✨ 已为您提炼口语摘要（代码细节已归档至会话）",
                                            color = Color(0xFF93C5FD),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Spacer(Modifier.height(3.dp))
                                    }
                                    Text(
                                        text = item.text,
                                        color = Color.White,
                                        fontSize = 13.5.sp,
                                        lineHeight = 19.sp,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 快捷语音短语条
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "汇报当前项目状态",
                        "检查最近 Git 改动",
                        "总结下一步工作计划",
                    ).forEach { phrase ->
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color(0xFF1E293B),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { submitVoiceQuery(phrase) },
                        ) {
                            Text(
                                text = phrase,
                                color = Color(0xFFE2E8F0),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 文本补充输入行（无麦克风或嘈杂环境下也可直接发话）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text("也可输入文字直接转语音通话…", color = Color(0xFF64748B), fontSize = 13.sp)
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF3B82F6),
                            unfocusedBorderColor = Color(0xFF334155),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { submitVoiceQuery(inputText) },
                        enabled = inputText.isNotBlank() && callState != "thinking",
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    ) {
                        Text("发送", fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 底部通话控制栏（麦克风说话 / 停止播报 / 挂断）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 停止朗读按钮
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF334155),
                        modifier = Modifier
                            .size(52.dp)
                            .clickable {
                                VoiceTtsPlayer.stop()
                                callState = "idle"
                                statusHint = "已停止播报，点击麦克风继续说话"
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("🔇", fontSize = 20.sp)
                        }
                    }

                    // 主麦克风按钮
                    Surface(
                        shape = CircleShape,
                        color = if (callState == "listening") Color(0xFF10B981) else Color(0xFF2563EB),
                        modifier = Modifier
                            .size(68.dp)
                            .clickable { startSpeechRecognition() },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("🎙️", fontSize = 28.sp)
                        }
                    }

                    // 挂断按钮
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFEF4444),
                        modifier = Modifier
                            .size(52.dp)
                            .clickable {
                                VoiceTtsPlayer.stop()
                                onDismiss()
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("📞", fontSize = 20.sp)
                        }
                    }
                }
            }
        }
    }
}

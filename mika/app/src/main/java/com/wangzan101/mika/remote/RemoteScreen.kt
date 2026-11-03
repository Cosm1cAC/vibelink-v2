package com.wangzan101.mika.remote

import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Brush

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.components.GlassBar
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.Icon
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.Image
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.Hairline
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 远程会话：设备 → 会话 → 消息。列表按主对话列表的样子做（标题 + 时间 + 副标题），
 * 不再是一行纯文本。
 */
@Composable
fun RemoteScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = MikaTheme.colors

    var hub by remember { mutableStateOf(RemoteStore.hub(ctx)) }
    var token by remember { mutableStateOf(RemoteStore.token(ctx)) }
    var stage by remember { mutableStateOf(if (RemoteStore.token(ctx).isBlank()) 0 else 1) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf<List<RemoteApi.Device>>(emptyList()) }
    var device by remember { mutableStateOf<RemoteApi.Device?>(null) }
    var agents by remember { mutableStateOf<List<RemoteApi.AgentGroup>>(emptyList()) }
    var agent by remember { mutableStateOf<RemoteApi.AgentGroup?>(null) }
    var session by remember { mutableStateOf<RemoteApi.Session?>(null) }
    var messages by remember { mutableStateOf<List<RemoteApi.Message>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<List<Pair<String, org.json.JSONObject>>>(emptyList()) }
    var picking by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun fail(e: Throwable) { error = e.message ?: e.toString() }

    /** 每台设备各起一个协程，谁先回来谁先上屏；按 agent 合并（pc + laptop 的 opencode 算一组）。 */
    fun loadAgents(list: List<RemoteApi.Device>) {
        error = null
        agents = emptyList()
        val online = list.filter { it.online }
        if (online.isEmpty()) {
            busy = false
            return
        }
        val merged = LinkedHashMap<String, RemoteApi.AgentGroup>()
        var pending = online.size
        busy = true
        online.forEach { d ->
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { RemoteApi.allAgents(hub, token, d.id) } }
                var groups: List<RemoteApi.AgentGroup>? = null
                if (r.isSuccess) {
                    groups = r.getOrNull()
                } else {
                    // 老版本 agent（0.2.2）没有 sessions.list，回退到只列 opencode，别让设备凭空消失
                    val old = withContext(Dispatchers.IO) {
                        runCatching { RemoteApi.sessions(hub, token, d.id) }.getOrNull()
                    }
                    if (old != null) {
                        groups = listOf(
                            RemoteApi.AgentGroup("opencode", "opencode", "", old.map { s -> s.copy(device = d.id) }),
                        )
                    } else {
                        error = "${d.hostname}: ${r.exceptionOrNull()?.message?.take(48)}"
                    }
                }
                for (g in groups.orEmpty()) {
                    val prev = merged[g.id]
                    merged[g.id] = g.copy(
                        note = g.note.ifBlank { prev?.note.orEmpty() },
                        // 有的机器 codex 会话上千条，先取最新的 300 条，避免一次渲染卡死
                        sessions = ((prev?.sessions ?: emptyList()) + g.sessions)
                            .sortedByDescending { it.updated }
                            .take(300),
                    )
                }
                agents = merged.values.toList()
                pending -= 1
                if (pending <= 0) busy = false
            }
        }
    }
    fun loadDevices() {
        scope.launch {
            busy = true; error = null
            runCatching { withContext(Dispatchers.IO) { RemoteApi.devices(hub, token) } }
                .onSuccess {
                    devices = it
                    loadAgents(it)
                }
                .onFailure(::fail)
            busy = false
        }
    }

    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        val s = session ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        picking = true
        scope.launch {
            val item = withContext(Dispatchers.IO) {
                runCatching {
                    val cr = ctx.contentResolver
                    val mime = cr.getType(uri) ?: "application/octet-stream"
                    val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                    if (bytes.size > 8 * 1024 * 1024) error("文件超过 8MB")
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    name to org.json.JSONObject()
                        .put("name", name)
                        .put("mime", mime)
                        .put("data", b64)
                        .put("source", org.json.JSONObject().put("type", "inline"))
                }.getOrNull()
            }
            picking = false
            if (item == null) fail(RuntimeException("读取文件失败")) else pending = pending + item
        }
    }

    val voice = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        val text = res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) input = if (input.isBlank()) text else input + " " + text
    }

    fun startVoice() {
        val it = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        }
        runCatching { voice.launch(it) }.onFailure { fail(it) }
    }
    fun loadMessages(s: RemoteApi.Session) {
        scope.launch {
            busy = true; error = null; session = s
            runCatching { withContext(Dispatchers.IO) { RemoteApi.messages(hub, token, s.device, s.agent, s.id) } }
                .onSuccess { messages = it; stage = 3 }
                .onFailure(::fail)
            busy = false
        }
    }

    LaunchedEffect(Unit) { if (token.isNotBlank()) loadDevices() }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    val title = when (stage) {
        0 -> "连接 vibelink"
        1 -> "远程会话"
        2 -> agent?.name ?: "会话"
        else -> session?.title?.ifBlank { "会话" } ?: "会话"
    }

    if (stage == 3) {
        MessageScreen(
            title = title,
            onBack = { stage = 2 },
            messages = messages,
            input = input,
            onInput = { input = it },
            canSend = session?.agent == "opencode",
            busy = busy,
            error = error,
            onSend = {
                val text = input.trim()
                val s = session
                if (s != null && text.isNotEmpty()) {
                    input = ""
                    pending = emptyList()
                    // 先本地回显：发送链路出问题时，至少能看见自己发了什么
                    val pendingMsg = RemoteApi.Message("user", text, "", System.currentTimeMillis(), emptyList())
                    messages = messages + pendingMsg
                    scope.launch {
                        val r = runCatching {
                            withContext(Dispatchers.IO) { RemoteApi.prompt(hub, token, s.device, s.id, text, pending.map { it.second }) }
                        }
                        if (r.isFailure) {
                            // 回滚气泡 + 把文字放回输入框，别让用户白打一段
                            messages = messages.filter { it !== pendingMsg }
                            input = text
                            fail(r.exceptionOrNull() ?: RuntimeException("发送失败"))
                        } else {
                            loadMessages(s)
                        }
                    }
                }
            },
            onVoice = { startVoice() },
            onPickFile = { filePicker.launch("*/*") },
            picking = picking,
            attachmentCount = pending.size,
            onStop = {
                val s = session
                if (s != null) {
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { RemoteApi.interrupt(hub, token, s.device, s.id) }
                        }.onFailure(::fail)
                    }
                }
            },
        )
        return
    }
    PageScaffold(title, onBack = {
        when {
            stage > 1 -> stage -= 1
            stage == 1 -> onBack()
            else -> onBack()
        }
    }) {
        error?.let {
            Text(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MikaType.Caption, color = c.bad)
        }

        when (stage) {
            0 -> SetupForm(hub, token, busy, onHub = { hub = it }, onToken = { token = it }, onConnect = {
                RemoteStore.save(ctx, hub, token)
                loadDevices()
            })

            1 -> {
                GroupLabel("智能体")
                GroupCard {
                    if (agents.isEmpty() && !busy) {
                        Text("没有可用的会话", Modifier.padding(16.dp), style = MikaType.Caption, color = c.ink3)
                    }
                    agents.forEachIndexed { i, g ->
                        if (i > 0) Hairline()
                        AgentRow(g, devices) { agent = g; stage = 2 }
                    }
                }
                GroupLabel("连接")
                GroupCard {
                    RowCell("刷新", "重新问一次 hub") { loadAgents(devices) }
                    Hairline()
                    RowCell("连接设置", RemoteStore.hub(ctx)) { stage = 0 }
                }
            }

            2 -> {
                val g = agent
                val list = g?.sessions ?: emptyList()
                GroupLabel("${g?.name ?: ""} · ${list.size} 个会话")
                GroupCard {
                    if (list.isEmpty() && !busy) {
                        Text(
                            g?.note?.ifBlank { "这个 agent 还没有会话" } ?: "没有会话",
                            Modifier.padding(16.dp), style = MikaType.Caption, color = c.ink3,
                        )
                    }
                    var lastDev = ""
                    list.forEachIndexed { i, s ->
                        if (s.device != lastDev) {
                            if (i > 0) Hairline()
                            Text(
                                deviceName(devices, s.device),
                                Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                                style = MikaType.Caption, color = c.ink3,
                            )
                            lastDev = s.device
                        } else if (i > 0) Hairline()
                        SessionRow(s) { loadMessages(s) }
                    }
                }
                GroupCard {
                    RowCell("刷新", "重新拉一次列表") { loadAgents(devices) }
                }
            }

        }

        if (busy) {
            Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp))
            }
        }
    }
}

/** 消息页独占全屏：TopBar + 可滚动消息 + 底部输入框。不能用 PageScaffold（它的内容区可滚动，weight 没有高度）。 */
@Composable
private fun MessageScreen(
    title: String,
    onBack: () -> Unit,
    messages: List<RemoteApi.Message>,
    input: String,
    onInput: (String) -> Unit,
    canSend: Boolean,
    busy: Boolean,
    error: String?,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onVoice: () -> Unit,
    onPickFile: () -> Unit,
    picking: Boolean,
    attachmentCount: Int,
) {
    val c = MikaTheme.colors
    val state = rememberLazyListState()
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    // 键盘弹出时也滚到底，别让最后一条被键盘挡住
    LaunchedEffect(messages.size, imeBottom) {
        if (messages.isNotEmpty()) state.animateScrollToItem(messages.lastIndex)
    }
    PageBackground {
        // edge-to-edge 下 adjustResize 不会缩窗口，必须自己吃 IME inset，否则键盘盖住输入框
        Column(Modifier.fillMaxSize().imePadding()) {
            TopBar(title, MikaIcons.Back, onBack, "返回")
            error?.let {
                Text(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MikaType.Caption, color = c.bad)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state) {
                items(messages) { m -> MessageBubble(m) }
            }
            GlassBar(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 14.dp)) {
                Box(
                    Modifier.size(MikaSize.IconButton).clip(CircleShape)
                        .clickable(enabled = canSend && !picking, onClick = onVoice),
                    contentAlignment = Alignment.Center,
                ) { Text("🎤", fontSize = 17.sp, color = c.ink3) }
                Box(
                    Modifier.size(MikaSize.IconButton).clip(CircleShape)
                        .clickable(enabled = canSend && !picking, onClick = onPickFile),
                    contentAlignment = Alignment.Center,
                ) { Icon(MikaIcons.Plus, if (picking) "读取中" else "附件", Modifier.size(22.dp), tint = c.ink3) }
                Box(Modifier.weight(1f).padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
                    if (input.isEmpty()) {
                        Text(
                            if (canSend) "想让 vibelink 做什么？" else "这个 agent 只读",
                            style = MikaType.Body, color = c.ink3, maxLines = 1,
                        )
                    }
                    BasicTextField(
                        input, onInput,
                        Modifier.fillMaxWidth(),
                        enabled = canSend,
                        textStyle = MikaType.Body.copy(color = c.ink),
                        cursorBrush = SolidColor(c.accent),
                        maxLines = 5,
                    )
                }
                if (attachmentCount > 0) {
                    Text("+$attachmentCount", Modifier.padding(end = 4.dp), style = MikaType.Caption, color = c.accent)
                }
                if (busy) {
                    Box(
                        Modifier.size(MikaSize.IconButton).background(c.ink, CircleShape).clip(CircleShape)
                            .clickable(enabled = canSend, onClick = onStop),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MikaIcons.Stop, "停止", Modifier.size(24.dp), tint = c.bg)
                    }
                } else {
                    val ready = input.isNotBlank() && canSend
                    Box(
                        Modifier.size(MikaSize.IconButton)
                            .background(if (ready) c.accent else c.greySoft, CircleShape).clip(CircleShape)
                            .clickable(enabled = ready, onClick = onSend),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MikaIcons.Up, "发送", Modifier.size(20.dp), tint = if (ready) c.onAccent else c.ink3)
                    }
                }
            }
        }
    }
}
@Composable
private fun SetupForm(hub: String, token: String, busy: Boolean, onHub: (String) -> Unit, onToken: (String) -> Unit, onConnect: () -> Unit) {
    val c = MikaTheme.colors
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("填 hub 地址和 token（PC 上 data/token.txt）", Modifier.padding(top = 8.dp, bottom = 12.dp), style = MikaType.Caption, color = c.ink3)
        OutlinedTextField(hub, onHub, label = { Text("Hub") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
        OutlinedTextField(token, onToken, label = { Text("Token") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
        TextButton(onClick = onConnect, enabled = !busy) { Text("保存并连接") }
    }
}

/** agent 入口一行：名字 + 会话数，副标题按设备分布（pc 50 · laptop 50）。 */
@Composable
private fun AgentRow(g: RemoteApi.AgentGroup, devices: List<RemoteApi.Device>, onClick: () -> Unit) {
    val c = MikaTheme.colors
    val byDev = g.sessions.groupingBy { it.device }.eachCount()
    val subtitle = if (g.sessions.isEmpty()) {
        g.note.ifBlank { "暂无会话" }
    } else {
        byDev.entries.joinToString(" · ") { (dev, n) -> "${deviceName(devices, dev)} $n" }
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(g.name, fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        RowValue(if (g.sessions.isEmpty()) "—" else "${g.sessions.size}")
    }
}
/** 设备一行：名字 + 在线状态，副标题是 id / agent 版本。 */
@Composable
private fun DeviceRow(d: RemoteApi.Device, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.hostname, fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(d.id, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).background(if (d.online) c.accent else c.ink3, CircleShape))
            Text(if (d.online) "在线" else "离线", style = MikaType.Value, color = if (d.online) c.ink2 else c.ink3)
        }
    }
}

/** 会话一行：和主页对话列表一致 —— 标题 + 右侧时间，下面一行模型。 */
@Composable
private fun SessionRow(s: RemoteApi.Session, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val live = s.updated > 0 && System.currentTimeMillis() - s.updated < 10 * 60_000
            Box(
                Modifier.size(6.dp)
                    .background(if (live) c.accent else c.ink3.copy(alpha = 0.35f), CircleShape),
            )
            Text(
                s.title.ifBlank { "(无标题)" },
                Modifier.weight(1f).padding(start = 8.dp),
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Medium,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val stamp = stamp(s.updated)
            if (stamp.isNotEmpty()) {
                Text(stamp, Modifier.padding(start = 8.dp), style = MikaType.Value, color = c.ink3, maxLines = 1)
            }
        }
        val sub = if (s.model.isNotBlank()) s.model else s.cwd
        if (sub.isNotBlank()) {
            Text(sub, Modifier.padding(start = 14.dp, top = 2.dp), style = MikaType.Caption, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 通用的一行：标题 + 副标题 + 右侧说明。 */
@Composable
private fun RowCell(title: String, subtitle: String? = null, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = c.ink)
            if (!subtitle.isNullOrBlank()) Text(subtitle, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3)
        }
        RowValue("›")
    }
}

/** 轻量 markdown：标题、粗体、行内代码、列表、代码块。够手机看会话，不引第三方库。 */
private data class MdBlock(val kind: String, val text: String)

private fun parseBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.split('\n')
    var i = 0
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out.add(MdBlock("p", para.toString().trim()))
        para.setLength(0)
    }
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            flush()
            val lang = trimmed.removePrefix("```").trim()
            val body = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                body.appendLine(lines[i])
                i++
            }
            out.add(MdBlock("code", body.toString().trimEnd()))
            i++
            continue
        }
        when {
            trimmed.startsWith("### ") -> { flush(); out.add(MdBlock("h3", trimmed.removePrefix("### "))) }
            trimmed.startsWith("## ") -> { flush(); out.add(MdBlock("h2", trimmed.removePrefix("## "))) }
            trimmed.startsWith("# ") -> { flush(); out.add(MdBlock("h1", trimmed.removePrefix("# "))) }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> { flush(); out.add(MdBlock("li", trimmed.drop(2))) }
            trimmed.isEmpty() -> flush()
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
        }
        i++
    }
    flush()
    return out
}

/** 行内样式：**粗体** 和 `代码`。 */
@Composable
private fun inline(text: String): androidx.compose.ui.text.AnnotatedString {
    val bold = FontWeight.SemiBold
    val codeBg = MikaTheme.colors.fill
    return androidx.compose.ui.text.buildAnnotatedString {
        var idx = 0
        val regex = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
        for (m in regex.findAll(text)) {
            if (m.range.first > idx) append(text.substring(idx, m.range.first))
            val b = m.groups[1]
            val c = m.groups[2]
            if (b != null) {
                withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = bold)) { append(b.value) }
            } else if (c != null) {
                withStyle(
                    androidx.compose.ui.text.SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBg,
                        fontSize = 13.sp,
                    ),
                ) { append(c.value) }
            }
            idx = m.range.last + 1
        }
        if (idx < text.length) append(text.substring(idx))
    }
}

@Composable
private fun RichText(text: String, color: androidx.compose.ui.graphics.Color) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        blocks.forEach { b ->
            when (b.kind) {
                "code" -> Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MikaTheme.colors.bg.copy(alpha = 0.7f))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(b.text, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, color = color)
                }
                "h1" -> Text(inline(b.text), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = color)
                "h2" -> Text(inline(b.text), fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold, color = color)
                "h3" -> Text(inline(b.text), fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = color)
                "li" -> Row(Modifier.fillMaxWidth()) {
                    Text("•", Modifier.padding(end = 6.dp), fontSize = 14.5.sp, color = MikaTheme.colors.ink3)
                    Text(inline(b.text), fontSize = 14.5.sp, color = color)
                }
                else -> Text(inline(b.text), fontSize = 14.5.sp, color = color)
            }
        }
    }
}
/** 消息气泡：自己在右、助手在左，reasoning 用更淡的字放在上面。 */
/** 消息里的图片（data:image/...;base64）。解码放 IO 线程，失败就安静跳过。 */
@Composable
private fun RemoteImage(uri: String) {
    val ctx = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var bytes by remember(uri) { mutableStateOf<ByteArray?>(null) }
    var full by remember { mutableStateOf(false) }
    LaunchedEffect(uri) {
        val data = withContext(Dispatchers.IO) {
            runCatching {
                val b64 = uri.substringAfter("base64,", "")
                if (b64.isEmpty()) return@runCatching null
                val raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                if (raw.size > 12 * 1024 * 1024) return@runCatching null
                raw
            }.getOrNull()
        }
        bytes = data
        bmp = data?.let {
            withContext(Dispatchers.IO) {
                android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
            }
        }
    }
    bmp?.let { image ->
        Image(
            image,
            contentDescription = "图片",
            modifier = Modifier.padding(top = 6.dp).fillMaxWidth().heightIn(max = 260.dp)
                .clip(RoundedCornerShape(10.dp)).clickable { full = true },
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )
        if (full) {
            Dialog(
                onDismissRequest = { full = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                var scale by remember { mutableStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                Box(
                    Modifier.fillMaxSize().background(Color.Black)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                offset = if (scale <= 1f) Offset.Zero else offset + pan
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        image,
                        contentDescription = "图片",
                        modifier = Modifier.fillMaxWidth().graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                    Box(
                        Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)
                            .size(40.dp).clip(CircleShape).background(Color(0x66000000))
                            .clickable { full = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MikaIcons.Close, "关闭", Modifier.size(20.dp), tint = Color.White)
                    }
                    Row(
                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp)
                            .background(Color(0x99000000), RoundedCornerShape(24.dp))
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(onClick = {
                            val data = bytes ?: return@TextButton
                            val ok = saveImage(ctx, data)
                            Toast.makeText(ctx, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("保存", color = Color.White)
                        }
                        TextButton(onClick = {
                            val data = bytes ?: return@TextButton
                            shareImage(ctx, data)
                        }) {
                            Text("分享", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/** 写进系统相册 `Pictures/Mika`，Android 10+ 不需要存储权限。 */
private fun saveImage(context: android.content.Context, bytes: ByteArray): Boolean {
    val name = "mika-${System.currentTimeMillis()}.png"
    val values = android.content.ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_PICTURES}/Mika")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull()
        ?: return false
    val ok = runCatching {
        resolver.openOutputStream(uri)?.use { it.write(bytes) } != null
    }.getOrDefault(false)
    if (ok) {
        resolver.update(uri, android.content.ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    } else {
        runCatching { resolver.delete(uri, null, null) }
    }
    return ok
}

/** 写进 cache/attach/viewing（AttachFilesProvider 已覆盖），再走系统分享。 */
private fun shareImage(context: android.content.Context, bytes: ByteArray) {
    val dir = java.io.File(context.cacheDir, "attach/viewing").apply { mkdirs() }
    val file = java.io.File(dir, "mika-${System.currentTimeMillis()}.png")
    runCatching { file.writeBytes(bytes) }.onFailure { return }
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.attachfiles",
        file,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享图片"))
}
@Composable
internal fun MessageBubble(m: RemoteApi.Message, streaming: Boolean = false) {
    val c = MikaTheme.colors
    val mine = m.role == "user"
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(14.dp))
                .background(if (mine) c.accent.copy(alpha = 0.16f) else c.fill)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            if (!mine && (m.reasoning.isNotBlank() || streaming)) {
                ThinkingBlock(m, streaming)
                if (m.text.isNotBlank()) Spacer(Modifier.height(8.dp))
            }
            if (m.text.isNotBlank()) {
                RichText(m.text, c.ink)
            }
            m.images.forEach { RemoteImage(it) }
            val stamp = stamp(m.at)
            if (stamp.isNotEmpty()) {
                Text(stamp, Modifier.padding(top = 4.dp), style = MikaType.Hint, color = c.ink3)
            }
        }
    }
}

/**
 * 深度思考块：DeepSeek / ChatGPT 那种「思考中」可折叠区域。
 *
 * - 正在思考：图标旋转、标题做流光、右侧闪烁光标，内容按 token 一样吐出来；
 * - 答案开始/结束时自动收起，标题变成「已深度思考 · Ns」；
 * - 用户手动点过展开/收起后，不再自动动。
 */
@Composable
private fun ThinkingBlock(m: RemoteApi.Message, streaming: Boolean) {
    val c = MikaTheme.colors
    val target = m.reasoning
    if (target.isBlank() && !streaming) return

    var expanded by remember(m.id) { mutableStateOf(streaming) }
    var manual by remember(m.id) { mutableStateOf(false) }
    var shown by remember(m.id) { mutableIntStateOf(if (streaming) 0 else target.length) }
    val scroll = rememberScrollState()

    // 新到的思考内容用 450ms 走完，看起来像 token 流，而不是每 4 秒蹦一大段
    LaunchedEffect(target.length, streaming) {
        if (!streaming) {
            shown = target.length
            return@LaunchedEffect
        }
        val from = shown
        val to = target.length
        if (to <= from) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (shown < to) {
            withFrameNanos { now ->
                val t = ((now - start) / 1_000_000f / 450f).coerceIn(0f, 1f)
                shown = from + ((to - from) * t).toInt()
            }
        }
        shown = to
    }
    LaunchedEffect(shown, expanded) {
        if (expanded) scroll.scrollTo(scroll.maxValue)
    }
    // 答案开始了就自动收起，除非用户自己点过
    LaunchedEffect(streaming, m.text.isNotBlank()) {
        if (!streaming && !manual && m.text.isNotBlank()) expanded = false
    }

    val seconds = if (m.started > 0 && m.at > m.started) ((m.at - m.started) / 1000) else 0
    val title = when {
        streaming -> "深度思考中"
        seconds > 0 -> "已深度思考 · ${seconds}s"
        else -> "已深度思考"
    }

    val anim = rememberInfiniteTransition(label = "think")
    val sweep by anim.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    val spin by anim.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    val caret by anim.animateFloat(
        0.15f, 1f,
        infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse),
        label = "caret",
    )
    val shine = Brush.linearGradient(
        colors = listOf(c.ink3, c.ink, c.ink3),
        start = Offset(sweep * 700f - 700f, 0f),
        end = Offset(sweep * 700f, 0f),
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.neutralSoft)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    manual = true
                    expanded = !expanded
                }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                MikaIcons.Spark,
                null,
                Modifier.size(14.dp).graphicsLayer { rotationZ = if (streaming) spin else 0f },
                tint = if (streaming) c.accent else c.ink3,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                title,
                style = if (streaming) MikaType.Caption.copy(brush = shine) else MikaType.Caption,
                color = c.ink3,
                maxLines = 1,
            )
            if (streaming) {
                Text("▍", style = MikaType.Caption, color = c.ink3.copy(alpha = caret))
            }
            Spacer(Modifier.weight(1f))
            Text(if (expanded) "收起" else "展开", style = MikaType.Hint, color = c.ink3)
            Icon(
                if (expanded) MikaIcons.Up else MikaIcons.Chevron,
                null,
                Modifier.size(12.dp),
                tint = c.ink3,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(scroll)
                    .padding(top = 4.dp),
            ) {
                val visibleText = target.take(shown)
                Text(visibleText, style = MikaType.Caption, color = c.ink2)
                if (streaming && shown < target.length) {
                    Text("▍", style = MikaType.Caption, color = c.ink3.copy(alpha = caret))
                }
            }
        }
    }
}

private val stampFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
private fun stamp(ms: Long): String = if (ms <= 0) "" else stampFmt.format(Date(ms))

private fun deviceName(devices: List<RemoteApi.Device>, id: String): String =
    devices.firstOrNull { it.id == id }?.hostname ?: id





















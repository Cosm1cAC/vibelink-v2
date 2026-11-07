package com.wangzan101.vibelink.remote

import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.wangzan101.vibelink.ui.BotTeamStore
import kotlinx.coroutines.delay
import kotlin.math.sin
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.wangzan101.vibelink.ui.theme.VibeLinkSize
import com.wangzan101.vibelink.ui.components.GlassBar
import com.wangzan101.vibelink.ui.components.GlassScrollToBottomButton
import com.wangzan101.vibelink.ui.components.glassSource
import com.wangzan101.vibelink.ui.components.rememberGlassBackdrop
import com.wangzan101.vibelink.ui.components.rememberScrollToBottomState
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.vibelink.ui.components.GroupCard
import com.wangzan101.vibelink.ui.components.GroupLabel
import com.wangzan101.vibelink.ui.components.Hairline
import com.wangzan101.vibelink.ui.components.PageBackground
import com.wangzan101.vibelink.ui.components.PageScaffold
import com.wangzan101.vibelink.ui.components.RowValue
import com.wangzan101.vibelink.ui.components.TopBar
import com.wangzan101.vibelink.ui.theme.VibeLinkIcons
import com.wangzan101.vibelink.ui.theme.VibeLinkTheme
import com.wangzan101.vibelink.ui.theme.VibeLinkType
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
    val c = VibeLinkTheme.colors

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
            input = BotTeamStore.getDraftText(ctx, "remote:${s.id}")
            runCatching { withContext(Dispatchers.IO) { RemoteApi.messages(hub, token, s.device, s.agent, s.id) } }
                .onSuccess { messages = it; stage = 3 }
                .onFailure(::fail)
            busy = false
        }
    }

    LaunchedEffect(Unit) { if (token.isNotBlank()) loadDevices() }
    // 系统相机扫到 vibelink://pair 拉起 VibeLink：不管当前在哪一层，都回到连接页让 PairSetup 弹确认
    val inboxLink by PairInbox.pending.collectAsState()
    LaunchedEffect(inboxLink) { if (inboxLink != null) stage = 0 }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    val title = when (stage) {
        0 -> "连接 VibeLink"
        1 -> "远程会话"
        2 -> agent?.name ?: "会话"
        else -> session?.title?.ifBlank { "会话" } ?: "会话"
    }

    if (stage == 3) {
        val currentSid = session?.id.orEmpty()
        MessageScreen(
            title = title,
            onBack = { stage = 2 },
            messages = messages,
            input = input,
            onInput = {
                input = it
                if (currentSid.isNotBlank()) {
                    BotTeamStore.setDraftText(ctx, "remote:$currentSid", it)
                }
            },
            canSend = true,
            busy = busy,
            error = error,
            onSend = {
                val text = input.trim()
                val s = session
                if (s != null && text.isNotEmpty()) {
                    input = ""
                    BotTeamStore.clearDraft(ctx, "remote:${s.id}")
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
            Text(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = VibeLinkType.Caption, color = c.bad)
        }

        when (stage) {
            0 -> PairSetup(
                hub, onHub = { hub = it },
                token = token,
                onToken = { token = it },
                onManualConnect = {
                    RemoteStore.save(ctx, hub, token)
                    com.wangzan101.vibelink.settings.Preconfigure.onPaired(ctx)
                    com.wangzan101.vibelink.clash.ClashSeed.reseed(ctx)
                    loadDevices()
                },
                onPaired = { h, t, link, host ->
                    hub = h
                    token = t
                    RemoteStore.savePaired(ctx, h, t, link, host)
                    com.wangzan101.vibelink.settings.Preconfigure.onPaired(ctx)
                    com.wangzan101.vibelink.clash.ClashSeed.reseed(ctx)
                    Toast.makeText(ctx, "已连接 ${host.ifBlank { "电脑" }}", Toast.LENGTH_SHORT).show()
                    stage = 1
                    loadDevices()
                },
            )

            1 -> {
                GroupLabel("智能体")
                GroupCard {
                    if (agents.isEmpty() && !busy) {
                        Text("没有可用的会话", Modifier.padding(16.dp), style = VibeLinkType.Caption, color = c.ink3)
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
                    RowCell("扫码重新连接", RemoteStore.hostName(ctx).ifBlank { RemoteStore.hub(ctx) }) { stage = 0 }
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
                            Modifier.padding(16.dp), style = VibeLinkType.Caption, color = c.ink3,
                        )
                    }
                    var lastDev = ""
                    list.forEachIndexed { i, s ->
                        if (s.device != lastDev) {
                            if (i > 0) Hairline()
                            Text(
                                deviceName(devices, s.device),
                                Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                                style = VibeLinkType.Caption, color = c.ink3,
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
    val c = VibeLinkTheme.colors
    val scope = rememberCoroutineScope()
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = (messages.size - 1).coerceAtLeast(0),
        initialFirstVisibleItemScrollOffset = 100_000,
    )
    var stick by remember { mutableStateOf(true) }
    var hasUserScrolledUp by remember { mutableStateOf(false) }
    val glassBackdrop = rememberGlassBackdrop()
    val scrollToBottomState = rememberScrollToBottomState(state)
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)

    suspend fun scrollToBottomImmediate() {
        scrollToBottomState.hideImmediate()
        if (messages.isEmpty()) return
        hasUserScrolledUp = false
        stick = true
        val targetIdx = (messages.size - 1).coerceAtLeast(0)
        runCatching {
            state.scrollToItem(targetIdx, scrollOffset = 100_000)
            withFrameNanos { }
            state.scrollToItem(targetIdx, scrollOffset = 100_000)
        }
    }

    LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow {
            Triple(
                state.isScrollInProgress,
                state.canScrollForward,
                state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
            )
        }.collect { (scrolling, canForward, lastIdx) ->
            if (!canForward) {
                hasUserScrolledUp = false
                stick = true
            } else if (scrolling) {
                val total = state.layoutInfo.totalItemsCount
                stick = total > 0 && lastIdx >= total - 2 && !canForward
                hasUserScrolledUp = true
            }
        }
    }

    // 键盘弹出或消息更新时滚到底，别让最后一条被键盘挡住
    LaunchedEffect(messages.size, messages.lastOrNull()?.id, messages.lastOrNull()?.text?.length, imeBottom) {
        if (messages.isNotEmpty() && stick) {
            scrollToBottomImmediate()
        }
    }
    PageBackground {
        // edge-to-edge 下 adjustResize 不会缩窗口，必须自己吃 IME inset，否则键盘盖住输入框
        Column(Modifier.fillMaxSize().imePadding()) {
            TopBar(title, VibeLinkIcons.Back, onBack, "返回")
            error?.let {
                Text(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = VibeLinkType.Caption, color = c.bad)
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(scrollToBottomState.nestedScrollConnection),
            ) {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .glassSource(glassBackdrop, c.bg),
                    state = state,
                ) {
                    items(messages) { m -> MessageBubble(m) }
                }
                GlassScrollToBottomButton(
                    state = scrollToBottomState,
                    backdrop = glassBackdrop,
                    hasMessages = messages.isNotEmpty(),
                    onClick = { scope.launch { scrollToBottomImmediate() } },
                )
            }
            GlassBar(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 14.dp)) {
                Box(
                    Modifier.size(VibeLinkSize.IconButton).clip(CircleShape)
                        .clickable(enabled = canSend && !picking, onClick = onVoice),
                    contentAlignment = Alignment.Center,
                ) { Text("🎤", fontSize = 17.sp, color = c.ink3) }
                Box(
                    Modifier.size(VibeLinkSize.IconButton).clip(CircleShape)
                        .clickable(enabled = canSend && !picking, onClick = onPickFile),
                    contentAlignment = Alignment.Center,
                ) { Icon(VibeLinkIcons.Plus, if (picking) "读取中" else "附件", Modifier.size(22.dp), tint = c.ink3) }
                Box(Modifier.weight(1f).padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
                    if (input.isEmpty()) {
                        Text(
                            if (canSend) "想让 VibeLink 做什么？" else "这个 agent 只读",
                            style = VibeLinkType.Body, color = c.ink3, maxLines = 1,
                        )
                    }
                    BasicTextField(
                        input, onInput,
                        Modifier.fillMaxWidth(),
                        enabled = canSend,
                        textStyle = VibeLinkType.Body.copy(color = c.ink),
                        cursorBrush = SolidColor(c.accent),
                        maxLines = 5,
                    )
                }
                if (attachmentCount > 0) {
                    Text("+$attachmentCount", Modifier.padding(end = 4.dp), style = VibeLinkType.Caption, color = c.accent)
                }
                val ready = input.isNotBlank() && canSend
                if (busy) {
                    Box(
                        Modifier.padding(end = if (ready) 6.dp else 0.dp)
                            .size(VibeLinkSize.IconButton).background(c.ink, CircleShape).clip(CircleShape)
                            .clickable(enabled = canSend, onClick = onStop),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(VibeLinkIcons.Stop, "打断当前任务", Modifier.size(24.dp), tint = c.bg)
                    }
                }
                if (!busy || ready) {
                    Box(
                        Modifier.size(VibeLinkSize.IconButton)
                            .background(if (ready) c.accent else c.greySoft, CircleShape).clip(CircleShape)
                            .clickable(enabled = ready, onClick = onSend),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(VibeLinkIcons.Up, if (busy) "排队发送" else "发送", Modifier.size(20.dp), tint = if (ready) c.onAccent else c.ink3)
                    }
                }
            }
        }
    }
}
/** agent 入口一行：名字 + 会话数，副标题按设备分布（pc 50 · laptop 50）。 */
@Composable
private fun AgentRow(g: RemoteApi.AgentGroup, devices: List<RemoteApi.Device>, onClick: () -> Unit) {
    val c = VibeLinkTheme.colors
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
            Text(subtitle, Modifier.padding(top = 2.dp), style = VibeLinkType.Caption, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        RowValue(if (g.sessions.isEmpty()) "—" else "${g.sessions.size}")
    }
}
/** 设备一行：名字 + 在线状态，副标题是 id / agent 版本。 */
@Composable
private fun DeviceRow(d: RemoteApi.Device, onClick: () -> Unit) {
    val c = VibeLinkTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.hostname, fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(d.id, Modifier.padding(top = 2.dp), style = VibeLinkType.Caption, color = c.ink3)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).background(if (d.online) c.accent else c.ink3, CircleShape))
            Text(if (d.online) "在线" else "离线", style = VibeLinkType.Value, color = if (d.online) c.ink2 else c.ink3)
        }
    }
}

/** 会话一行：和主页对话列表一致 —— 标题 + 右侧时间，下面一行模型。 */
@Composable
private fun SessionRow(s: RemoteApi.Session, onClick: () -> Unit) {
    val c = VibeLinkTheme.colors
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
                Text(stamp, Modifier.padding(start = 8.dp), style = VibeLinkType.Value, color = c.ink3, maxLines = 1)
            }
        }
        val sub = if (s.model.isNotBlank()) s.model else s.cwd
        if (sub.isNotBlank()) {
            Text(sub, Modifier.padding(start = 14.dp, top = 2.dp), style = VibeLinkType.Caption, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 通用的一行：标题 + 副标题 + 右侧说明。 */
@Composable
private fun RowCell(title: String, subtitle: String? = null, onClick: () -> Unit) {
    val c = VibeLinkTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = c.ink)
            if (!subtitle.isNullOrBlank()) Text(subtitle, Modifier.padding(top = 2.dp), style = VibeLinkType.Caption, color = c.ink3)
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
    val codeBg = VibeLinkTheme.colors.fill
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
internal fun RichText(text: String, color: androidx.compose.ui.graphics.Color) {
    val c = VibeLinkTheme.colors
    val ctx = LocalContext.current
    val blocks = remember(text) { parseBlocks(text) }
    val selectionColors = remember(c.accent) {
        TextSelectionColors(
            handleColor = c.accent,
            backgroundColor = c.accent.copy(alpha = 0.28f),
        )
    }
    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                blocks.forEach { b ->
                    when (b.kind) {
                        "code" -> Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(c.bg.copy(alpha = 0.7f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            DisableSelection {
                                Row(
                                    Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    Text(
                                        "📋 复制代码",
                                        style = VibeLinkType.Hint,
                                        color = c.accent,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable {
                                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                                    as android.content.ClipboardManager
                                                cm.setPrimaryClip(android.content.ClipData.newPlainText("code", b.text))
                                                Toast.makeText(ctx, "已复制代码段", Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(horizontal = 4.dp, vertical = 1.dp),
                                    )
                                }
                            }
                            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                Text(b.text, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, color = color)
                            }
                        }
                        "h1" -> Text(inline(b.text), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = color)
                        "h2" -> Text(inline(b.text), fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold, color = color)
                        "h3" -> Text(inline(b.text), fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = color)
                        "li" -> Row(Modifier.fillMaxWidth()) {
                            DisableSelection {
                                Text("•", Modifier.padding(end = 6.dp), fontSize = 14.5.sp, color = c.ink3)
                            }
                            Text(inline(b.text), fontSize = 14.5.sp, color = color)
                        }
                        else -> Text(inline(b.text), fontSize = 14.5.sp, color = color)
                    }
                }
            }
        }
    }
}

@Composable
internal fun TextExcerptDialog(
    fullText: String,
    reasoningText: String = "",
    onDismiss: () -> Unit,
) {
    val c = VibeLinkTheme.colors
    val ctx = LocalContext.current
    var editableExcerpt by remember(fullText) { mutableStateOf(fullText) }
    val segments = remember(fullText) {
        fullText.split(Regex("\\n{2,}")).map { it.trim() }.filter { it.length >= 2 }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 620.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(c.surface)
                .border(0.5.dp, c.edge, RoundedCornerShape(18.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("✂️ 自由选词与片段截取", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (reasoningText.isNotBlank()) {
                        Text(
                            "💭 复制思考链",
                            fontSize = 12.sp,
                            color = c.accent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(c.accent.copy(alpha = 0.12f))
                                .clickable {
                                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("reasoning", reasoningText))
                                    Toast.makeText(ctx, "已复制完整思考过程", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                    Text(
                        "📋 复制全文",
                        fontSize = 12.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.accent)
                            .clickable {
                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("message", fullText))
                                Toast.makeText(ctx, "已复制全文", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            Text(
                "提示：在下方文本框内长按即可自由拖动水滴光标选中任意特定字词复制，或直接点击下方拆解好的段落一键复制：",
                style = VibeLinkType.Hint,
                color = c.ink3,
            )
            OutlinedTextField(
                value = editableExcerpt,
                onValueChange = { editableExcerpt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 130.dp, max = 230.dp),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 13.5.sp,
                    color = c.ink,
                ),
            )
            if (segments.size > 1) {
                Text("按段落快速截取：", style = VibeLinkType.Caption, fontWeight = FontWeight.SemiBold, color = c.ink2)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    segments.forEachIndexed { idx, seg ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(c.fill)
                                .clickable {
                                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("segment", seg))
                                    Toast.makeText(ctx, "已复制第 ${idx + 1} 段", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                seg,
                                fontSize = 12.5.sp,
                                color = c.ink,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text("复制", fontSize = 11.5.sp, color = c.accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text("关闭", color = c.ink2)
                }
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
                        Icon(VibeLinkIcons.Close, "关闭", Modifier.size(20.dp), tint = Color.White)
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

/** 写进系统相册 `Pictures/VibeLink`，Android 10+ 不需要存储权限。 */
private fun saveImage(context: android.content.Context, bytes: ByteArray): Boolean {
    val name = "vibelink-${System.currentTimeMillis()}.png"
    val values = android.content.ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_PICTURES}/VibeLink")
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
    val file = java.io.File(dir, "vibelink-${System.currentTimeMillis()}.png")
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    m: RemoteApi.Message,
    streaming: Boolean = false,
    onOpenRemoteFile: ((String) -> Unit)? = null,
    showApprovalChips: Boolean = false,
    onQuickReply: ((String) -> Unit)? = null,
) {
    val c = VibeLinkTheme.colors
    val mine = m.role == "user"
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var showExcerptDialog by remember { mutableStateOf(false) }
    val needsConfirm = remember(m.text, showApprovalChips, mine, streaming) {
        if (mine || streaming || m.text.isBlank()) false
        else if (showApprovalChips) true
        else {
            val t = m.text.trimEnd()
            t.contains("请确认") || t.contains("是否继续") || t.contains("是否批准") ||
                t.contains("需要你的确认") || t.contains("Proceed") ||
                ((t.endsWith("?") || t.endsWith("？")) && (t.contains("方案") || t.contains("计划") || t.contains("执行")))
        }
    }
    val pureThinkingStream = !mine && streaming && m.text.isBlank() && m.images.isEmpty() && m.files.isEmpty()
    if (showExcerptDialog && (m.text.isNotBlank() || m.reasoning.isNotBlank())) {
        TextExcerptDialog(
            fullText = m.text.ifBlank { m.reasoning },
            reasoningText = if (m.text.isNotBlank()) m.reasoning else "",
            onDismiss = { showExcerptDialog = false },
        )
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Column(
            (if (mine) Modifier.widthIn(max = 285.dp) else Modifier.fillMaxWidth(0.96f))
                .clip(RoundedCornerShape(14.dp))
                .background(
                    when {
                        mine -> c.accent.copy(alpha = 0.16f)
                        pureThinkingStream -> Color.Transparent
                        else -> c.fill
                    },
                )
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        if (m.text.isNotBlank() || m.reasoning.isNotBlank()) {
                            showExcerptDialog = true
                        }
                    },
                )
                .padding(
                    horizontal = if (pureThinkingStream) 4.dp else 13.dp,
                    vertical = if (pureThinkingStream) 6.dp else 10.dp,
                ),
        ) {
            // DeepSeek 手机端风格：将整轮思考过程与所有工具调用（read/edit/cmd/diff）统一收纳在顶部折叠区，默认收起
            if (!mine && (m.reasoning.isNotBlank() || m.tools.isNotEmpty() || streaming)) {
                ThinkingBlock(m = m, streaming = streaming, onOpenRemoteFile = onOpenRemoteFile)
                if (m.text.isNotBlank()) Spacer(Modifier.height(8.dp))
            }
            if (m.text.isNotBlank()) {
                RichText(m.text, c.ink)
            }
            m.images.forEach { RemoteImage(it) }
            if (m.files.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    m.files.forEach { f ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(c.surface)
                                .border(0.5.dp, c.edge, RoundedCornerShape(8.dp))
                                .clickable { onOpenRemoteFile?.invoke(f.path) }
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("📄", style = VibeLinkType.Caption)
                            Text(
                                f.name,
                                style = VibeLinkType.Caption,
                                color = c.accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 175.dp),
                            )
                            Text("预览/下载", style = VibeLinkType.Hint, color = c.ink3, softWrap = false, maxLines = 1)
                        }
                    }
                }
            }
            if (needsConfirm && onQuickReply != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(
                        "✅ 允许并继续" to "已确认，请继续执行",
                        "📋 批准计划并执行" to "/plan 确认，请按计划开始执行",
                        "🔄 重新规划" to "请根据上下文重新调整方案后再执行",
                    ).forEach { (label, replyText) ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(c.accent.copy(alpha = 0.14f))
                                .border(0.5.dp, c.accent.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                .clickable { onQuickReply(replyText) }
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                        ) {
                            Text(label, style = VibeLinkType.Caption, color = c.accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            val stamp = stamp(m.at)
            if (stamp.isNotEmpty() || (!streaming && m.text.isNotBlank())) {
                val ttsScope = rememberCoroutineScope()
                Row(
                    Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (stamp.isNotEmpty()) {
                        Text(stamp, style = VibeLinkType.Hint, color = c.ink3)
                    }
                    if (!streaming && m.text.isNotBlank()) {
                        Text(
                            "📋 复制",
                            style = VibeLinkType.Hint,
                            color = c.ink2,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("message", m.text))
                                    android.widget.Toast.makeText(ctx, "已复制全文", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                        Text(
                            "✂️ 选词截取",
                            style = VibeLinkType.Hint,
                            color = c.accent,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { showExcerptDialog = true }
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                    if (!mine && !streaming && m.text.isNotBlank()) {
                        Text(
                            "🔊 朗读",
                            style = VibeLinkType.Hint,
                            color = c.accent,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    ttsScope.launch {
                                        com.wangzan101.vibelink.ui.VoiceTtsPlayer.speakText(ctx, m.text)
                                    }
                                }
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 展开态内部单条工具调用项（默认折叠代码 Diff 与命令行详情，仅在用户主动点击「详情/变更」时展开）。
 */
@Composable
private fun CompactToolStepRow(
    tc: RemoteApi.ToolCall,
    onOpenRemoteFile: ((String) -> Unit)?,
) {
    val c = VibeLinkTheme.colors
    var detailExpanded by remember { mutableStateOf(false) }
    val hasDetail = tc.diff.isNotBlank() || tc.command.isNotBlank()
    val icon = when {
        tc.name.contains("bash", ignoreCase = true) || tc.name.contains("cmd", ignoreCase = true) || tc.command.isNotBlank() -> "💻"
        tc.name.contains("edit", ignoreCase = true) || tc.diff.isNotBlank() -> "✏️"
        tc.name.contains("write", ignoreCase = true) -> "📝"
        tc.name.contains("read", ignoreCase = true) -> "📖"
        else -> "🔧"
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = hasDetail) { detailExpanded = !detailExpanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("•", fontSize = 13.sp, color = c.ink3)
            Text(icon, fontSize = 12.sp)
            Text(
                tc.title,
                style = VibeLinkType.Mono.copy(fontSize = 12.5.sp),
                color = c.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (tc.file.isNotBlank() && onOpenRemoteFile != null) {
                Text(
                    "打开文件",
                    style = VibeLinkType.Hint,
                    color = c.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onOpenRemoteFile(tc.file) }
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            if (hasDetail) {
                Text(
                    if (detailExpanded) "收起" else if (tc.diff.isNotBlank()) "代码变更 >" else "详情 >",
                    style = VibeLinkType.Hint,
                    color = c.ink3,
                )
            }
        }
        AnimatedVisibility(visible = detailExpanded && hasDetail) {
            Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (tc.command.isNotBlank()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF14171F))
                            .padding(8.dp),
                    ) {
                        Text(tc.command, style = VibeLinkType.Mono, color = Color(0xFFE2E8F0))
                    }
                }
                if (tc.diff.isNotBlank()) {
                    com.wangzan101.vibelink.ui.DiffCodeBox(diff = tc.diff, maxLines = 60)
                }
            }
        }
    }
}

private val thinkingStartMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
private val thinkingDurationMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
@Volatile
private var lastLocalStreamStartMs: Long = 0L

private fun formatThinkingDuration(sec: Long): String {
    val s = sec.coerceAtLeast(1L)
    return if (s < 60L) {
        "${s} 秒"
    } else {
        val m = s / 60L
        val r = s % 60L
        if (r == 0L) "${m} 分钟" else "${m} 分 ${r} 秒"
    }
}

private fun formatLiveStopwatch(sec: Long): String {
    val s = sec.coerceAtLeast(1L)
    return if (s < 60L) "${s}s" else "${s / 60}m ${s % 60}s"
}

/**
 * 动态双环星轨 + 4 柱声波脉冲思考动画组件（替代原本单一的静态文字）
 */
@Composable
private fun ThinkingNeuralOrbAndWave(accent: Color) {
    val infinite = rememberInfiniteTransition(label = "thinking_neural")
    val outerAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "outer_angle",
    )
    val innerAngle by infinite.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "inner_angle",
    )
    val wavePhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 950, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wave_phase",
    )
    val coreScale by infinite.animateFloat(
        initialValue = 0.65f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 760, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "core_scale",
    )
    val secondaryColor = Color(0xFF8B5CF6)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 双环反向星轨 + 呼吸核心光球
        Canvas(Modifier.size(18.dp)) {
            val strokeOuter = 1.8.dp.toPx()
            val strokeInner = 1.5.dp.toPx()
            drawCircle(
                color = accent.copy(alpha = 0.14f),
                radius = size.minDimension / 2f,
            )
            drawArc(
                color = accent,
                startAngle = outerAngle,
                sweepAngle = 210f,
                useCenter = false,
                style = Stroke(width = strokeOuter, cap = StrokeCap.Round),
            )
            val inset = 3.2.dp.toPx()
            drawArc(
                color = secondaryColor,
                startAngle = innerAngle,
                sweepAngle = 150f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                style = Stroke(width = strokeInner, cap = StrokeCap.Round),
            )
            drawCircle(
                color = accent,
                radius = 2.4.dp.toPx() * coreScale,
            )
        }
        // 4 柱错相律动波纹条
        Canvas(Modifier.width(15.dp).height(12.dp)) {
            val barCount = 4
            val barW = 2.2.dp.toPx()
            val gap = (size.width - barW * barCount) / (barCount - 1).coerceAtLeast(1)
            for (i in 0 until barCount) {
                val ratio = ((sin(wavePhase.toDouble() + i * 0.95) + 1.0) / 2.0).toFloat()
                val h = (size.height * (0.28f + 0.72f * ratio)).coerceAtLeast(2.5.dp.toPx())
                val x = i * (barW + gap)
                val y = (size.height - h) / 2f
                drawRoundRect(
                    color = if (i % 2 == 0) accent else secondaryColor.copy(alpha = 0.85f),
                    topLeft = Offset(x, y),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                )
            }
        }
    }
}

/**
 * DeepSeek 手机端风格的统一「思考 + 工具调用」折叠区：
 *
 * - 正在思考/执行工具（无正文时）：显示双环星轨与律动波纹动画 + 实时跳秒计时器 + 阶段状态提示，下方配左侧竖线与自动滚动小窗预览；
 * - 思考完成或正文开始后：默认自动收起为极简一行「已思考 (用时 N 秒) >」，隐藏全部思考文本、工具调用与代码 Diff；
 * - 用户点击展开后：变为「已思考 (用时 N 秒) ⌄」，下方通过左侧竖线 + 圆点列表展示思考要点与单行工具记录，支持长按自由选词复制。
 */
@Composable
internal fun ThinkingBlock(
    m: RemoteApi.Message,
    streaming: Boolean,
    onOpenRemoteFile: ((String) -> Unit)? = null,
) {
    val c = VibeLinkTheme.colors
    val target = remember(m.reasoning, m.tools, streaming) {
        when {
            m.reasoning.isNotBlank() -> m.reasoning.trim()
            m.tools.isNotEmpty() -> m.tools.mapIndexed { idx, tc ->
                "${idx + 1}. 正在调用工具：${tc.title.ifBlank { tc.name }}"
            }.joinToString("\n")
            streaming -> "正在分析上下文与制定执行方案…"
            else -> ""
        }
    }
    if (target.isBlank() && m.tools.isEmpty() && !streaming) return

    val activelyThinking = streaming && m.text.isBlank()
    var expanded by remember(m.id) { mutableStateOf(activelyThinking) }
    var manual by remember(m.id) { mutableStateOf(false) }
    var toolsExpanded by remember(m.id) { mutableStateOf(false) }
    var shown by remember(m.id) {
        mutableIntStateOf(if (streaming) (target.length - 140).coerceAtLeast(0) else target.length)
    }
    val scroll = rememberScrollState()

    val normStarted = remember(m.started) {
        if (m.started in 1L..9_999_999_999L) m.started * 1000L else m.started
    }
    val normAt = remember(m.at) {
        if (m.at in 1L..9_999_999_999L) m.at * 1000L else m.at
    }
    val baseStartMs = remember(m.id, normStarted, streaming) {
        val now = System.currentTimeMillis()
        if (m.id.startsWith("local-assistant-") || m.id.startsWith("msg_")) {
            val initStart = if (normStarted > 0L && now - normStarted in 0L..3_600_000L) normStarted else now
            val s = thinkingStartMap.getOrPut(m.id) { initStart }
            lastLocalStreamStartMs = s
            s
        } else {
            val cached = thinkingStartMap.getOrPut(m.id) {
                when {
                    streaming && lastLocalStreamStartMs > 0L && now - lastLocalStreamStartMs in 0L..180_000L ->
                        lastLocalStreamStartMs
                    normStarted > 0L && now - normStarted in 0L..3_600_000L && (normAt == 0L || normStarted < normAt) ->
                        normStarted
                    else -> now
                }
            }
            if (normStarted > 0L && normStarted < cached && now - normStarted in 0L..3_600_000L && (normAt == 0L || normStarted < normAt)) {
                thinkingStartMap[m.id] = normStarted
                normStarted
            } else {
                cached
            }
        }
    }
    var liveElapsedSec by remember(m.id) {
        val initial = thinkingDurationMap[m.id]
            ?: ((System.currentTimeMillis() - baseStartMs) / 1000L).coerceIn(1L, 3600L)
        mutableLongStateOf(initial)
    }
    LaunchedEffect(m.id, streaming, baseStartMs) {
        if (!streaming) return@LaunchedEffect
        while (true) {
            val elapsed = ((System.currentTimeMillis() - baseStartMs) / 1000L).coerceAtLeast(1L)
            liveElapsedSec = elapsed
            thinkingDurationMap[m.id] = elapsed
            thinkingDurationMap["__last_active__"] = elapsed
            delay(250L)
        }
    }

    LaunchedEffect(target, streaming) {
        if (!streaming) {
            shown = target.length
            return@LaunchedEffect
        }
        if (target.length < shown) {
            shown = 0
        }
        val from = shown
        val to = target.length
        if (to <= from) return@LaunchedEffect
        val start = withFrameNanos { it }
        val durationMs = ((to - from) * 6f).coerceIn(180f, 520f)
        while (shown < to) {
            withFrameNanos { now ->
                val t = ((now - start) / 1_000_000f / durationMs).coerceIn(0f, 1f)
                shown = from + ((to - from) * t).toInt()
            }
        }
        shown = to
    }
    LaunchedEffect(shown, scroll.maxValue, activelyThinking, expanded) {
        if (activelyThinking && expanded && !toolsExpanded && scroll.maxValue > 0) {
            scroll.scrollTo(scroll.maxValue)
        }
    }
    // 实时同步展开/折叠状态：正在思考时自动展开实时思考流；思考完成或开始输出最终回答时自动收起（除非用户手动点过）
    LaunchedEffect(activelyThinking) {
        if (!manual) {
            expanded = activelyThinking
        }
    }

    val serverDiffSec = if (normStarted > 0L && normAt > normStarted) ((normAt - normStarted) / 1000L) else 0L
    val recordedSec = remember(m.id, streaming, normAt, liveElapsedSec) {
        val direct = thinkingDurationMap[m.id]
        if (direct != null && direct >= 1L) {
            direct
        } else if (!streaming && normAt > 0L && System.currentTimeMillis() - normAt in 0L..20_000L) {
            val fallback = thinkingDurationMap["__last_active__"] ?: 0L
            if (fallback >= 1L) {
                thinkingDurationMap[m.id] = fallback
            }
            fallback
        } else {
            0L
        }
    }
    val estSec = ((target.length / 180) + m.tools.size * 2).coerceAtLeast(1).toLong()
    val completedSeconds = when {
        recordedSec >= 1L && serverDiffSec in 1L..3600L -> maxOf(recordedSec, serverDiffSec)
        recordedSec >= 1L -> recordedSec
        serverDiffSec in 1L..3600L -> serverDiffSec
        else -> estSec
    }

    val phaseHints = remember {
        listOf("正在拆解意图", "正在检索上下文", "正在推演方案", "正在深度思考")
    }
    val phaseLabel = if (m.tools.isNotEmpty()) {
        val lastTool = m.tools.last().title.ifBlank { m.tools.last().name }.take(18)
        "正在执行第 ${m.tools.size} 步 · $lastTool"
    } else {
        phaseHints[((liveElapsedSec / 3L) % phaseHints.size).toInt()]
    }

    val title = when {
        streaming -> phaseLabel
        else -> "已思考 (用时 ${formatThinkingDuration(completedSeconds)})"
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    manual = true
                    expanded = !expanded
                }
                .padding(vertical = 4.dp, horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (streaming) {
                ThinkingNeuralOrbAndWave(accent = c.accent)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title,
                fontSize = if (streaming) 14.5.sp else 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (streaming) c.ink else c.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (streaming) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(c.accent.copy(alpha = 0.12f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        "⏱ ${formatLiveStopwatch(liveElapsedSec)}",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.accent,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            if (activelyThinking) {
                Icon(
                    VibeLinkIcons.ChevronUpDown,
                    contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                    modifier = Modifier.size(15.dp),
                    tint = c.ink3,
                )
            } else {
                Icon(
                    VibeLinkIcons.Chevron,
                    contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                    modifier = Modifier
                        .size(13.dp)
                        .graphicsLayer { rotationZ = if (expanded) 90f else 0f },
                    tint = c.ink3,
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            val guideColor = c.ink3.copy(alpha = 0.28f)
            val thumbColor = c.ink3.copy(alpha = 0.38f)
            val containerMod = if (activelyThinking) {
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .heightIn(max = 185.dp)
                    .drawWithContent {
                        drawContent()
                        val maxScroll = scroll.maxValue.toFloat()
                        if (maxScroll > 4f && size.height > 0f) {
                            val totalH = size.height + maxScroll
                            val thumbH = (size.height * (size.height / totalH)).coerceIn(20.dp.toPx(), size.height)
                            val thumbY = (scroll.value.toFloat() / maxScroll).coerceIn(0f, 1f) * (size.height - thumbH)
                            val w = 2.5.dp.toPx()
                            drawRoundRect(
                                color = thumbColor,
                                topLeft = Offset(size.width - w, thumbY),
                                size = Size(w, thumbH),
                                cornerRadius = CornerRadius(w / 2f, w / 2f),
                            )
                        }
                    }
                    .verticalScroll(scroll)
                    .padding(end = 6.dp)
            } else {
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            }
            Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // 工具调用状态栏固定在滚动区上方，避免随底部自动滚动被顶出视野
                if (m.tools.isNotEmpty()) {
                    val latestToolTitle = m.tools.last().title.ifBlank { m.tools.last().name }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { toolsExpanded = !toolsExpanded }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("◦", fontSize = 13.sp, color = c.accent)
                            Text(
                                "已调用 ${m.tools.size} 步工具 · $latestToolTitle",
                                fontSize = 12.5.sp,
                                color = c.ink2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (toolsExpanded) "收起 ⌃" else "查看 >",
                                style = VibeLinkType.Hint,
                                color = c.accent,
                            )
                        }
                        AnimatedVisibility(visible = toolsExpanded) {
                            Column(
                                Modifier.padding(start = 8.dp, bottom = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                m.tools.forEach { tc ->
                                    CompactToolStepRow(tc = tc, onOpenRemoteFile = onOpenRemoteFile)
                                }
                            }
                        }
                    }
                }

                Column(containerMod) {
                    SelectionContainer {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .drawBehind {
                                    val strokeW = 1.8.dp.toPx()
                                    val x = 4.dp.toPx()
                                    drawLine(
                                        color = guideColor,
                                        start = Offset(x, 4.dp.toPx()),
                                        end = Offset(x, (size.height - 4.dp.toPx()).coerceAtLeast(4.dp.toPx())),
                                        strokeWidth = strokeW,
                                    )
                                }
                                .padding(start = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val visibleText = target.take(shown).trim()
                            if (visibleText.isNotEmpty()) {
                                val lines = remember(visibleText) {
                                    visibleText
                                        .split(Regex("\\n+"))
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                }
                                lines.forEachIndexed { lineIdx, rawLine ->
                                    val isLastLine = lineIdx == lines.lastIndex
                                    val isSubItem = rawLine.startsWith("◦") || rawLine.startsWith("- ") || rawLine.startsWith("* ")
                                    val isNumbered = rawLine.matches(Regex("^\\d+[.、)\\s].*"))
                                    val cleanLine = rawLine
                                        .removePrefix("•")
                                        .removePrefix("◦")
                                        .removePrefix("- ")
                                        .removePrefix("* ")
                                        .trim()
                                    val displayLine = (if (isNumbered) rawLine else cleanLine) +
                                        if (activelyThinking && isLastLine && liveElapsedSec % 2L == 0L) " ▍" else ""
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(start = if (isSubItem) 14.dp else 0.dp),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        if (!isNumbered) {
                                            DisableSelection {
                                                Text(
                                                    if (isSubItem) "◦ " else "• ",
                                                    fontSize = 13.5.sp,
                                                    lineHeight = 20.sp,
                                                    color = c.ink3,
                                                )
                                            }
                                        }
                                        Text(
                                            displayLine,
                                            fontSize = 13.5.sp,
                                            lineHeight = 20.sp,
                                            color = c.ink3,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val stampFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
private fun stamp(ms: Long): String = if (ms <= 0) "" else stampFmt.format(Date(ms))

private fun deviceName(devices: List<RemoteApi.Device>, id: String): String =
    devices.firstOrNull { it.id == id }?.hostname ?: id





















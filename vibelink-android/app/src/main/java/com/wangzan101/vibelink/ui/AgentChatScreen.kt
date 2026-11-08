package com.wangzan101.vibelink.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.vibelink.remote.MessageBubble
import com.wangzan101.vibelink.remote.RemoteApi
import com.wangzan101.vibelink.remote.RemoteStore
import com.wangzan101.vibelink.ui.components.BarIconButton
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.wangzan101.vibelink.ui.components.GlassBar
import com.wangzan101.vibelink.ui.components.GlassScrollToBottomButton
import com.wangzan101.vibelink.ui.components.VibeLinkButton
import com.wangzan101.vibelink.ui.components.PageBackground
import com.wangzan101.vibelink.ui.components.glassSource
import com.wangzan101.vibelink.ui.components.rememberGlassBackdrop
import com.wangzan101.vibelink.ui.components.rememberScrollToBottomState
import com.wangzan101.vibelink.ui.theme.VibeLinkIcons
import com.wangzan101.vibelink.ui.theme.VibeLinkSize
import com.wangzan101.vibelink.ui.theme.VibeLinkTheme
import com.wangzan101.vibelink.ui.theme.VibeLinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class RemoteAttachment(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val base64: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("filename", name)
        .put("mime", mime)
        .put("data", base64)
}

internal fun readUriAsAttachment(ctx: Context, uri: Uri): RemoteAttachment? = runCatching {
    val resolver = ctx.contentResolver
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    var displayName = "attachment_${System.currentTimeMillis()}"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIdx >= 0) {
                val n = cursor.getString(nameIdx)
                if (!n.isNullOrBlank()) displayName = n
            }
        }
    }
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (bytes.isEmpty() || bytes.size > 20 * 1024 * 1024) return null
    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    RemoteAttachment(
        id = uri.toString(),
        name = displayName,
        mime = mime,
        size = bytes.size.toLong(),
        base64 = b64,
    )
}.getOrNull()

private fun notifyRemoteTaskDone(ctx: Context, agentName: String, sessionTitle: String) {
    runCatching {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val chId = "vibelink_remote_done"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ch = android.app.NotificationChannel(
                chId,
                "远程任务完成通知",
                android.app.NotificationManager.IMPORTANCE_DEFAULT,
            )
            nm.createNotificationChannel(ch)
        }
        val notif = androidx.core.app.NotificationCompat.Builder(ctx, chId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("✅ $agentName 远程任务已完成")
            .setContentText(sessionTitle)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), notif)
    }
    Toast.makeText(ctx, "✅ $agentName 任务完成: $sessionTitle", Toast.LENGTH_SHORT).show()
}

/**
 * 统一的 VibeLink 远程电脑工作台会话页（全面采纳 Cindy 成熟细节）：
 *  - 支持多源传文件/图片（含截图红笔涂鸦标注）并自动落盘至远端会话工作目录
 *  - 支持远端生成文件在线代码/图片预览与一键下载到手机
 *  - 支持顶部一键呼出「📁 远程文件树」、「🔀 Git 变更 Diff 审查」、「⏰ 自动任务」、「🔌 插件技能」与「🖥 远程桌面」
 *  - 支持 Antigravity / Claude Code / Codex / OpenCode 四大引擎完整双向交互
 *  - 支持模型/推理强度/自动审批模式切换、斜杠指令、@远端文件引用与语音输入
 */
@Composable
fun AgentChatScreen(
    agentId: String,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    initialSessionId: String? = null,
    onSwitchAgent: (String, String?) -> Unit = { _, _ -> },
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = VibeLinkTheme.colors
    val hub = remember { RemoteStore.hub(ctx) }
    val token = remember { RemoteStore.token(ctx) }

    LaunchedEffect(Unit) {
        BotTeamStore.ensureSeeded(ctx)
        BotTeamStore.syncWithHub(ctx)
    }
    val allBots by BotTeamStore.botsFlow.collectAsState()
    val storeVersion by BotTeamStore.versionFlow.collectAsState()
    val botFriend = remember(agentId, allBots, storeVersion) {
        allBots.firstOrNull { it.id == agentId } ?: BotTeamStore.getBot(ctx, agentId)
    }
    val effectiveEngine = botFriend?.engine ?: agentId
    val title = botFriend?.name ?: agentDisplayName(agentId)

    // 首帧同步读取两级缓存（L1 内存 + L2 磁盘），在公网/弱网下实现 0ms 瞬时上屏，彻底消除白屏等待
    val initialCachedSessions = remember(effectiveEngine) {
        RemoteSessionCache.getCachedSessions(ctx, effectiveEngine)
    }
    val initialPreferId = initialSessionId?.takeIf { it.isNotBlank() }
        ?: botFriend?.activeSessionId?.takeIf { it.isNotBlank() }
    val initialTargetSession = remember(effectiveEngine, initialPreferId, botFriend?.id) {
        when {
            initialPreferId != null -> initialCachedSessions.firstOrNull { it.id == initialPreferId }
                ?: RemoteApi.Session(
                    id = initialPreferId,
                    title = botFriend?.lastText.orEmpty().ifBlank { "会话" },
                    model = "",
                    updated = botFriend?.lastAt ?: System.currentTimeMillis(),
                    agent = effectiveEngine,
                    device = botFriend?.device?.ifBlank { "pc" } ?: "pc",
                    cwd = botFriend?.cwd ?: "C:\\Users\\22771\\Desktop\\项目\\VibeLink",
                )
            botFriend != null -> null // Bot 尚未创建专属会话时先显示待命卡片，发首条消息时自动创建专属会话
            else -> initialCachedSessions.firstOrNull()
        }
    }
    val initialCachedMessagesPair = remember(initialTargetSession?.id) {
        initialTargetSession?.id?.let { RemoteSessionCache.getCachedMessages(ctx, it) }
            ?: (emptyList<RemoteApi.Message>() to null)
    }

    var sessions by remember(effectiveEngine) { mutableStateOf(initialCachedSessions) }
    var session by remember(agentId, initialPreferId) { mutableStateOf(initialTargetSession) }
    var latest by remember(initialTargetSession?.id) { mutableStateOf(initialCachedMessagesPair.first) }
    var older by remember(initialTargetSession?.id) { mutableStateOf<List<RemoteApi.Message>>(emptyList()) }
    var nextCursor by remember(initialTargetSession?.id) { mutableStateOf(initialCachedMessagesPair.second) }
    var loadingEarlier by remember { mutableStateOf(false) }
    val messages: List<RemoteApi.Message> = remember(latest, older) {
        (older + latest).distinctBy { m -> m.id.ifBlank { "local:${m.at}:${m.text.hashCode()}" } }
    }
    val draftKey = remember(agentId) { "bot:$agentId" }
    var input by remember(draftKey) { mutableStateOf(BotTeamStore.getDraftText(ctx, draftKey)) }
    var pendingAttachments by remember(draftKey) { mutableStateOf(BotTeamStore.getDraftAttachments(draftKey)) }
    LaunchedEffect(draftKey, input) {
        BotTeamStore.setDraftText(ctx, draftKey, input)
    }
    LaunchedEffect(draftKey, pendingAttachments) {
        BotTeamStore.setDraftAttachments(draftKey, pendingAttachments)
    }
    var markupTarget by remember { mutableStateOf<RemoteAttachment?>(null) }
    var loading by remember(initialTargetSession?.id) {
        mutableStateOf(initialCachedMessagesPair.first.isEmpty() && initialTargetSession != null)
    }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var showNewSessionDialog by remember { mutableStateOf(false) }
    var showEditBotDialog by remember { mutableStateOf(false) }
    var showVoiceCallDialog by remember { mutableStateOf(false) }
    var workspaceInspectorTab by remember { mutableStateOf<Int?>(null) }
    var showRemoteDesktop by remember { mutableStateOf(false) }
    var viewingRemoteFilePath by remember { mutableStateOf<String?>(null) }
    var directUploadTargetDir by remember { mutableStateOf<String?>(null) }
    var effortMode by remember { mutableIntStateOf(0) } // 0: ⚡自动·高强度, 1: ⚡自动·标准, 2: 🛡确认模式
    var showSlashMenu by remember { mutableStateOf(false) }
    var sentAt by remember { mutableStateOf(0L) }
    var stick by remember { mutableStateOf(true) }

    val effortLabels = listOf(
        "⚡ 自动审批 · 高强度",
        "⚡ 自动审批 · 标准",
        "🛡 步进确认模式",
    )

    // 挑选待发送附件（支持多选任意文件或图片）
    val attachmentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val targetDir = directUploadTargetDir
        directUploadTargetDir = null
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                uris.mapNotNull { readUriAsAttachment(ctx, it) }
            }
            if (loaded.isEmpty()) {
                Toast.makeText(ctx, "文件读取失败或超过 20MB 限制", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (targetDir != null) {
                // 直接上传到远端工作区指定目录
                val dev = session?.device?.ifBlank { "pc" } ?: "pc"
                runCatching {
                    withContext(Dispatchers.IO) {
                        RemoteApi.workspaceUpload(
                            hub = hub,
                            token = token,
                            device = dev,
                            cwd = targetDir,
                            files = loaded.map { it.toJson() },
                            subdir = false,
                        )
                    }
                }.onSuccess { saved ->
                    Toast.makeText(ctx, "已上传 ${saved.size} 个文件到 $targetDir", Toast.LENGTH_LONG).show()
                }.onFailure { e ->
                    Toast.makeText(ctx, "上传失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                pendingAttachments = pendingAttachments + loaded
            }
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (spoken.isNotEmpty()) {
                input = if (input.isBlank()) spoken else "$input $spoken"
            }
        }
    }

    val initialScrollIdx = remember(initialTargetSession?.id) {
        val cnt = initialCachedMessagesPair.first.size
        val extra = if (initialCachedMessagesPair.second != null) 1 else 0
        (cnt - 1 + extra).coerceAtLeast(0)
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialScrollIdx,
        initialFirstVisibleItemScrollOffset = if (initialCachedMessagesPair.first.isNotEmpty()) 100_000 else 0,
    )
    var initialNetworkScrollDone by remember(session?.id) { mutableStateOf(false) }
    var hasUserScrolledUp by remember(session?.id) { mutableStateOf(false) }
    val glassBackdrop = rememberGlassBackdrop()
    val scrollToBottomState = rememberScrollToBottomState(listState)

    suspend fun scrollToBottomImmediate() {
        scrollToBottomState.hideImmediate()
        if (messages.isEmpty()) return
        val extra = if (nextCursor != null) 1 else 0
        val targetIdx = (messages.size - 1 + extra).coerceAtLeast(0)
        runCatching {
            listState.scrollToItem(targetIdx, scrollOffset = 100_000)
            withFrameNanos { }
            listState.scrollToItem(targetIdx, scrollOffset = 100_000)
        }
        hasUserScrolledUp = false
        stick = true
    }

    LaunchedEffect(session?.id) {
        val sid = session?.id.orEmpty()
        if (sid.isNotBlank()) {
            val (cachedMsgs, cachedCur) = RemoteSessionCache.getCachedMessages(ctx, sid)
            if (cachedMsgs.isNotEmpty() && latest.isEmpty()) {
                latest = cachedMsgs
                nextCursor = cachedCur
            }
        }
        older = emptyList()
        stick = true
        hasUserScrolledUp = false
        initialNetworkScrollDone = false
        if (messages.isNotEmpty()) {
            scrollToBottomImmediate()
        }
    }

    fun fail(e: Throwable) {
        error = e.message ?: e.toString()
    }

    suspend fun loadMessages(s: RemoteApi.Session) {
        // 先检查本地缓存，若当前列表为空则立即先展示缓存消息
        if (latest.isEmpty()) {
            val (cached, cur) = RemoteSessionCache.getCachedMessages(ctx, s.id)
            if (cached.isNotEmpty()) {
                latest = cached
                if (older.isEmpty()) nextCursor = cur
                loading = false
            }
        }
        runCatching {
            withContext(Dispatchers.IO) { RemoteApi.messagesPage(hub, token, s.device.ifBlank { "pc" }, s.agent, s.id) }
        }.onSuccess { page ->
            val wasFirstNetworkLoad = !initialNetworkScrollDone
            latest = page.messages
            if (older.isEmpty()) nextCursor = page.next
            RemoteSessionCache.putCachedMessages(ctx, s.id, page.messages, page.next)
            val lastAssistantBusy = page.messages.lastOrNull()?.let { it.role == "assistant" && it.at == 0L } == true
            if (lastAssistantBusy) {
                session = session?.copy(running = true)
            } else if (page.messages.lastOrNull()?.role == "assistant" && System.currentTimeMillis() - sentAt > 1200L) {
                session = session?.copy(running = false)
            }
            if (wasFirstNetworkLoad) {
                initialNetworkScrollDone = true
                stick = true
            }
            if (botFriend != null) {
                val lastAssistant = page.messages.lastOrNull { it.role == "assistant" }
                val lastReply = lastAssistant?.text?.lineSequence()?.firstOrNull()?.take(45)
                if (!lastReply.isNullOrBlank()) {
                    BotTeamStore.bindSessionToBot(ctx, botFriend.id, s.id, lastReply)
                }
                if (lastAssistant != null && lastAssistant.at > 0L && lastAssistant.text.isNotBlank()) {
                    BotTeamStore.syncCompletedSessionToGroups(
                        context = ctx,
                        botId = botFriend.id,
                        sessionId = s.id,
                        latestAssistantText = lastAssistant.text,
                        reasoning = lastAssistant.reasoning,
                        startedAt = lastAssistant.started,
                        completedAt = lastAssistant.at,
                        tools = lastAssistant.tools,
                    )
                }
            }
            error = null
        }.onFailure { e ->
            // 若在发送后轮询失败超过 15 秒，自动移除本地占位的「正在思考」气泡并提示错误，防止永久卡死
            if (latest.any { it.id.startsWith("local-assistant-") } && System.currentTimeMillis() - sentAt > 15_000L) {
                latest = latest.filterNot { it.id.startsWith("local-assistant-") }
                fail(e)
            } else if (latest.isEmpty()) {
                fail(e)
            }
        }
    }

    fun loadEarlier() {
        val s = session ?: return
        val cursor = nextCursor ?: return
        if (loadingEarlier) return
        loadingEarlier = true
        stick = false
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { RemoteApi.messagesPage(hub, token, s.device.ifBlank { "pc" }, s.agent, s.id, cursor) }
            }.onSuccess { page ->
                older = page.messages + older
                nextCursor = page.next
                error = null
            }.onFailure(::fail)
            loadingEarlier = false
        }
    }

    fun loadAll(preferSessionId: String? = initialPreferId) {
        scope.launch {
            if (latest.isEmpty() && session != null) loading = true
            error = null
            val list = runCatching { loadAgentSessions(ctx, effectiveEngine) }.getOrElse {
                if (sessions.isEmpty()) fail(it)
                sessions
            }
            if (list.isNotEmpty()) {
                val boundSet = botFriend?.sessionIds.orEmpty().toSet()
                sessions = if (boundSet.isNotEmpty()) {
                    list.sortedWith(compareByDescending<RemoteApi.Session> { it.id in boundSet }.thenByDescending { it.updated })
                } else {
                    list
                }
            }
            val target = when {
                !preferSessionId.isNullOrBlank() && !preferSessionId.startsWith("01a1a0") -> list.firstOrNull { it.id == preferSessionId } ?: session
                botFriend != null -> {
                    val activeId = botFriend.activeSessionId
                    if (activeId.isNotBlank() && !activeId.startsWith("01a1a0")) list.firstOrNull { it.id == activeId } ?: session else null
                }
                else -> list.firstOrNull() ?: session
            }
            session = target
            if (target != null) loadMessages(target)
            loading = false
        }
    }

    LaunchedEffect(agentId, initialPreferId) { loadAll(initialPreferId) }
    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.canScrollForward,
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
            )
        }.collect { (scrolling, canForward, lastIdx) ->
            // 仅在用户主动手势向上滚动离开底部后才显示悬浮按钮；到达底部时自动隐藏
            if (!canForward) {
                hasUserScrolledUp = false
                stick = true
            } else if (scrolling) {
                val total = listState.layoutInfo.totalItemsCount
                stick = total > 0 && lastIdx >= total - 2 && !canForward
                hasUserScrolledUp = true
            }
        }
    }
    val lastMsg = latest.lastOrNull()
    LaunchedEffect(
        session?.id,
        messages.size,
        lastMsg?.id,
        lastMsg?.text?.length,
        lastMsg?.reasoning?.length,
        lastMsg?.tools?.size,
        initialNetworkScrollDone,
    ) {
        if (messages.isNotEmpty() && stick) {
            scrollToBottomImmediate()
        }
    }
    LaunchedEffect(session?.id) {
        val s = session ?: return@LaunchedEffect
        while (true) {
            val busy = latest.lastOrNull()?.let { it.role == "assistant" && it.at == 0L } == true
            val recent = System.currentTimeMillis() - sentAt < 30_000
            delay(if (busy || recent || session?.running == true) 850 else 3500)
            if (!sending) loadMessages(s)
        }
    }
    LaunchedEffect(effectiveEngine) {
        while (true) {
            delay(12_000)
            val fresh = runCatching { loadAgentSessions(ctx, effectiveEngine) }.getOrNull() ?: continue
            val prevRunning = session?.running == true
            sessions = fresh
            val id = session?.id ?: continue
            fresh.firstOrNull { it.id == id }?.let { updated ->
                if (prevRunning && !updated.running) {
                    notifyRemoteTaskDone(ctx, title, updated.title.ifBlank { updated.id })
                }
                session = updated
            }
        }
    }

    val recentCwds = remember(sessions) {
        sessions.map { it.cwd.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("C--") && !it.startsWith("D--") }
            .distinct()
            .take(5)
    }

    val effectiveCwd = session?.cwd?.takeIf { it.isNotBlank() && !it.startsWith("C--") }
        ?: botFriend?.cwd?.takeIf { it.isNotBlank() }
        ?: recentCwds.firstOrNull()
        ?: "C:\\Users\\22771\\Desktop\\项目\\VibeLink"
    val effectiveDevice = session?.device?.ifBlank { null }
        ?: botFriend?.device?.ifBlank { null }
        ?: sessions.firstOrNull()?.device?.ifBlank { null }
        ?: "pc"

    PageBackground {
        Column(Modifier.fillMaxSize().imePadding()) {
            // 顶部导航栏：严格控制右侧按钮紧凑度，保证 Bot 姓名 + 职位头衔在 360dp 真机上完整显示不挤压
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarIconButton(VibeLinkIcons.Back, "返回", onBack)
                // 点击头像进入 Bot 资料与底层会话工位管理页
                AgentAvatar(agentId, title, 34.dp, onClick = onOpenProfile)
                Spacer(Modifier.width(8.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { if (sessions.isNotEmpty() || botFriend != null) menu = true },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            style = VibeLinkType.Title.copy(fontSize = 16.5.sp),
                            color = c.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (botFriend != null) {
                            Spacer(Modifier.width(5.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(botFriend.lifecycle.badgeColorHex).copy(alpha = 0.14f))
                                    .padding(horizontal = 5.dp, vertical = 1.5.dp),
                            ) {
                                Text(
                                    botFriend.jobTitle,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(botFriend.lifecycle.badgeColorHex),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        } else {
                            Spacer(Modifier.width(5.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(c.accent.copy(alpha = 0.14f))
                                    .padding(horizontal = 5.dp, vertical = 1.5.dp),
                            ) {
                                Text(
                                    effectiveDevice.uppercase(),
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = c.accent,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                    val sub = when {
                        loading && messages.isEmpty() -> "正在同步…"
                        session != null -> buildString {
                            if (botFriend != null) {
                                append(effectiveDevice.uppercase())
                                append(" · ")
                                append(agentDisplayName(effectiveEngine))
                                append(" · ")
                            }
                            val folder = session?.cwd.orEmpty().substringAfterLast('\\').substringAfterLast('/')
                            if (folder.isNotBlank()) {
                                append(folder)
                                append(" · ")
                            }
                            append(session?.title.orEmpty().ifBlank { session?.id.orEmpty() })
                            if (session?.running == true) append(" · 运行中")
                        }
                        botFriend != null -> "${effectiveDevice.uppercase()} · ${agentDisplayName(effectiveEngine)} · 在线待命，直接发消息派活"
                        else -> "还没有会话 · 点右上角 + 新建"
                    }
                    Text(sub, style = VibeLinkType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                // 📁 远端工作台图标按钮
                BarIconButton(VibeLinkIcons.Terminal, "远程工作台（文件树/Git/定时任务/技能）", { workspaceInspectorTab = 0 })

                // 🖥 远程电脑桌面按钮
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.surface)
                        .border(0.5.dp, c.edge, RoundedCornerShape(8.dp))
                        .clickable { showRemoteDesktop = true }
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                ) {
                    Text("🖥", fontSize = 12.sp, color = c.ink, maxLines = 1, softWrap = false)
                }

                Box {
                    BarIconButton(VibeLinkIcons.Chats, "会话与工位菜单", { menu = true })
                    DropdownMenu(menu, { menu = false }) {
                        if (botFriend != null) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "👤 Bot 工位与职位设置 (${botFriend.name})",
                                        style = VibeLinkType.BodyStrong,
                                        color = c.ink,
                                    )
                                },
                                onClick = {
                                    menu = false
                                    onOpenProfile()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "+ 新建 $title 专属会话",
                                    style = VibeLinkType.BodyStrong,
                                    color = c.accent,
                                )
                            },
                            onClick = {
                                menu = false
                                showNewSessionDialog = true
                            },
                        )
                        sessions.take(50).forEach { s ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            s.title.ifBlank { s.id },
                                            style = VibeLinkType.Body,
                                            color = if (s.id == session?.id) c.accent else c.ink,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val folder = s.cwd.substringAfterLast('\\').substringAfterLast('/')
                                        Text(
                                            buildString {
                                                append(s.device)
                                                if (folder.isNotBlank()) append(" · $folder")
                                                append(" · ")
                                                append(conversationTime(s.updated, System.currentTimeMillis()))
                                                if (s.running) append(" · 运行中")
                                            },
                                            style = VibeLinkType.Hint,
                                            color = c.ink3,
                                        )
                                    }
                                },
                                onClick = {
                                    menu = false
                                    session = s
                                    if (botFriend != null) {
                                        BotTeamStore.bindSessionToBot(ctx, botFriend.id, s.id, s.title)
                                    }
                                    val (cachedMsgs, cachedCur) = RemoteSessionCache.getCachedMessages(ctx, s.id)
                                    latest = cachedMsgs
                                    older = emptyList()
                                    nextCursor = cachedCur
                                    stick = true
                                    initialNetworkScrollDone = false
                                    scope.launch {
                                        scrollToBottomImmediate()
                                        loadMessages(s)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            error?.let {
                Text(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = VibeLinkType.Hint, color = c.bad, maxLines = 2)
            }

            if (session == null && !loading && botFriend == null) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (error == null) "这个 agent 还没有会话" else "连不上 hub 或暂无会话",
                            style = VibeLinkType.Caption,
                            color = c.ink3,
                        )
                        Spacer(Modifier.height(14.dp))
                        VibeLinkButton(
                            "+ 新建 $title 远程会话",
                            onClick = { showNewSessionDialog = true },
                        )
                    }
                }
            } else {
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
                        state = listState,
                    ) {
                        if (botFriend != null && messages.isEmpty() && !loading) {
                            item("bot_welcome_card") {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    AgentAvatar(botFriend.id, botFriend.name, 38.dp, onClick = onOpenProfile)
                                    Spacer(Modifier.width(10.dp))
                                    Column(
                                        Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(c.surface)
                                            .border(0.5.dp, c.edge, RoundedCornerShape(14.dp))
                                            .padding(14.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                botFriend.name,
                                                style = VibeLinkType.BodyStrong,
                                                color = c.ink,
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Box(
                                                Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Color(botFriend.lifecycle.badgeColorHex).copy(alpha = 0.14f))
                                                    .padding(horizontal = 6.dp, vertical = 1.5.dp),
                                            ) {
                                                Text(
                                                    "${botFriend.jobTitle} · ${botFriend.lifecycle.label}",
                                                    fontSize = 10.5.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(botFriend.lifecycle.badgeColorHex),
                                                    maxLines = 1,
                                                    softWrap = false,
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            "你好！我是 **${botFriend.name}**（${botFriend.jobTitle}），底层由 **${agentDisplayName(botFriend.engine)}** 驱动。\n\n**岗位职责**：${botFriend.rolePrompt}\n**工作目录**：`${botFriend.cwd}`\n\n你可以直接在下方输入框发消息向我派活，系统会自动在工作目录建立我的专属编码会话。",
                                            style = VibeLinkType.Caption,
                                            color = c.ink2,
                                        )
                                        Spacer(Modifier.height(10.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Box(
                                                Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(c.accent.copy(alpha = 0.12f))
                                                    .clickable { menu = true }
                                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                            ) {
                                                Text(
                                                    "🔗 绑定已有会话 (${sessions.size})",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = c.accent,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                )
                                            }
                                            Box(
                                                Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(c.fill)
                                                    .clickable { showEditBotDialog = true }
                                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                            ) {
                                                Text(
                                                    "⚙️ 职位配置",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = c.ink2,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (nextCursor != null) {
                            item("earlier") {
                                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                    TextButton(onClick = ::loadEarlier, enabled = !loadingEarlier) {
                                        Text(
                                            if (loadingEarlier) "载入中…" else "↑ 载入更早消息",
                                            style = VibeLinkType.Hint,
                                            color = c.ink3,
                                        )
                                    }
                                }
                            }
                        }
                        itemsIndexed(messages) { i, m ->
                            val isLastAssistant = i == messages.lastIndex && m.role == "assistant"
                            MessageBubble(
                                m = m,
                                streaming = isLastAssistant && m.at == 0L,
                                onOpenRemoteFile = { path -> viewingRemoteFilePath = path },
                                showApprovalChips = isLastAssistant && m.at > 0L && effortMode == 2,
                                onQuickReply = { quickText ->
                                    val s = session ?: return@MessageBubble
                                    val now = System.currentTimeMillis()
                                    val pending = RemoteApi.Message(
                                        role = "user",
                                        text = quickText,
                                        reasoning = "",
                                        at = now,
                                        id = "local-user-$now",
                                        started = now,
                                    )
                                    stick = true
                                    latest = latest + pending
                                    RemoteSessionCache.putCachedMessages(ctx, s.id, latest, nextCursor)
                                    sending = true
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                RemoteApi.prompt(
                                                    hub = hub,
                                                    token = token,
                                                    device = s.device,
                                                    session = s.id,
                                                    text = quickText,
                                                    agent = s.agent,
                                                    cwd = s.cwd,
                                                )
                                            }
                                        }.onSuccess {
                                            sentAt = System.currentTimeMillis()
                                            session = s.copy(running = true)
                                            delay(400)
                                            loadMessages(s)
                                        }.onFailure { e ->
                                            latest = latest.filter { it !== pending }
                                            fail(e)
                                        }
                                        sending = false
                                    }
                                },
                            )
                        }
                    }

                    GlassScrollToBottomButton(
                        state = scrollToBottomState,
                        backdrop = glassBackdrop,
                        hasMessages = messages.isNotEmpty(),
                        onClick = { scope.launch { scrollToBottomImmediate() } },
                    )
                }
            }

            val canSend = (session != null && session?.agent in BotModelPolicy.engines.toSet()) ||
                (botFriend != null && effectiveEngine in BotModelPolicy.engines.toSet())

            // 待发送附件预览条（Cindy 风格：支持图片缩略图 + 🖍涂鸦标注 + 文件芯片 + 移除）
            if (pendingAttachments.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    pendingAttachments.forEach { att ->
                        val isImg = att.mime.startsWith("image/")
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(c.surface)
                                .border(1.dp, c.accent.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (isImg) {
                                val thumb = remember(att.base64) {
                                    runCatching {
                                        val bytes = Base64.decode(att.base64, Base64.DEFAULT)
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                    }.getOrNull()
                                }
                                if (thumb != null) {
                                    Image(
                                        bitmap = thumb,
                                        contentDescription = att.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)),
                                    )
                                } else {
                                    Text("🖼", fontSize = 16.sp)
                                }
                            } else {
                                Text("📎", fontSize = 15.sp)
                            }
                            Column {
                                Text(
                                    att.name,
                                    style = VibeLinkType.Caption,
                                    color = c.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 130.dp),
                                )
                                Text(formatByteSize(att.size), style = VibeLinkType.Hint, color = c.ink3)
                            }
                            if (isImg) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(c.accent.copy(alpha = 0.15f))
                                        .clickable { markupTarget = att }
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                ) {
                                    Text("🖍 标注", style = VibeLinkType.Hint, color = c.accent, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(c.surface)
                                    .clickable {
                                        pendingAttachments = pendingAttachments.filterNot { it.id == att.id }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("✕", fontSize = 11.sp, color = c.ink2)
                            }
                        }
                    }
                }
            }

            // Cindy 风格的输入框上方快捷控制条（传文件 / 传图 / @引用远端文件 / 斜杠命令 / 权限与推理强度）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.accent.copy(alpha = 0.14f))
                        .border(0.5.dp, c.accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .clickable(enabled = canSend) {
                            directUploadTargetDir = null
                            attachmentLauncher.launch("*/*")
                        }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text("📎 传文件", style = VibeLinkType.Hint, color = c.accent, fontWeight = FontWeight.SemiBold)
                }

                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.surface)
                        .border(0.5.dp, c.edge, RoundedCornerShape(8.dp))
                        .clickable(enabled = canSend) {
                            directUploadTargetDir = null
                            attachmentLauncher.launch("image/*")
                        }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text("🖼 图片/截图", style = VibeLinkType.Hint, color = c.ink2)
                }

                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.surface)
                        .border(0.5.dp, c.edge, RoundedCornerShape(8.dp))
                        .clickable { workspaceInspectorTab = 0 }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text("@ 远端文件", style = VibeLinkType.Hint, color = c.ink2)
                }

                Box {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.surface)
                            .border(0.5.dp, c.edge, RoundedCornerShape(8.dp))
                            .clickable { showSlashMenu = true }
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    ) {
                        Text("/ 快捷指令", style = VibeLinkType.Hint, color = c.ink2)
                    }
                    DropdownMenu(expanded = showSlashMenu, onDismissRequest = { showSlashMenu = false }) {
                        listOf(
                            "/plan" to "生成结构化分步改造计划并等待确认",
                            "/review" to "审查当前工作区代码变更 (Git Diff)",
                            "/compact" to "压缩上下文并整理关键进展",
                            "/status" to "汇报当前任务状态与剩余步骤",
                            "/boost" to "开启多视角深度分析与严格验证",
                            "请检查我刚上传的文件并分析处理" to "分析上传的附件文件",
                        ).forEach { (cmd, desc) ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(cmd, style = VibeLinkType.Mono, color = c.accent, fontWeight = FontWeight.SemiBold)
                                        Text(desc, style = VibeLinkType.Hint, color = c.ink3)
                                    }
                                },
                                onClick = {
                                    showSlashMenu = false
                                    input = if (input.isBlank()) cmd else "$input $cmd"
                                },
                            )
                        }
                    }
                }

                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.surface)
                        .border(0.5.dp, c.edge, RoundedCornerShape(8.dp))
                        .clickable { workspaceInspectorTab = 1 }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text("🔀 Git审查", style = VibeLinkType.Hint, color = c.ink2)
                }

                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.surface)
                        .border(0.5.dp, c.edge, RoundedCornerShape(8.dp))
                        .clickable { workspaceInspectorTab = 2 }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text("⏰ 自动任务", style = VibeLinkType.Hint, color = c.ink2)
                }

                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.surface)
                        .border(0.5.dp, c.edge, RoundedCornerShape(8.dp))
                        .clickable { workspaceInspectorTab = 3 }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text("🔌 插件技能", style = VibeLinkType.Hint, color = c.ink2)
                }

                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.surface)
                        .border(0.5.dp, c.edge, RoundedCornerShape(8.dp))
                        .clickable { workspaceInspectorTab = 4 }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text(">_ 交互终端", style = VibeLinkType.Hint, color = c.ink2)
                }

                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.surface)
                        .border(0.5.dp, c.edge, RoundedCornerShape(8.dp))
                        .clickable { workspaceInspectorTab = 5 }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text("🛡️ 安全审批", style = VibeLinkType.Hint, color = c.ink2)
                }

                if (botFriend != null) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.accent.copy(alpha = 0.14f))
                            .border(0.5.dp, c.accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .clickable { showVoiceCallDialog = true }
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    ) {
                        Text("📞 语音通话", style = VibeLinkType.Hint, color = c.accent, fontWeight = FontWeight.SemiBold)
                    }
                }

                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.surface)
                        .border(0.5.dp, c.edge, RoundedCornerShape(8.dp))
                        .clickable { showRemoteDesktop = true }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text("🖥 远程桌面", style = VibeLinkType.Hint, color = c.ink2)
                }

                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.surface)
                        .border(0.5.dp, c.edge, RoundedCornerShape(8.dp))
                        .clickable { effortMode = (effortMode + 1) % effortLabels.size }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text(effortLabels[effortMode], style = VibeLinkType.Hint, color = c.ink2)
                }
            }

            val streamBusy = latest.lastOrNull()?.let { it.role == "assistant" && it.at == 0L } == true
            val isRunningNow = sending || session?.running == true || streamBusy
            var busySendMode by remember(draftKey) {
                mutableStateOf(BotTeamStore.getBusySendMode(draftKey, "queue"))
            }
            var queuedPrompts by remember(draftKey) {
                mutableStateOf(BotTeamStore.getQueuedPrompts(draftKey))
            }
            val trayAttachments = remember(pendingAttachments) {
                pendingAttachments.map { att ->
                    PendingAttachment(
                        uri = att.id,
                        name = att.name,
                        size = att.size,
                        photo = att.mime.startsWith("image/") || AttachmentMessage.isPhoto(att.name),
                    )
                }
            }
            fun executeSendNow(
                text: String,
                filesToSend: List<RemoteAttachment>,
                interruptFirst: Boolean = false,
            ) {
                if (text.isEmpty() && filesToSend.isEmpty()) return
                val isCreatingNewBotSession = session == null && botFriend != null
                val wasFirstMessage = latest.isEmpty() && older.isEmpty() && (session == null || botFriend?.sessionIds?.contains(session?.id) == true)
                sending = true
                val now = System.currentTimeMillis()
                val displayText = buildString {
                    if (text.isNotEmpty()) append(text)
                    if (filesToSend.isNotEmpty()) {
                        if (isNotEmpty()) append("\n")
                        append("📎 已上传附件: ")
                        append(filesToSend.joinToString(", ") { it.name })
                    }
                }
                val previewImgs = filesToSend
                    .filter { it.mime.startsWith("image/") }
                    .map { "data:${it.mime};base64,${it.base64}" }
                val pending = RemoteApi.Message(
                    role = "user",
                    text = displayText,
                    reasoning = "",
                    at = now,
                    images = previewImgs,
                    id = "local-user-$now",
                    started = now,
                )
                val pendingAssistant = RemoteApi.Message(
                    role = "assistant",
                    text = "",
                    reasoning = "正在分析上下文与制定方案…",
                    at = 0L,
                    id = "local-assistant-$now",
                    started = now,
                )
                stick = true
                val baseLatest = if (interruptFirst) {
                    latest.filterNot { it.id.startsWith("local-assistant-") || (it.role == "assistant" && it.at == 0L) }
                } else {
                    latest
                }
                latest = baseLatest + pending + pendingAssistant
                session?.id?.takeIf { it.isNotBlank() }?.let { sid ->
                    RemoteSessionCache.putCachedMessages(ctx, sid, latest, nextCursor)
                }
                if (botFriend != null) {
                    BotTeamStore.bindSessionToBot(
                        ctx,
                        botFriend.id,
                        session?.id.orEmpty(),
                        displayText.lineSequence().firstOrNull()?.take(40),
                    )
                }
                scope.launch {
                    val existingSession = session
                    if (interruptFirst && existingSession != null && existingSession.id.isNotBlank()) {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                RemoteApi.interrupt(
                                    hub = hub,
                                    token = token,
                                    device = existingSession.device.ifBlank { "pc" },
                                    session = existingSession.id,
                                    agent = existingSession.agent,
                                )
                            }
                        }
                        delay(200)
                    }
                    var sessionFailure: Throwable? = null
                    val targetSession: RemoteApi.Session? = existingSession ?: if (botFriend != null) {
                        val sid = runCatching { BotTeamStore.ensureBotRemoteSession(
                            ctx,
                            botFriend,
                            text.ifBlank { "上传附件任务" },
                        ) }.getOrElse { sessionFailure = it; "" }
                        if (sid.isNotBlank()) {
                            RemoteApi.Session(
                                id = sid,
                                title = "[${botFriend.jobTitle}-${botFriend.name}] ${text.take(24)}",
                                model = botFriend.model,
                                updated = now,
                                agent = effectiveEngine,
                                device = effectiveDevice,
                                cwd = effectiveCwd,
                                running = true,
                            ).also { created ->
                                session = created
                                sessions = listOf(created) + sessions.filterNot { it.id == created.id }
                                RemoteSessionCache.putCachedMessages(ctx, sid, latest, nextCursor)
                            }
                        } else null
                    } else null

                    if (targetSession == null) {
                        latest = latest.filter { it !== pending && it !== pendingAssistant }
                        input = text
                        pendingAttachments = filesToSend
                        fail(sessionFailure ?: RuntimeException("Codex 未返回可用会话 ID"))
                        sending = false
                        return@launch
                    }

                    val promptText = if (botFriend != null && (isCreatingNewBotSession || wasFirstMessage) && text.isNotBlank()) {
                        BotTeamStore.buildBotRolePrompt(botFriend, text)
                    } else {
                        text
                    }

                    val r = runCatching {
                        withContext(Dispatchers.IO) {
                            RemoteApi.prompt(
                                hub = hub,
                                token = token,
                                device = targetSession.device.ifBlank { "pc" },
                                session = targetSession.id,
                                text = promptText,
                                files = filesToSend.map { it.toJson() },
                                agent = targetSession.agent,
                                botId = botFriend?.id.orEmpty(),
                                model = botFriend?.model.orEmpty(),
                                cwd = targetSession.cwd,
                            )
                        }
                    }
                    if (r.isFailure) {
                        latest = latest.filter { it !== pending && it !== pendingAssistant }
                        RemoteSessionCache.putCachedMessages(ctx, targetSession.id, latest, nextCursor)
                        input = text
                        pendingAttachments = filesToSend
                        fail(r.exceptionOrNull() ?: RuntimeException("发送失败"))
                    } else {
                        val resolvedSid = r.getOrNull()?.optString("id")?.trim().orEmpty()
                        val effectiveSession = if (resolvedSid.isNotBlank() && resolvedSid != targetSession.id) {
                            targetSession.copy(id = resolvedSid, running = true).also { updated ->
                                if (botFriend != null) {
                                    BotTeamStore.bindSessionToBot(
                                        ctx,
                                        botFriend.id,
                                        resolvedSid,
                                        displayText.lineSequence().firstOrNull()?.take(40),
                                    )
                                }
                            }
                        } else {
                            targetSession.copy(running = true)
                        }
                        sentAt = System.currentTimeMillis()
                        session = effectiveSession
                        delay(400)
                        loadMessages(effectiveSession)
                    }
                    sending = false
                }
            }

            // 当上一条指令执行完成且排队队列非空时，自动顺延派发下一条排队指令
            LaunchedEffect(isRunningNow, queuedPrompts.size, session?.id) {
                if (!isRunningNow && queuedPrompts.isNotEmpty()) {
                    delay(450L)
                    val stillBusy = sending || session?.running == true ||
                        (latest.lastOrNull()?.let { it.role == "assistant" && it.at == 0L } == true)
                    if (!stillBusy) {
                        val nextItem = BotTeamStore.popNextQueuedPrompt(draftKey)
                        queuedPrompts = BotTeamStore.getQueuedPrompts(draftKey)
                        if (nextItem != null) {
                            executeSendNow(nextItem.text, nextItem.attachments, interruptFirst = false)
                        }
                    }
                }
            }

            val triggerSend: () -> Unit = triggerSend@{
                val text = input.trim()
                val filesToSend = pendingAttachments
                if (text.isEmpty() && filesToSend.isEmpty()) return@triggerSend
                input = ""
                pendingAttachments = emptyList()
                BotTeamStore.clearDraft(ctx, draftKey)
                if (isRunningNow && busySendMode != "interrupt") {
                    val qItem = QueuedBotPrompt(
                        text = text,
                        attachments = filesToSend,
                    )
                    queuedPrompts = BotTeamStore.enqueuePrompt(draftKey, qItem)
                    Toast.makeText(
                        ctx,
                        "⏳ 已加入排队队列 (#${queuedPrompts.size})，完成后自动执行；也可点击「⚡ 改为打断」立即执行",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@triggerSend
                }
                executeSendNow(text, filesToSend, interruptFirst = isRunningNow && busySendMode == "interrupt")
            }

            // 待执行排队队列卡片（默认隐藏，仅当用户实际发送了排队消息后展示单行队列条，支持一键切为打断）
            if (queuedPrompts.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(c.surface)
                        .border(0.5.dp, c.edge)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    queuedPrompts.forEachIndexed { idx, qItem ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(c.bg)
                                .border(0.5.dp, c.accent.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "⏳ 排队#${idx + 1}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = c.accent,
                            )
                            Text(
                                qItem.text.ifBlank { "[附件 ${qItem.attachments.size} 个]" },
                                style = VibeLinkType.Caption,
                                color = c.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFFD97706).copy(alpha = 0.16f))
                                    .border(0.5.dp, Color(0xFFD97706), RoundedCornerShape(6.dp))
                                    .clickable {
                                        val removed = BotTeamStore.removeQueuedPrompt(draftKey, qItem.id)
                                        queuedPrompts = BotTeamStore.getQueuedPrompts(draftKey)
                                        if (removed != null) {
                                            executeSendNow(removed.text, removed.attachments, interruptFirst = true)
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    "⚡ 改为打断",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFD97706),
                                )
                            }
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(c.fill)
                                    .clickable {
                                        val removed = BotTeamStore.removeQueuedPrompt(draftKey, qItem.id)
                                        queuedPrompts = BotTeamStore.getQueuedPrompts(draftKey)
                                        if (removed != null && input.isBlank()) {
                                            input = removed.text
                                            if (removed.attachments.isNotEmpty() && pendingAttachments.isEmpty()) {
                                                pendingAttachments = removed.attachments
                                            }
                                        }
                                    }
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            ) {
                                Text("✕ 撤回", fontSize = 11.sp, color = c.ink3)
                            }
                        }
                    }
                }
            }

            Composer(
                modifier = Modifier.fillMaxWidth(),
                text = input,
                onText = {
                    input = it
                    BotTeamStore.setDraftText(ctx, draftKey, it)
                },
                mode = if (isRunningNow) ComposerMode.Busy else ComposerMode.Idle,
                hasHistory = messages.isNotEmpty(),
                onSend = triggerSend,
                onStop = {
                    val s = session ?: return@Composer
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { RemoteApi.interrupt(hub, token, s.device, s.id, agent = s.agent) }
                        }.onSuccess {
                            session = s.copy(running = false)
                            latest = latest.filterNot { it.id.startsWith("local-assistant-") }
                            loadMessages(s)
                        }.onFailure(::fail)
                    }
                },
                attachPanel = if (canSend) ({
                    AttachPanelHost(
                        selected = trayAttachments,
                        onPicked = { uris ->
                            scope.launch {
                                val loaded = withContext(Dispatchers.IO) {
                                    uris.mapNotNull { readUriAsAttachment(ctx, Uri.parse(it)) }
                                }
                                if (loaded.isEmpty()) {
                                    Toast.makeText(ctx, "文件读取失败或超过 20MB 限制", Toast.LENGTH_SHORT).show()
                                } else {
                                    pendingAttachments = (pendingAttachments + loaded).distinctBy { it.name + it.size }
                                }
                            }
                        },
                        onToggle = { item ->
                            val existing = pendingAttachments.firstOrNull { att ->
                                AttachTray.matchesPhoto(
                                    PendingAttachment(
                                        uri = att.id,
                                        name = att.name,
                                        size = att.size,
                                        photo = att.mime.startsWith("image/") || AttachmentMessage.isPhoto(att.name),
                                    ),
                                    item,
                                )
                            }
                            if (existing != null) {
                                pendingAttachments = pendingAttachments.filterNot { it.id == existing.id }
                                true
                            } else {
                                scope.launch {
                                    val loaded = withContext(Dispatchers.IO) {
                                        readUriAsAttachment(ctx, Uri.parse(item.uri))
                                    }
                                    if (loaded != null) {
                                        pendingAttachments = pendingAttachments + loaded.copy(id = item.uri)
                                    }
                                }
                                true
                            }
                        },
                    )
                }) else null,
                attachments = emptyList(), // 顶部已有带 🖍 涂鸦标注的缩略图预览条，不重复渲染 PendingTray
                backdrop = glassBackdrop,
                placeholderOverride = when {
                    pendingAttachments.isNotEmpty() -> "已选 ${pendingAttachments.size} 个附件，输入说明或直接点发送…"
                    isRunningNow && busySendMode != "interrupt" -> "⏳ 上一条执行中，发送将默认排队顺延（上方可切为打断）…"
                    isRunningNow && busySendMode == "interrupt" -> "⚡ 打断模式：发送将立即中断当前任务并执行新指令…"
                    canSend -> "发消息或点左侧 + 传相机/相册/文件给 $title…"
                    session == null -> "请先在右上角 + 新建会话"
                    else -> "这个 agent 只读"
                },
                onVoiceInput = {
                    runCatching {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话…")
                        }
                        voiceLauncher.launch(intent)
                    }.onFailure {
                        Toast.makeText(ctx, "当前设备不支持系统语音识别", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }

    if (showEditBotDialog && botFriend != null) {
        CreateOrEditBotDialog(
            initialBot = botFriend,
            onDismiss = { showEditBotDialog = false },
            onSaved = { showEditBotDialog = false },
        )
    }

    if (showVoiceCallDialog && botFriend != null) {
        BotVoiceCallDialog(
            bot = botFriend,
            onDismiss = { showVoiceCallDialog = false },
        )
    }

    // 远程工作区文件树 & Git Diff 抽屉
    workspaceInspectorTab?.let { initTab ->
        WorkspaceInspectorDialog(
            hub = hub,
            token = token,
            device = effectiveDevice,
            initialCwd = effectiveCwd,
            initialTab = initTab,
            onDismiss = { workspaceInspectorTab = null },
            onSelectCwd = { newCwd ->
                session = session?.copy(cwd = newCwd)
                Toast.makeText(ctx, "工作目录已切换为: $newCwd", Toast.LENGTH_SHORT).show()
            },
            onInsertFileMention = { filePath ->
                input = if (input.isBlank()) "@`$filePath` " else "$input @`$filePath` "
            },
            onRequestUploadToDir = { dir ->
                directUploadTargetDir = dir
                attachmentLauncher.launch("*/*")
            },
        )
    }

    // 远端文件在线预览与下载弹窗
    viewingRemoteFilePath?.let { path ->
        RemoteFileViewerDialog(
            hub = hub,
            token = token,
            device = effectiveDevice,
            filePath = path,
            onDismiss = { viewingRemoteFilePath = null },
            onInsertMention = {
                input = if (input.isBlank()) "@`$path` " else "$input @`$path` "
                viewingRemoteFilePath = null
            },
        )
    }

    // 图片红笔涂鸦标注弹窗
    markupTarget?.let { target ->
        ImageMarkupDialog(
            initialBase64 = target.base64,
            fileName = target.name,
            onDismiss = { markupTarget = null },
            onConfirm = { annotatedB64 ->
                pendingAttachments = pendingAttachments.map {
                    if (it.id == target.id) it.copy(base64 = annotatedB64, mime = "image/png") else it
                }
                markupTarget = null
            },
        )
    }

    // 远程电脑桌面实时屏幕与键鼠遥控弹窗
    if (showRemoteDesktop) {
        RemoteDesktopDialog(
            hub = hub,
            token = token,
            device = effectiveDevice,
            onDismiss = { showRemoteDesktop = false },
        )
    }

    if (showNewSessionDialog) {
        NewRemoteSessionDialog(
            boundBot = botFriend,
            initialAgent = if (effectiveEngine in BotModelPolicy.engines.toSet()) effectiveEngine else "codex",
            initialDevice = effectiveDevice,
            initialCwd = effectiveCwd,
            recentCwds = recentCwds,
            onDismiss = { showNewSessionDialog = false },
            onCreated = { created ->
                showNewSessionDialog = false
                if (botFriend != null) {
                    BotTeamStore.bindSessionToBot(ctx, botFriend.id, created.id, created.title)
                }
                if (botFriend == null && created.agent != agentId) {
                    onSwitchAgent(created.agent, created.id)
                } else {
                    sessions = listOf(created) + sessions.filter { it.id != created.id }
                    session = created
                    latest = emptyList()
                    older = emptyList()
                    nextCursor = null
                    sentAt = System.currentTimeMillis()
                    scope.launch {
                        delay(400)
                        loadMessages(created)
                    }
                }
            },
        )
    }
}

/**
 * VibeLink 远程新建多引擎会话弹窗（含远端电脑目录树浏览器与首条附件支持）。
 */
@Composable
fun NewRemoteSessionDialog(
    boundBot: BotFriend? = null,
    initialAgent: String = "codex",
    initialDevice: String = "pc",
    initialCwd: String = "C:\\Users\\22771\\Desktop\\项目\\VibeLink",
    recentCwds: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onCreated: (RemoteApi.Session) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = VibeLinkTheme.colors
    val hub = remember { RemoteStore.hub(ctx) }
    val token = remember { RemoteStore.token(ctx) }

    var selectedAgent by remember { mutableStateOf(boundBot?.engine ?: initialAgent) }
    var selectedModel by remember { mutableStateOf(boundBot?.model ?: BotModelPolicy.defaultModel("")) }
    var selectedDevice by remember { mutableStateOf(initialDevice.ifBlank { "pc" }) }
    var devices by remember { mutableStateOf<List<RemoteApi.Device>>(emptyList()) }
    var cwd by remember { mutableStateOf(initialCwd) }
    var prompt by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var browsingCwd by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) {
            runCatching { RemoteApi.devices(hub, token) }.getOrDefault(emptyList())
        }
        devices = list
        val online = list.filter { it.online }
        if (boundBot == null && online.isNotEmpty() && online.none { it.id == selectedDevice }) {
            selectedDevice = online.first().id
        }
    }

    val engineOptions = BotModelPolicy.engines.map { it to BotModelPolicy.label(it) }

    val cwdPresets = remember(recentCwds) {
        if (com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION) {
            recentCwds.distinct()
        } else {
            (recentCwds + listOf(
                "D:\\AI\\VibeLink",
                "C:\\Users\\22771\\Desktop\\项目\\VibeLink",
                "C:\\Users\\22771\\cindy-root",
                "D:\\AI\\tbs-workbench",
            )).distinct()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        title = {
            Column {
                Text("新建远程会话", style = VibeLinkType.Title, color = c.ink)
                Text(if (boundBot != null) "使用 ${boundBot.name} 的模型、Agent 与工作区" else "选择运行模型与电脑工作目录", style = VibeLinkType.Hint, color = c.ink3)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Agent", style = VibeLinkType.Label, color = c.ink2)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    engineOptions.forEach { (id, label) ->
                        val active = selectedAgent == id
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) c.accent else c.surface)
                                .border(1.dp, if (active) c.accent else c.edge, RoundedCornerShape(8.dp))
                                .clickable(enabled = !creating && boundBot == null) { selectedAgent = id }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                label,
                                style = VibeLinkType.Caption,
                                color = if (active) c.onAccent else c.ink,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }

                Text("运行模型", style = VibeLinkType.Label, color = c.ink2)
                if (boundBot != null) {
                    Text(BotModelPolicy.modelLabel(boundBot.model), style = VibeLinkType.Body, color = c.ink)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BotModelPolicy.models.forEach { model ->
                            TextButton(enabled = !creating, onClick = { selectedModel = model }) {
                                Text((if (selectedModel == model) "✓ " else "") + BotModelPolicy.modelLabel(model))
                            }
                        }
                    }
                }

                if (devices.size > 1 && boundBot == null) {
                    Text("设备", style = VibeLinkType.Label, color = c.ink2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        devices.filter { it.online }.forEach { d ->
                            val active = selectedDevice == d.id
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) c.accent else c.surface)
                                    .border(1.dp, if (active) c.accent else c.edge, RoundedCornerShape(8.dp))
                                    .clickable(enabled = !creating) { selectedDevice = d.id }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    d.id,
                                    style = VibeLinkType.Caption,
                                    color = if (active) c.onAccent else c.ink,
                                )
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("工作目录 (cwd)", style = VibeLinkType.Label, color = c.ink2)
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(c.accent.copy(alpha = 0.14f))
                            .clickable(enabled = !creating && boundBot == null) { browsingCwd = true }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text("📁 浏览远端目录", style = VibeLinkType.Hint, color = c.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    cwdPresets.forEach { preset ->
                        val shortName = preset.substringAfterLast('\\').substringAfterLast('/').ifBlank { preset }
                        val active = cwd.trim() == preset
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (active) c.accent.copy(alpha = 0.16f) else c.surface)
                                .border(1.dp, if (active) c.accent else c.edge, RoundedCornerShape(6.dp))
                                .clickable(enabled = !creating && boundBot == null) { cwd = preset }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(shortName, style = VibeLinkType.Hint, color = if (active) c.accent else c.ink2)
                        }
                    }
                }
                OutlinedTextField(
                    value = cwd,
                    onValueChange = { cwd = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !creating && boundBot == null,
                    placeholder = { Text("C:\\Users\\22771\\Desktop\\项目\\VibeLink") },
                )

                Text("首条指令（可选，填好后直接开跑）", style = VibeLinkType.Label, color = c.ink2)
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    enabled = !creating,
                    placeholder = { Text("想让 ${agentDisplayName(selectedAgent)} 做什么？") },
                )

                err?.let {
                    Text(it, style = VibeLinkType.Hint, color = c.bad)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !creating,
                onClick = {
                    creating = true
                    err = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                if (boundBot != null) {
                                    val sid = BotTeamStore.ensureBotRemoteSession(
                                        context = ctx, bot = boundBot, taskTitle = prompt.trim().ifBlank { "新会话" },
                                        forceNew = true, initialText = prompt.trim(),
                                    )
                                    RemoteSessionCache.getCachedSessions(ctx, boundBot.engine).firstOrNull { it.id == sid }
                                        ?: error("会话已创建但缓存未就绪，请重新打开资料页")
                                } else RemoteApi.createSession(
                                    hub = hub,
                                    token = token,
                                    device = selectedDevice.ifBlank { "pc" },
                                    agent = selectedAgent,
                                    model = selectedModel,
                                    cwd = cwd.trim(),
                                    text = prompt.trim(),
                                )
                            }
                        }.onSuccess { created ->
                            creating = false
                            onCreated(created)
                        }.onFailure { e ->
                            creating = false
                            err = e.message ?: "创建失败"
                        }
                    }
                },
            ) {
                Text(if (creating) "创建中…" else "创建并进入", color = c.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                enabled = !creating,
                onClick = onDismiss,
            ) {
                Text("取消", color = c.ink3)
            }
        },
    )

    if (browsingCwd) {
        WorkspaceInspectorDialog(
            hub = hub,
            token = token,
            device = selectedDevice.ifBlank { "pc" },
            initialCwd = cwd.trim(),
            initialTab = 0,
            onDismiss = { browsingCwd = false },
            onSelectCwd = { picked ->
                cwd = picked
                browsingCwd = false
            },
            onInsertFileMention = { filePath ->
                prompt = if (prompt.isBlank()) "@`$filePath` " else "$prompt @`$filePath` "
                browsingCwd = false
            },
            onRequestUploadToDir = {},
        )
    }
}

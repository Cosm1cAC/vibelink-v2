package com.wangzan101.vibelink.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.vibelink.ui.components.BarIconButton
import com.wangzan101.vibelink.ui.components.GlassScrollToBottomButton
import com.wangzan101.vibelink.ui.components.PageBackground
import com.wangzan101.vibelink.ui.components.glassSource
import com.wangzan101.vibelink.ui.components.rememberGlassBackdrop
import com.wangzan101.vibelink.ui.components.rememberScrollToBottomState
import com.wangzan101.vibelink.ui.theme.VibeLinkIcons
import com.wangzan101.vibelink.ui.theme.VibeLinkShapes
import com.wangzan101.vibelink.ui.theme.VibeLinkTheme
import com.wangzan101.vibelink.ui.theme.VibeLinkType
import com.wangzan101.vibelink.remote.RemoteApi
import com.wangzan101.vibelink.remote.RemoteStore
import com.wangzan101.vibelink.remote.RichText
import com.wangzan101.vibelink.remote.TextExcerptDialog
import com.wangzan101.vibelink.remote.ThinkingBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * QQ/微信群聊风格的四宫格群头像
 */
@Composable
fun TeamGroupAvatar(
    group: TeamGroup,
    bots: List<BotFriend>,
    size: Dp = 52.dp,
    onClick: (() -> Unit)? = null,
) {
    val c = VibeLinkTheme.colors
    val members = group.botIds.mapNotNull { id -> bots.firstOrNull { it.id == id } }.take(4)
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(c.accent.copy(alpha = 0.14f))
            .border(0.5.dp, c.accent.copy(alpha = 0.3f), RoundedCornerShape(size * 0.28f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (members.size <= 1) {
            Text(
                "群",
                color = c.accent,
                fontSize = (size.value * 0.4f).sp,
                fontWeight = FontWeight.Bold,
            )
        } else {
            val cellSize = (size - 8.dp) / 2
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    members.getOrNull(0)?.let { AgentAvatar(it.id, it.name, cellSize) }
                    members.getOrNull(1)?.let { AgentAvatar(it.id, it.name, cellSize) }
                }
                if (members.size > 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        members.getOrNull(2)?.let { AgentAvatar(it.id, it.name, cellSize) }
                        members.getOrNull(3)?.let { AgentAvatar(it.id, it.name, cellSize) }
                    }
                }
            }
        }
    }
}

/**
 * 多 Bot 组成的 Team 群聊页面：
 * - 支持在群里 @任意职位 Bot 或开启「团队接力模式」由管理 Bot + 项目 Bot 依次协同
 * - 点击群内任意 Bot 的头像或气泡下方的「穿透干预底层会话」直接进入该 Bot 绑定的底层 Agent 会话或工位
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun TeamGroupChatScreen(
    groupId: String,
    onBack: () -> Unit,
    onOpenBotProfile: (String) -> Unit,
    onInspectSession: (agentEngine: String, sessionId: String) -> Unit,
) {
    val ctx = LocalContext.current
    val c = VibeLinkTheme.colors
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        BotTeamStore.ensureSeeded(ctx)
        BotTeamStore.syncWithHub(ctx)
    }
    val allBots by BotTeamStore.botsFlow.collectAsState()
    val allGroups by BotTeamStore.groupsFlow.collectAsState()
    val storeVersion by BotTeamStore.versionFlow.collectAsState()

    val group = remember(groupId, allGroups, storeVersion) {
        allGroups.firstOrNull { it.id == groupId } ?: BotTeamStore.getGroup(ctx, groupId)
    }
    val memberBots = remember(group, allBots, storeVersion) {
        val ids = group?.botIds.orEmpty()
        ids.mapNotNull { id -> allBots.firstOrNull { it.id == id } }
    }
    var messages by remember(groupId, storeVersion) {
        mutableStateOf(BotTeamStore.listGroupMessages(ctx, groupId))
    }
    val draftKey = remember(groupId) { "group:$groupId" }
    var input by remember(groupId) { mutableStateOf(BotTeamStore.getDraftText(ctx, draftKey)) }
    // 群协同模式："grok_420" (Captain拆解→主程执行→Lucas红队挑刺) | "relay" (管理+执行双人接力) | "single" (单人响应)
    var collabMode by remember { mutableStateOf("grok_420") }
    // Open Duet 委派权限模式："work" (全权实操落盘) | "consult" (只读咨询核查围栏)
    var delegationMode by remember { mutableStateOf("work") }
    // Open Duet 单次发言按需挂载的技能 ID 集合 (/skill)
    var attachedSkillIds by remember(groupId) { mutableStateOf(BotTeamStore.getDraftSkills(draftKey)) }
    var showSkillPicker by remember { mutableStateOf(false) }
    var learnSkillTarget by remember { mutableStateOf<Pair<BotFriend, TeamGroupMessage>?>(null) }
    var reworkTarget by remember { mutableStateOf<Pair<BotFriend, TeamGroupMessage>?>(null) }
    var excerptDialogTarget by remember { mutableStateOf<TeamGroupMessage?>(null) }
    // 多任务并发追踪：thinkingMsgId -> bot.id，支持同一群聊多条指令同时执行、多 Bot / 同 Bot 多工位并发
    var activeTaskBotMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val activeTaskJobs = remember { java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>() }
    val activeTaskSessions = remember { java.util.concurrent.ConcurrentHashMap<String, String>() }
    val runningBotIds = remember(activeTaskBotMap) { activeTaskBotMap.values.toSet() }
    val activeTaskCount = activeTaskBotMap.size
    // 群聊忙时发送策略："parallel" (默认：多任务并发执行) | "queue" (排队顺延) | "interrupt" (立即打断)
    var busySendMode by remember(draftKey) {
        mutableStateOf(BotTeamStore.getBusySendMode(draftKey, "parallel"))
    }
    var queuedPrompts by remember(draftKey) {
        mutableStateOf(BotTeamStore.getQueuedPrompts(draftKey))
    }
    var showEditGroup by remember { mutableStateOf(false) }
    var showRecruitBot by remember { mutableStateOf(false) }
    var presetAppliedToast by remember { mutableStateOf("") }
    var voiceCallBot by remember { mutableStateOf<BotFriend?>(null) }
    var attachOpen by remember { mutableStateOf(false) }
    var pendingAttachments by remember(groupId) { mutableStateOf(BotTeamStore.getDraftAttachments(draftKey)) }
    LaunchedEffect(groupId, input) {
        BotTeamStore.setDraftText(ctx, draftKey, input)
    }
    LaunchedEffect(groupId, pendingAttachments) {
        BotTeamStore.setDraftAttachments(draftKey, pendingAttachments)
    }
    LaunchedEffect(groupId, attachedSkillIds) {
        BotTeamStore.setDraftSkills(draftKey, attachedSkillIds)
    }
    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val matches = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotBlank()) {
                input = if (input.isBlank()) spoken else "$input $spoken"
            }
        }
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (messages.size).coerceAtLeast(0),
        initialFirstVisibleItemScrollOffset = 100_000,
    )
    var stick by remember { mutableStateOf(true) }
    var hasUserScrolledUp by remember(groupId) { mutableStateOf(false) }
    val glassBackdrop = rememberGlassBackdrop()
    val scrollToBottomState = rememberScrollToBottomState(listState)

    suspend fun scrollToBottomImmediate() {
        scrollToBottomState.hideImmediate()
        if (messages.isEmpty()) return
        val targetIdx = messages.size
        runCatching {
            listState.scrollToItem(targetIdx, scrollOffset = 100_000)
            androidx.compose.runtime.withFrameNanos { }
            listState.scrollToItem(targetIdx, scrollOffset = 100_000)
        }
        hasUserScrolledUp = false
        stick = true
    }

    fun refreshState() {
        messages = BotTeamStore.listGroupMessages(ctx, groupId)
    }

    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.canScrollForward,
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
            )
        }.collect { (scrolling, canForward, lastIdx) ->
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

    LaunchedEffect(
        groupId,
        messages.size,
        messages.lastOrNull()?.text?.length,
        messages.lastOrNull()?.reasoning?.length,
        messages.lastOrNull()?.tools?.size,
    ) {
        if (messages.isNotEmpty() && stick) {
            scrollToBottomImmediate()
        }
    }

    fun appendOrUpdateMessage(msg: TeamGroupMessage) {
        messages = BotTeamStore.upsertGroupMessage(ctx, groupId, msg)
        group?.let { g ->
            val previewBody = when {
                msg.text.isNotBlank() && !msg.text.startsWith("🔧 正在调用工具") -> msg.text
                msg.tools.isNotEmpty() -> "已调用 ${msg.tools.size} 步工具完成操作"
                msg.reasoning.isNotBlank() -> "已完成思考与操作"
                else -> "正在思考…"
            }
            val preview = if (msg.senderId == "user") "我: $previewBody" else "[${msg.senderJobTitle} ${msg.senderName}]: $previewBody"
            val updated = g.copy(lastText = preview.replace("\n", " ").take(60), lastAt = msg.at)
            BotTeamStore.upsertGroup(ctx, updated)
        }
    }

    // 后台自动同步挂起/后台执行中的群聊 Bot 消息，以及为历史已完成消息补齐思考过程与工具调用记录
    val backfilledMsgIds = remember(groupId) { mutableSetOf<String>() }
    LaunchedEffect(groupId) {
        while (true) {
            delay(2500L)
            val currentMsgs = BotTeamStore.listGroupMessages(ctx, groupId)
            val activeMsgIds = activeTaskBotMap.keys
            val pendingTargets = currentMsgs.filter { m ->
                m.senderId != "user" && m.id !in activeMsgIds && (
                    m.status == "thinking" ||
                        m.text.contains("仍在后台持续执行中") ||
                        m.text.contains("正在自己的工位会话")
                    )
            }
            val backfillTargets = currentMsgs.takeLast(10).filter { m ->
                m.senderId != "user" &&
                    m.status != "thinking" &&
                    m.linkedSessionId.isNotBlank() &&
                    m.reasoning.isBlank() &&
                    m.tools.isEmpty() &&
                    m.id !in backfilledMsgIds
            }
            if (pendingTargets.isNotEmpty() || backfillTargets.isNotEmpty()) {
                val hub = RemoteStore.hub(ctx)
                val token = RemoteStore.token(ctx)
                if (hub.isNotBlank() && token.isNotBlank()) {
                    for (pending in pendingTargets) {
                        val bot = BotTeamStore.getBot(ctx, pending.senderId)
                        val engine = pending.senderEngine.ifBlank { bot?.engine ?: "codex" }
                        val device = bot?.device?.ifBlank { "pc" } ?: "pc"
                        val sid = pending.linkedSessionId.ifBlank { bot?.activeSessionId.orEmpty() }
                        if (sid.isBlank()) continue
                        val remoteMsgs = runCatching {
                            withContext(Dispatchers.IO) {
                                RemoteApi.messages(hub, token, device, engine, sid)
                            }
                        }.getOrDefault(emptyList())
                        if (remoteMsgs.isNotEmpty()) {
                            RemoteSessionCache.putCachedMessages(ctx, sid, remoteMsgs)
                            val lastAssistant = remoteMsgs.lastOrNull { it.role == "assistant" }
                            if (lastAssistant != null) {
                                if (lastAssistant.at > 0L) {
                                    val cleanText = lastAssistant.text.trim()
                                    val isCorruptedReasoning = cleanText.startsWith("🔧 正在调用工具") ||
                                        cleanText.startsWith("🔧 已调用") ||
                                        (lastAssistant.reasoning.isNotBlank() && lastAssistant.reasoning.contains(cleanText))
                                    val effectiveText = if (isCorruptedReasoning) "" else cleanText
                                    if (effectiveText.isNotBlank() && !effectiveText.contains("只有 <bot_identity>")) {
                                        val needsInputQ = BotTeamStore.extractNeedsInput(effectiveText)
                                        appendOrUpdateMessage(
                                            pending.copy(
                                                text = effectiveText,
                                                reasoning = lastAssistant.reasoning.ifBlank { pending.reasoning },
                                                startedAt = if (lastAssistant.started > 0L) lastAssistant.started else pending.startedAt,
                                                tools = lastAssistant.tools.ifEmpty { pending.tools },
                                                linkedSessionId = sid,
                                                status = if (needsInputQ != null) "needs_input" else "done",
                                                at = maxOf(pending.at, lastAssistant.at),
                                            ),
                                        )
                                    } else if (effectiveText.contains("只有 <bot_identity>")) {
                                        appendOrUpdateMessage(
                                            pending.copy(
                                                text = "（旧会话参数截断已自动修复，请重新派发一次任务即可在全新工位会话中执行）",
                                                linkedSessionId = sid,
                                                status = "done",
                                            ),
                                        )
                                    } else {
                                        // 仅有思维链与工具调用，无答复正文；绝不能把 reasoning 填充到 text！
                                        val updatedReasoning = lastAssistant.reasoning.ifBlank { pending.reasoning }
                                        val updatedTools = lastAssistant.tools.ifEmpty { pending.tools }
                                        appendOrUpdateMessage(
                                            pending.copy(
                                                text = "",
                                                reasoning = updatedReasoning,
                                                startedAt = if (lastAssistant.started > 0L) lastAssistant.started else pending.startedAt,
                                                tools = updatedTools,
                                                linkedSessionId = sid,
                                                status = "done",
                                                at = maxOf(pending.at, lastAssistant.at),
                                            ),
                                        )
                                    }
                                } else if (lastAssistant.at == 0L) {
                                    val updatedReasoning = lastAssistant.reasoning.ifBlank { pending.reasoning }
                                    val updatedStarted = if (lastAssistant.started > 0L) lastAssistant.started else pending.startedAt
                                    val cleanText = lastAssistant.text.trim()
                                    val isCorruptedReasoning = cleanText.startsWith("🔧 正在调用工具") ||
                                        cleanText.startsWith("🔧 已调用") ||
                                        (updatedReasoning.isNotBlank() && updatedReasoning.contains(cleanText))
                                    val effectiveText = if (isCorruptedReasoning) "" else cleanText
                                    if (
                                        effectiveText != pending.text ||
                                        updatedReasoning != pending.reasoning ||
                                        lastAssistant.tools.size != pending.tools.size
                                    ) {
                                        appendOrUpdateMessage(
                                            pending.copy(
                                                text = effectiveText,
                                                reasoning = updatedReasoning,
                                                startedAt = updatedStarted,
                                                tools = lastAssistant.tools.ifEmpty { pending.tools },
                                                linkedSessionId = sid,
                                                status = "thinking",
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    for (hist in backfillTargets) {
                        backfilledMsgIds.add(hist.id)
                        val bot = BotTeamStore.getBot(ctx, hist.senderId)
                        val engine = hist.senderEngine.ifBlank { bot?.engine ?: "codex" }
                        val device = bot?.device?.ifBlank { "pc" } ?: "pc"
                        val sid = hist.linkedSessionId
                        val cached = RemoteSessionCache.getCachedMessages(ctx, sid).first
                        val remoteMsgs = if (cached.isNotEmpty()) {
                            cached
                        } else {
                            val fetched = runCatching {
                                withContext(Dispatchers.IO) {
                                    RemoteApi.messages(hub, token, device, engine, sid)
                                }
                            }.getOrDefault(emptyList())
                            if (fetched.isNotEmpty()) RemoteSessionCache.putCachedMessages(ctx, sid, fetched)
                            fetched
                        }
                        val matched = remoteMsgs.lastOrNull {
                            it.role == "assistant" && (it.text.trim() == hist.text.trim() || hist.text.startsWith(it.text.take(40)))
                        } ?: remoteMsgs.lastOrNull { it.role == "assistant" }
                        if (matched != null && (matched.reasoning.isNotBlank() || matched.tools.isNotEmpty())) {
                            appendOrUpdateMessage(
                                hist.copy(
                                    reasoning = matched.reasoning,
                                    startedAt = if (matched.started > 0L) matched.started else hist.startedAt,
                                    tools = matched.tools,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun triggerBotsForMessage(
        userText: String,
        targetBots: List<BotFriend>,
        overrideMode: String? = null,
        filesToSend: List<RemoteAttachment> = emptyList(),
        skillsOverride: List<String>? = null,
        interruptBusyFirst: Boolean = false,
    ) {
        if (targetBots.isEmpty()) return
        val skillsForTurn = skillsOverride ?: attachedSkillIds.toList()
        val filesJson = filesToSend.map { it.toJson() }

        suspend fun runSingleBotTask(
            bot: BotFriend,
            priorOutput: String,
        ): String {
            val wasBotAlreadyRunning = bot.id in activeTaskBotMap.values
            if (interruptBusyFirst && wasBotAlreadyRunning) {
                val hub = RemoteStore.hub(ctx)
                val token = RemoteStore.token(ctx)
                // 中断该 Bot 正在群里执行的任务协程与底层会话
                activeTaskBotMap.filterValues { it == bot.id }.keys.forEach { oldMsgId ->
                    activeTaskJobs.remove(oldMsgId)?.cancel()
                    val oldSid = activeTaskSessions.remove(oldMsgId).orEmpty().ifBlank { bot.activeSessionId }
                    if (oldSid.isNotBlank() && hub.isNotBlank() && token.isNotBlank()) {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                RemoteApi.interrupt(hub, token, bot.device.ifBlank { "pc" }, oldSid, bot.engine)
                            }
                        }
                    }
                    activeTaskBotMap = activeTaskBotMap - oldMsgId
                }
            }

            // 在 Grok 4.20 辩论流水线中，情报侦察(Harper)与红队审校(Lucas)默认走 consult 只读围栏，主程(Benjamin)走 work 实操
            val effectiveMode = overrideMode ?: when {
                delegationMode == "consult" -> "consult"
                collabMode == "grok_420" && (bot.id == "bot_qa" || bot.templateId == "tpl_lucas_critic" || bot.templateId == "tpl_harper_research" || bot.disabledTools.isNotEmpty()) -> "consult"
                else -> "work"
            }
            val isReworkTurn = userText.startsWith("[Rework")
            // 若该 Bot 当前已有另一条指令在执行且未选择打断，自动开启独立新工位会话实现多任务并发，绝不互相打断或覆盖！
            val forceNewForTask = (bot.lifecycle == BotLifecycle.PROJECT && !isReworkTurn) ||
                (wasBotAlreadyRunning && !interruptBusyFirst)
            val modeBadge = buildString {
                append(if (effectiveMode == "consult") "🔍 咨询只读 (Consult)" else "🛠️ 实操落盘 (Work)")
                if (wasBotAlreadyRunning && !interruptBusyFirst) append(" · 🚀 并行新工位")
            }
            val thinkingMsgId = "msg_" + UUID.randomUUID().toString().take(8)
            activeTaskBotMap = activeTaskBotMap + (thinkingMsgId to bot.id)
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let { job ->
                activeTaskJobs[thinkingMsgId] = job
            }
            val baseHeader = "正在自己的工位会话 (${agentDisplayName(bot.engine)} · $modeBadge) 中处理任务…"
            var currentLinkedSid = if (forceNewForTask) "" else bot.activeSessionId
            if (currentLinkedSid.isNotBlank()) {
                activeTaskSessions[thinkingMsgId] = currentLinkedSid
            }
            val turnStartMs = System.currentTimeMillis()
            var latestReasoning = ""
            var latestStartedAt = turnStartMs
            var latestTools = emptyList<RemoteApi.ToolCall>()
            val thinkingMsg = TeamGroupMessage(
                id = thinkingMsgId,
                senderId = bot.id,
                senderName = bot.name,
                senderJobTitle = bot.jobTitle,
                senderEngine = bot.engine,
                text = "",
                reasoning = baseHeader,
                startedAt = turnStartMs,
                at = turnStartMs,
                linkedSessionId = currentLinkedSid,
                status = "thinking",
            )
            appendOrUpdateMessage(thinkingMsg)

            val recentContext = messages
                .filter { it.id != thinkingMsgId && (it.status == "done" || it.status == "needs_input") }
                .takeLast(6)
                .joinToString("\n") { m ->
                    val who = if (m.senderId == "user") "用户(群主)" else "${m.senderName}[${m.senderJobTitle}]"
                    "$who: ${m.text.take(260)}"
                } + if (priorOutput.isNotBlank()) "\n\n【前序协作 Bot 刚输出的结果】\n$priorOutput" else ""

            try {
                val (sessionId, replyText) = BotTeamStore.executeBotTurn(
                    context = ctx,
                    bot = bot,
                    userMessage = userText,
                    groupContextSummary = recentContext,
                    mode = effectiveMode,
                    attachedSkillIds = skillsForTurn,
                    files = filesJson,
                    forceNewSession = forceNewForTask,
                    onSessionLinked = { linkedSid ->
                        currentLinkedSid = linkedSid
                        if (linkedSid.isNotBlank()) {
                            activeTaskSessions[thinkingMsgId] = linkedSid
                        }
                        scope.launch(Dispatchers.Main) {
                            appendOrUpdateMessage(
                                thinkingMsg.copy(
                                    text = "",
                                    reasoning = latestReasoning.ifBlank { baseHeader },
                                    startedAt = latestStartedAt,
                                    tools = latestTools,
                                    linkedSessionId = linkedSid,
                                    status = "thinking",
                                ),
                            )
                        }
                    },
                    onProgress = { progressText ->
                        if (latestReasoning.isBlank() && latestTools.isEmpty()) {
                            scope.launch(Dispatchers.Main) {
                                appendOrUpdateMessage(
                                    thinkingMsg.copy(
                                        text = "",
                                        reasoning = "$baseHeader\n\n$progressText",
                                        startedAt = latestStartedAt,
                                        at = System.currentTimeMillis(),
                                        linkedSessionId = currentLinkedSid,
                                        status = "thinking",
                                    ),
                                )
                            }
                        }
                    },
                    onTurnUpdate = { turnMsg ->
                        if (turnMsg.reasoning.isNotBlank()) latestReasoning = turnMsg.reasoning
                        if (turnMsg.started in (turnStartMs - 10_000L)..turnStartMs) {
                            latestStartedAt = turnMsg.started
                        }
                        if (turnMsg.tools.isNotEmpty()) latestTools = turnMsg.tools
                        scope.launch(Dispatchers.Main) {
                            appendOrUpdateMessage(
                                thinkingMsg.copy(
                                    text = turnMsg.text,
                                    reasoning = latestReasoning.ifBlank { baseHeader },
                                    startedAt = latestStartedAt,
                                    tools = latestTools,
                                    at = System.currentTimeMillis(),
                                    linkedSessionId = currentLinkedSid,
                                    status = "thinking",
                                ),
                            )
                        }
                    },
                )
                val needsInputQ = BotTeamStore.extractNeedsInput(replyText)
                appendOrUpdateMessage(
                    thinkingMsg.copy(
                        text = replyText,
                        reasoning = latestReasoning,
                        startedAt = latestStartedAt,
                        tools = latestTools,
                        at = System.currentTimeMillis(),
                        linkedSessionId = sessionId,
                        status = if (needsInputQ != null) "needs_input" else "done",
                    ),
                )
                return "[${bot.jobTitle} ${bot.name}]:\n$replyText"
            } finally {
                activeTaskBotMap = activeTaskBotMap - thinkingMsgId
                activeTaskJobs.remove(thinkingMsgId)
                activeTaskSessions.remove(thinkingMsgId)
            }
        }

        // 判断本次指令内的多个目标 Bot 是否同时并发执行：
        // - 当处于「🚀 多Bot并发」模式，或用户在单条消息中同时 @点名了多个 Bot 时，所有目标 Bot 同时并发启动！
        // - 当处于「🧠 四阶段协同」或「⚡ 双人接力」且未手动点名多人时，本条指令内部按流水线接力，但不同指令之间完全并发！
        val runTargetsInParallel = collabMode == "parallel" || (
            targetBots.size > 1 && targetBots.count { b ->
                userText.contains("@${b.name}") || userText.contains("@${b.jobTitle}")
            } > 1
            )

        if (runTargetsInParallel && targetBots.size > 1) {
            targetBots.forEach { bot ->
                scope.launch {
                    runSingleBotTask(bot, priorOutput = "")
                }
            }
        } else {
            scope.launch {
                var priorOutput = ""
                for (bot in targetBots) {
                    priorOutput = runSingleBotTask(bot, priorOutput = priorOutput)
                }
            }
        }
    }

    fun resolveTargetsForText(effectiveUserText: String): List<BotFriend> {
        val mentioned = memberBots.filter { b ->
            effectiveUserText.contains("@${b.name}") || effectiveUserText.contains("@${b.jobTitle}")
        }
        return when {
            mentioned.isNotEmpty() -> mentioned
            collabMode == "parallel" -> memberBots.ifEmpty { emptyList() }
            collabMode == "grok_420" -> {
                val captain = memberBots.firstOrNull { it.id == "bot_cto" || it.templateId == "tpl_grok_cos" || it.lifecycle == BotLifecycle.MANAGEMENT }
                val executor = memberBots.firstOrNull {
                    it.id != captain?.id && (it.id == "bot_dev" || it.templateId == "tpl_benjamin_code" || it.disabledTools.isEmpty())
                }
                val critic = memberBots.firstOrNull {
                    it.id != captain?.id && it.id != executor?.id &&
                        (it.id == "bot_qa" || it.templateId == "tpl_lucas_critic" || it.disabledTools.isNotEmpty())
                }
                listOfNotNull(captain, executor, critic).distinctBy { it.id }.ifEmpty {
                    listOfNotNull(memberBots.firstOrNull())
                }
            }
            collabMode == "relay" -> {
                val mgr = memberBots.firstOrNull { it.lifecycle == BotLifecycle.MANAGEMENT }
                val proj = memberBots.firstOrNull { it.lifecycle == BotLifecycle.PROJECT && it.id != mgr?.id }
                listOfNotNull(mgr, proj).ifEmpty { listOfNotNull(memberBots.firstOrNull()) }
            }
            else -> {
                val mgr = memberBots.firstOrNull { it.lifecycle == BotLifecycle.MANAGEMENT } ?: memberBots.firstOrNull()
                listOfNotNull(mgr)
            }
        }
    }

    fun dispatchGroupInstructionNow(
        rawText: String,
        filesToSend: List<RemoteAttachment>,
        skillsForTurn: List<String>,
        targetBotIds: List<String> = emptyList(),
        interruptBusyFirst: Boolean = false,
    ) {
        val text = rawText.trim()
        if (text.isBlank() && filesToSend.isEmpty()) return
        val attachmentBadge = if (filesToSend.isEmpty()) "" else {
            filesToSend.joinToString("\n") { "📎 ${it.name} (${it.size / 1024} KB)" } +
                if (text.isNotBlank()) "\n\n" else ""
        }
        val effectiveUserText = if (text.isNotBlank()) text else "请查看并处理我上传的附件：${filesToSend.joinToString(", ") { it.name }}"
        val userMsg = TeamGroupMessage(
            id = "msg_" + UUID.randomUUID().toString().take(8),
            senderId = "user",
            senderName = "我",
            text = attachmentBadge + text,
            at = System.currentTimeMillis(),
            status = "done",
        )
        appendOrUpdateMessage(userMsg)
        val targets = if (targetBotIds.isNotEmpty()) {
            memberBots.filter { it.id in targetBotIds }.ifEmpty { resolveTargetsForText(effectiveUserText) }
        } else {
            resolveTargetsForText(effectiveUserText)
        }
        triggerBotsForMessage(
            userText = effectiveUserText,
            targetBots = targets,
            filesToSend = filesToSend,
            skillsOverride = skillsForTurn,
            interruptBusyFirst = interruptBusyFirst,
        )
    }

    // 当群聊排队队列非空且当前所有并发任务完成时，自动顺延派发下一条排队指令
    LaunchedEffect(activeTaskCount, queuedPrompts.size) {
        if (activeTaskCount == 0 && queuedPrompts.isNotEmpty()) {
            delay(400L)
            if (activeTaskBotMap.isEmpty()) {
                val nextItem = BotTeamStore.popNextQueuedPrompt(draftKey)
                queuedPrompts = BotTeamStore.getQueuedPrompts(draftKey)
                if (nextItem != null) {
                    dispatchGroupInstructionNow(
                        rawText = nextItem.text,
                        filesToSend = nextItem.attachments,
                        skillsForTurn = nextItem.skillIds,
                        targetBotIds = nextItem.targetBotIds,
                        interruptBusyFirst = false,
                    )
                }
            }
        }
    }

    fun sendUserMessage(rawText: String) {
        val text = rawText.trim()
        val filesToSend = pendingAttachments
        if (text.isBlank() && filesToSend.isEmpty()) return
        val currentSkills = attachedSkillIds.toList()
        input = ""
        pendingAttachments = emptyList()
        BotTeamStore.clearDraft(ctx, draftKey)
        attachOpen = false

        val effectiveUserText = if (text.isNotBlank()) text else "请查看并处理我上传的附件：${filesToSend.joinToString(", ") { it.name }}"
        val targets = resolveTargetsForText(effectiveUserText)
        if (activeTaskCount > 0 && busySendMode == "queue") {
            val qItem = QueuedBotPrompt(
                text = text,
                attachments = filesToSend,
                skillIds = currentSkills,
                targetBotIds = targets.map { it.id },
            )
            queuedPrompts = BotTeamStore.enqueuePrompt(draftKey, qItem)
            Toast.makeText(
                ctx,
                "⏳ 已加入群聊排队队列 (#${queuedPrompts.size})，也可点击「🚀 立即并发」或「⚡ 改为打断」",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        dispatchGroupInstructionNow(
            rawText = text,
            filesToSend = filesToSend,
            skillsForTurn = currentSkills,
            targetBotIds = targets.map { it.id },
            interruptBusyFirst = activeTaskCount > 0 && busySendMode == "interrupt",
        )
    }

    val currentGroup = group
    PageBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // 顶栏
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarIconButton(VibeLinkIcons.Back, "返回", onBack)
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { showEditGroup = true }
                        .padding(horizontal = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${currentGroup?.name ?: "Team 群聊"} (${memberBots.size})",
                            style = VibeLinkType.Title,
                            color = c.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (activeTaskCount > 0) {
                            Spacer(Modifier.width(6.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 2.dp,
                                color = c.accent,
                            )
                        }
                    }
                    Text(
                        memberBots.joinToString(" · ") { "${it.name}[${it.jobTitle}]" }.ifBlank { "点击添加 Bot 群成员" },
                        style = VibeLinkType.Hint,
                        color = c.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BarIconButton(VibeLinkIcons.Plus, "招募新 Bot", { showRecruitBot = true })
                BarIconButton(VibeLinkIcons.Gear, "群设置与成员管理", { showEditGroup = true })
            }

            // 群成员工位快捷栏（点击头像进 Bot 工位/干预底层会话，点击 @ 点名）
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.surface)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 协同模式切换胶囊（Grok 4.20 四阶段辩论 / 多Bot并发 / 双人接力 / 单人响应）
                Box(
                    Modifier
                        .clip(VibeLinkShapes.Pill)
                        .background(if (collabMode != "single") c.accent.copy(alpha = 0.14f) else c.fill)
                        .border(0.5.dp, if (collabMode != "single") c.accent else c.edge, VibeLinkShapes.Pill)
                        .clickable {
                            collabMode = when (collabMode) {
                                "grok_420" -> "parallel"
                                "parallel" -> "relay"
                                "relay" -> "single"
                                else -> "grok_420"
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        when (collabMode) {
                            "grok_420" -> "🧠 四阶段协同: 拆解→执行→红队挑刺"
                            "parallel" -> "🚀 多Bot并发: 全员/点名同时并行"
                            "relay" -> "⚡ 双人接力: 管理+主程"
                            else -> "🎯 单人响应模式"
                        },
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (collabMode != "single") c.accent else c.ink2,
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                // 一键重置标准专家角色配置胶囊
                Box(
                    Modifier
                        .clip(VibeLinkShapes.Pill)
                        .background(c.fill)
                        .border(0.5.dp, c.edge, VibeLinkShapes.Pill)
                        .clickable {
                            scope.launch {
                                BotTeamStore.applyGrok420Preset(ctx)
                                refreshState()
                                presetAppliedToast = "✅ 已同步 Gemini/Claude/GPT/GLM 标准配置"
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        if (presetAppliedToast.isNotBlank()) presetAppliedToast else "✨ 重置团队角色配置",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = c.ink2,
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                memberBots.forEach { bot ->
                    val isBusy = bot.id in runningBotIds
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.bg)
                            .border(
                                0.5.dp,
                                if (isBusy) c.accent else c.edge,
                                RoundedCornerShape(12.dp),
                            )
                            // 点击成员卡片主体直接进入与该 Bot 的 1 对 1 聊天窗口，点击头像进入工位资料页
                            .clickable { onInspectSession(bot.id, bot.activeSessionId) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AgentAvatar(bot.id, bot.name, 26.dp, onClick = { onOpenBotProfile(bot.id) })
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(bot.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.ink, maxLines = 1, softWrap = false)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    bot.jobTitle,
                                    fontSize = 10.sp,
                                    color = Color(bot.lifecycle.badgeColorHex),
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                            Text(
                                if (isBusy) "● 正在执行…" else "${bot.lifecycle.label} · ${agentDisplayName(bot.engine)}",
                                fontSize = 9.5.sp,
                                color = if (isBusy) c.accent else c.ink3,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .clip(VibeLinkShapes.Pill)
                                .background(c.accent.copy(alpha = 0.12f))
                                .clickable {
                                    if (!input.contains("@${bot.name}")) {
                                        input = ("$input @${bot.name} ").trimStart()
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text("@TA", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = c.accent, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            // 群消息列表
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(scrollToBottomState.nestedScrollConnection),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .glassSource(glassBackdrop, c.bg)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(messages, key = { it.id }) { msg ->
                        if (msg.senderId == "user") {
                            // 用户自己的群发言（自适应宽度上限 285dp，支持长按自由选词截取 + 一键复制全文）
                            Column(
                                Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.End,
                            ) {
                                CompositionLocalProvider(
                                    LocalTextSelectionColors provides TextSelectionColors(
                                        c.accent,
                                        c.onAccent.copy(alpha = 0.35f),
                                    ),
                                ) {
                                    SelectionContainer {
                                        Box(
                                            Modifier
                                                .widthIn(max = 285.dp)
                                                .clip(VibeLinkShapes.UserBubble)
                                                .background(c.accent)
                                                .combinedClickable(
                                                    onClick = {},
                                                    onLongClick = { excerptDialogTarget = msg },
                                                )
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                        ) {
                                            Text(msg.text, style = VibeLinkType.Body, color = c.onAccent)
                                        }
                                    }
                                }
                                Row(
                                    Modifier.padding(top = 3.dp, end = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "📋 复制",
                                        style = VibeLinkType.Hint,
                                        color = c.ink3,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable {
                                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                                    as android.content.ClipboardManager
                                                cm.setPrimaryClip(android.content.ClipData.newPlainText("message", msg.text))
                                                Toast.makeText(ctx, "已复制消息", Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(horizontal = 4.dp, vertical = 1.dp),
                                    )
                                    Text(
                                        "✂️ 选词截取",
                                        style = VibeLinkType.Hint,
                                        color = c.accent,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable { excerptDialogTarget = msg }
                                            .padding(horizontal = 4.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        } else {
                            // Bot 群成员发言（实时动态读取最新昵称与职位头衔 + 一键穿透干预底层会话）
                            val bot = allBots.firstOrNull { it.id == msg.senderId }
                            val displayName = bot?.name ?: msg.senderName
                            val displayJobTitle = bot?.jobTitle ?: msg.senderJobTitle
                            val displayEngine = bot?.engine ?: msg.senderEngine
                            val lifecycle = bot?.lifecycle ?: BotLifecycle.PROJECT
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.Top,
                            ) {
                                AgentAvatar(
                                    id = msg.senderId,
                                    name = displayName,
                                    size = 40.dp,
                                    onClick = { onOpenBotProfile(msg.senderId) },
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    // 发言人姓名 + 职位徽章 + 生命周期 + 驱动引擎
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            displayName,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = c.ink,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (displayJobTitle.isNotBlank()) {
                                            Box(
                                                Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Color(lifecycle.badgeColorHex).copy(alpha = 0.14f))
                                                    .padding(horizontal = 6.dp, vertical = 1.5.dp),
                                            ) {
                                                Text(
                                                    displayJobTitle,
                                                    fontSize = 10.5.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(lifecycle.badgeColorHex),
                                                    maxLines = 1,
                                                    softWrap = false,
                                                )
                                            }
                                        }
                                        Text(
                                            "${lifecycle.label} · ${agentDisplayName(displayEngine)}",
                                            fontSize = 10.5.sp,
                                            color = c.ink3,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    // 消息气泡
                                    val needsInputQuestion = remember(msg.text, msg.status) {
                                        if (msg.status == "thinking") null else BotTeamStore.extractNeedsInput(msg.text)
                                    }
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(VibeLinkShapes.AnswerBubble)
                                            .background(c.surface)
                                            .border(
                                                if (needsInputQuestion != null) 1.dp else 0.5.dp,
                                                if (needsInputQuestion != null) Color(0xFFD97706) else c.edge,
                                                VibeLinkShapes.AnswerBubble,
                                            )
                                            .combinedClickable(
                                                onClick = {},
                                                onLongClick = {
                                                    if (msg.text.isNotBlank() || msg.reasoning.isNotBlank()) {
                                                        excerptDialogTarget = msg
                                                    }
                                                },
                                            )
                                            .padding(12.dp),
                                    ) {
                                        val isThinking = msg.status == "thinking"
                                        val rawText = msg.text.trim()
                                        val rawReasoning = msg.reasoning.trim()
                                        val isCorruptedReasoning = rawText.isNotBlank() && (
                                            rawText == rawReasoning ||
                                            rawText.startsWith("🔧 正在调用工具") ||
                                            rawText.startsWith("🔧 已调用") ||
                                            (rawReasoning.isNotBlank() && rawReasoning.contains(rawText))
                                        )
                                        val bodyText = when {
                                            isThinking && rawText.startsWith("正在自己的工位会话") -> ""
                                            isCorruptedReasoning -> "" // 绝不把思维链或工具调用当成正文输出！
                                            else -> rawText
                                        }
                                        val fallbackReasoning = when {
                                            rawReasoning.isNotBlank() -> rawReasoning
                                            isCorruptedReasoning -> rawText // 若旧缓存里 reasoning 为空而 text 存了思维链，归位到 reasoning 供折叠块渲染
                                            isThinking && rawText.startsWith("正在自己的工位会话") -> rawText
                                            isThinking -> "正在自己的工位会话 (${agentDisplayName(displayEngine)}) 中分析与处理任务…"
                                            msg.tools.isEmpty() && msg.linkedSessionId.isNotBlank() ->
                                                "已在底层 ${agentDisplayName(displayEngine)} 工位会话 (${msg.linkedSessionId.take(6)}) 完成上下文分析与推理。"
                                            else -> ""
                                        }
                                        val syntheticMsg = remember(
                                            msg.id,
                                            bodyText,
                                            fallbackReasoning,
                                            msg.tools,
                                            msg.startedAt,
                                            msg.at,
                                            isThinking,
                                        ) {
                                            RemoteApi.Message(
                                                role = "assistant",
                                                text = bodyText,
                                                reasoning = fallbackReasoning,
                                                at = if (isThinking) 0L else msg.at,
                                                images = emptyList(),
                                                id = msg.id,
                                                started = if (msg.startedAt > 0L) msg.startedAt else msg.at,
                                                tools = msg.tools,
                                            )
                                        }
                                        if (syntheticMsg.reasoning.isNotBlank() || syntheticMsg.tools.isNotEmpty() || isThinking) {
                                            ThinkingBlock(
                                                m = syntheticMsg,
                                                streaming = isThinking,
                                                onOpenRemoteFile = null,
                                            )
                                            if (bodyText.isNotBlank()) Spacer(Modifier.height(8.dp))
                                        }
                                        if (bodyText.isNotBlank()) {
                                            RichText(bodyText, c.ink)
                                        }

                                        if (needsInputQuestion != null) {
                                            Spacer(Modifier.height(10.dp))
                                            Column(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(Color(0xFFD97706).copy(alpha = 0.14f))
                                                    .border(0.5.dp, Color(0xFFD97706).copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                                    .padding(10.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Text(
                                                    "🙋 待你拍板 (NEEDS INPUT 挂起协议)",
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFFD97706),
                                                )
                                                Text(
                                                    needsInputQuestion,
                                                    fontSize = 12.sp,
                                                    color = c.ink,
                                                )
                                                Box(
                                                    Modifier
                                                        .clip(VibeLinkShapes.Pill)
                                                        .background(Color(0xFFD97706))
                                                        .clickable {
                                                            input = "@$displayName 针对你的待确认问题「${needsInputQuestion.take(36)}」，我的拍板决定是："
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                                ) {
                                                    Text(
                                                        "💬 立即拍板答复并继续执行",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = Color.White,
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(Modifier.height(10.dp))
                                        // 穿透干预操作栏
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            val targetSession = msg.linkedSessionId.ifBlank { bot?.activeSessionId.orEmpty() }
                                            Box(
                                                Modifier
                                                    .clip(VibeLinkShapes.Pill)
                                                    .background(c.accent.copy(alpha = 0.12f))
                                                    .clickable {
                                                        onInspectSession(msg.senderId, targetSession)
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                            ) {
                                                Text(
                                                    if (targetSession.isNotBlank()) "🔍 穿透底层会话 (${targetSession.take(6)})"
                                                    else "💬 私聊 / 进入底层 ${agentDisplayName(displayEngine)} 会话",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = c.accent,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                )
                                            }

                                            if (msg.status == "thinking") {
                                                Box(
                                                    Modifier
                                                        .clip(VibeLinkShapes.Pill)
                                                        .background(Color(0xFFD97706).copy(alpha = 0.15f))
                                                        .clickable {
                                                            activeTaskJobs.remove(msg.id)?.cancel()
                                                            activeTaskBotMap = activeTaskBotMap - msg.id
                                                            val sidToStop = activeTaskSessions.remove(msg.id).orEmpty().ifBlank { targetSession }
                                                            scope.launch {
                                                                val hub = RemoteStore.hub(ctx)
                                                                val token = RemoteStore.token(ctx)
                                                                if (sidToStop.isNotBlank() && hub.isNotBlank() && token.isNotBlank()) {
                                                                    runCatching {
                                                                        withContext(Dispatchers.IO) {
                                                                            RemoteApi.interrupt(
                                                                                hub = hub,
                                                                                token = token,
                                                                                device = bot?.device?.ifBlank { "pc" } ?: "pc",
                                                                                session = sidToStop,
                                                                                agent = displayEngine,
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                                appendOrUpdateMessage(
                                                                    msg.copy(
                                                                        text = msg.text.ifBlank { "（已手动停止该任务）" },
                                                                        status = "done",
                                                                        at = System.currentTimeMillis(),
                                                                    ),
                                                                )
                                                            }
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                                ) {
                                                    Text(
                                                        "⏹ 停止此任务",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = Color(0xFFD97706),
                                                        maxLines = 1,
                                                        softWrap = false,
                                                    )
                                                }
                                            }

                                            if (msg.status != "thinking" && msg.text.isNotBlank()) {
                                                Box(
                                                    Modifier
                                                        .clip(VibeLinkShapes.Pill)
                                                        .background(c.fill)
                                                        .clickable {
                                                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                                                as android.content.ClipboardManager
                                                            cm.setPrimaryClip(android.content.ClipData.newPlainText("message", msg.text))
                                                            Toast.makeText(ctx, "已复制全文", Toast.LENGTH_SHORT).show()
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                                ) {
                                                    Text(
                                                        "📋 复制全文",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = c.ink2,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                    )
                                                }

                                                Box(
                                                    Modifier
                                                        .clip(VibeLinkShapes.Pill)
                                                        .background(c.accent.copy(alpha = 0.12f))
                                                        .clickable {
                                                            excerptDialogTarget = msg
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                                ) {
                                                    Text(
                                                        "✂️ 选词截取",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = c.accent,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                    )
                                                }
                                            }

                                            if (msg.status != "thinking" && msg.text.isNotBlank() && bot != null) {
                                                Box(
                                                    Modifier
                                                        .clip(VibeLinkShapes.Pill)
                                                        .background(Color(0xFF7C3AED).copy(alpha = 0.14f))
                                                        .clickable {
                                                            learnSkillTarget = bot to msg
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                                ) {
                                                    Text(
                                                        "✨ 提炼为技能",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = Color(0xFF7C3AED),
                                                        maxLines = 1,
                                                        softWrap = false,
                                                    )
                                                }

                                                Box(
                                                    Modifier
                                                        .clip(VibeLinkShapes.Pill)
                                                        .background(Color(0xFFD97706).copy(alpha = 0.14f))
                                                        .clickable {
                                                            reworkTarget = bot to msg
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                                ) {
                                                    Text(
                                                        "🔄 打回重做",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = Color(0xFFD97706),
                                                        maxLines = 1,
                                                        softWrap = false,
                                                    )
                                                }
                                            }

                                            Box(
                                                Modifier
                                                    .clip(VibeLinkShapes.Pill)
                                                    .background(c.fill)
                                                    .clickable { onOpenBotProfile(msg.senderId) }
                                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                            ) {
                                                Text(
                                                    "👤 工位与职位设置",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = c.ink2,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                )
                                            }

                                            if (msg.status != "thinking" && msg.text.isNotBlank()) {
                                                Box(
                                                    Modifier
                                                        .clip(VibeLinkShapes.Pill)
                                                        .background(c.fill)
                                                        .clickable {
                                                            scope.launch {
                                                                VoiceTtsPlayer.speakText(
                                                                    context = ctx,
                                                                    rawText = msg.text,
                                                                    botId = msg.senderId,
                                                                    summarize = true,
                                                                )
                                                            }
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                                ) {
                                                    Text(
                                                        "🔊 朗读",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = c.ink2,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                    )
                                                }
                                            }

                                            if (bot != null) {
                                                Box(
                                                    Modifier
                                                        .clip(VibeLinkShapes.Pill)
                                                        .background(c.fill)
                                                        .clickable { voiceCallBot = bot }
                                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                                ) {
                                                    Text(
                                                        "📞 语音通话",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = c.ink2,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                    )
                                                }
                                            }

                                            Box(
                                                Modifier
                                                    .clip(VibeLinkShapes.Pill)
                                                    .background(c.fill)
                                                    .clickable {
                                                        input = ("$input @$displayName ").trimStart()
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                            ) {
                                                Text(
                                                    "@追问TA",
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
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }

                GlassScrollToBottomButton(
                    state = scrollToBottomState,
                    backdrop = glassBackdrop,
                    hasMessages = messages.isNotEmpty(),
                    onClick = { scope.launch { scrollToBottomImmediate() } },
                )
            }

            // Open Duet 委派工具栏：Consult/Work 切换 + 按需挂载 /skill + 示例提问 Starter Chips
            val allGroupSkills = remember(memberBots) {
                memberBots.flatMap { b -> b.skills.map { sk -> b.name to sk } }.distinctBy { it.second.id }
            }
            val starterPrompts = remember(memberBots) {
                memberBots.flatMap { b ->
                    val reqs = b.exampleRequests.ifEmpty {
                        BotTeamStore.TEMPLATES.firstOrNull { it.id == b.templateId }?.exampleRequests.orEmpty()
                    }
                    reqs.map { req -> b.name to req }
                }.take(8)
            }
            if (showSkillPicker && allGroupSkills.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(c.surface)
                        .border(0.5.dp, c.edge)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "⚡ 按需挂载 6-Section 技能剧本 (/skill · 已挂载 ${attachedSkillIds.size} 项)",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.ink,
                        )
                        Text(
                            if (attachedSkillIds.isNotEmpty()) "清空挂载" else "收起",
                            fontSize = 11.sp,
                            color = c.accent,
                            modifier = Modifier.clickable {
                                if (attachedSkillIds.isNotEmpty()) attachedSkillIds = emptySet()
                                else showSkillPicker = false
                            },
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        allGroupSkills.forEach { (botName, sk) ->
                            val selected = sk.id in attachedSkillIds
                            Box(
                                Modifier
                                    .clip(VibeLinkShapes.Pill)
                                    .background(if (selected) c.accent else c.fill)
                                    .border(0.5.dp, if (selected) c.accent else c.edge, VibeLinkShapes.Pill)
                                    .clickable {
                                        attachedSkillIds = if (selected) attachedSkillIds - sk.id else attachedSkillIds + sk.id
                                    }
                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    "${if (selected) "✓ " else ""}[$botName] ${sk.name}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (selected) c.onAccent else c.ink,
                                )
                            }
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.surface)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Open Duet Consult / Work 权限切换胶囊
                val isConsult = delegationMode == "consult"
                Box(
                    Modifier
                        .clip(VibeLinkShapes.Pill)
                        .background(if (isConsult) Color(0xFFD97706).copy(alpha = 0.16f) else Color(0xFF1F9D55).copy(alpha = 0.14f))
                        .border(0.5.dp, if (isConsult) Color(0xFFD97706) else Color(0xFF1F9D55), VibeLinkShapes.Pill)
                        .clickable {
                            delegationMode = if (isConsult) "work" else "consult"
                        }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text(
                        if (isConsult) "🔍 咨询只读 (Consult)" else "🛠️ 实操落盘 (Work)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isConsult) Color(0xFFD97706) else Color(0xFF1F9D55),
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                // /skill 按需技能挂载胶囊
                Box(
                    Modifier
                        .clip(VibeLinkShapes.Pill)
                        .background(if (attachedSkillIds.isNotEmpty() || showSkillPicker) c.accent.copy(alpha = 0.14f) else c.fill)
                        .border(0.5.dp, if (attachedSkillIds.isNotEmpty() || showSkillPicker) c.accent else c.edge, VibeLinkShapes.Pill)
                        .clickable { showSkillPicker = !showSkillPicker }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text(
                        if (attachedSkillIds.isEmpty()) "⚡ /skill 挂载技能" else "⚡ 已挂 ${attachedSkillIds.size} 项技能",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (attachedSkillIds.isNotEmpty() || showSkillPicker) c.accent else c.ink2,
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                // Starter Prompt Chips (exampleRequests)
                starterPrompts.forEach { (botName, promptChip) ->
                    Box(
                        Modifier
                            .clip(VibeLinkShapes.Pill)
                            .background(c.fill)
                            .border(0.5.dp, c.edge, VibeLinkShapes.Pill)
                            .clickable {
                                input = "@$botName $promptChip"
                            }
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "💡 $promptChip",
                            fontSize = 11.sp,
                            color = c.ink2,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }

            // 底部统一输入栏（复用 VibeLink Composer + AttachPanelHost 拍照/相册/文件与语音输入）
            val hintMentions = remember(memberBots) {
                memberBots.take(2).joinToString(" ") { "@${it.name}" }.ifBlank { "@成员" }
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
            // 群聊排队队列提示栏（默认隐藏，仅当存在待执行排队项时展示单行紧凑卡片）
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
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                                    .background(c.accent.copy(alpha = 0.15f))
                                    .border(0.5.dp, c.accent, RoundedCornerShape(6.dp))
                                    .clickable {
                                        val removed = BotTeamStore.removeQueuedPrompt(draftKey, qItem.id)
                                        queuedPrompts = BotTeamStore.getQueuedPrompts(draftKey)
                                        if (removed != null) {
                                            dispatchGroupInstructionNow(
                                                rawText = removed.text,
                                                filesToSend = removed.attachments,
                                                skillsForTurn = removed.skillIds,
                                                targetBotIds = removed.targetBotIds,
                                                interruptBusyFirst = false,
                                            )
                                        }
                                    }
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    "🚀 立即并发",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = c.accent,
                                )
                            }
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFFD97706).copy(alpha = 0.16f))
                                    .border(0.5.dp, Color(0xFFD97706), RoundedCornerShape(6.dp))
                                    .clickable {
                                        val removed = BotTeamStore.removeQueuedPrompt(draftKey, qItem.id)
                                        queuedPrompts = BotTeamStore.getQueuedPrompts(draftKey)
                                        if (removed != null) {
                                            dispatchGroupInstructionNow(
                                                rawText = removed.text,
                                                filesToSend = removed.attachments,
                                                skillsForTurn = removed.skillIds,
                                                targetBotIds = removed.targetBotIds,
                                                interruptBusyFirst = true,
                                            )
                                        }
                                    }
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    "⚡ 改为打断",
                                    fontSize = 10.5.sp,
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
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                            ) {
                                Text("✕ 撤回", fontSize = 10.5.sp, color = c.ink3)
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
                mode = if (activeTaskCount > 0) ComposerMode.Busy else ComposerMode.Idle,
                hasHistory = messages.isNotEmpty(),
                onSend = { sendUserMessage(input) },
                onStop = {
                    val snapshotMap = activeTaskBotMap
                    activeTaskBotMap = emptyMap()
                    snapshotMap.forEach { (msgId, botId) ->
                        activeTaskJobs.remove(msgId)?.cancel()
                        val sid = activeTaskSessions.remove(msgId).orEmpty()
                        val b = memberBots.firstOrNull { it.id == botId }
                        if (sid.isNotBlank() && b != null) {
                            scope.launch(Dispatchers.IO) {
                                val hub = RemoteStore.hub(ctx)
                                val token = RemoteStore.token(ctx)
                                if (hub.isNotBlank() && token.isNotBlank()) {
                                    runCatching {
                                        RemoteApi.interrupt(hub, token, b.device.ifBlank { "pc" }, sid, b.engine)
                                    }
                                }
                            }
                        }
                    }
                },
                attachPanel = {
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
                },
                attachments = trayAttachments,
                onRemove = { id -> pendingAttachments = pendingAttachments.filterNot { it.id == id } },
                backdrop = glassBackdrop,
                placeholderOverride = when {
                    pendingAttachments.isNotEmpty() -> "已选 ${pendingAttachments.size} 个附件，输入说明或直接点发送…"
                    collabMode != "single" -> "发任务给全群协同，或 $hintMentions 指定 Bot…"
                    else -> "输入消息，可点上方成员 @TA…"
                },
                onVoiceInput = {
                    runCatching {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话，识别后将自动填入群聊输入框…")
                        }
                        voiceLauncher.launch(intent)
                    }
                },
            )
        }
    }

    excerptDialogTarget?.let { targetMsg ->
        TextExcerptDialog(
            fullText = targetMsg.text.ifBlank { targetMsg.reasoning },
            reasoningText = if (targetMsg.text.isNotBlank()) targetMsg.reasoning else "",
            onDismiss = { excerptDialogTarget = null },
        )
    }

    if (showEditGroup && currentGroup != null) {
        CreateOrEditTeamGroupDialog(
            initialGroup = currentGroup,
            onDismiss = { showEditGroup = false },
            onSaved = {
                showEditGroup = false
                refreshState()
            },
        )
    }

    if (showRecruitBot) {
        CreateOrEditBotDialog(
            initialBot = null,
            onDismiss = { showRecruitBot = false },
            onSaved = { newBot ->
                showRecruitBot = false
                // 自动把新招募的 Bot 拉进当前群聊
                currentGroup?.let { g ->
                    if (newBot.id !in g.botIds) {
                        BotTeamStore.upsertGroup(ctx, g.copy(botIds = g.botIds + newBot.id))
                    }
                }
                refreshState()
            },
        )
    }

    voiceCallBot?.let { targetBot ->
        BotVoiceCallDialog(
            bot = targetBot,
            onDismiss = { voiceCallBot = null },
            onOpenUnderlyingSession = { sid ->
                voiceCallBot = null
                onInspectSession(targetBot.id, sid)
            },
        )
    }

    learnSkillTarget?.let { (targetBot, targetMsg) ->
        val lastUserPrompt = remember(targetMsg, messages) {
            val idx = messages.indexOfFirst { it.id == targetMsg.id }
            val prior = if (idx > 0) messages.subList(0, idx) else messages
            prior.lastOrNull { it.senderId == "user" }?.text ?: "执行专项工程与核查任务"
        }
        LearnSkillDraftDialog(
            bot = targetBot,
            userPrompt = lastUserPrompt,
            assistantReply = targetMsg.text,
            onDismiss = { learnSkillTarget = null },
            onSaved = {
                learnSkillTarget = null
                refreshState()
            },
        )
    }

    reworkTarget?.let { (targetBot, targetMsg) ->
        ReworkBotTurnDialog(
            bot = targetBot,
            previousMessage = targetMsg,
            onDismiss = { reworkTarget = null },
            onConfirmRework = { reworkReason, mode ->
                reworkTarget = null
                val reworkUserMsg = TeamGroupMessage(
                    id = "msg_" + UUID.randomUUID().toString().take(8),
                    senderId = "user",
                    senderName = "我",
                    text = "🔄 [打回重做 Rework] @${targetBot.name}：$reworkReason",
                    at = System.currentTimeMillis(),
                    status = "done",
                )
                appendOrUpdateMessage(reworkUserMsg)
                val prompt = buildString {
                    appendLine("[Rework 返工重做指令] 请针对以下验收缺陷或补充要求直接返工修正：")
                    appendLine(reworkReason)
                    appendLine()
                    appendLine("【你上一轮提交的结果摘要】")
                    appendLine(targetMsg.text.take(600))
                }
                triggerBotsForMessage(
                    userText = prompt,
                    targetBots = listOf(targetBot),
                    overrideMode = mode,
                )
            },
        )
    }
}

/**
 * 招募新 Bot 好友 / 编辑 Bot 职位、Grok Bot 七层结构化配方（Recipe）、技能剧本、工具边界与长期记忆弹窗
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateOrEditBotDialog(
    initialBot: BotFriend? = null,
    onDismiss: () -> Unit,
    onSaved: (BotFriend) -> Unit,
) {
    val ctx = LocalContext.current
    val c = VibeLinkTheme.colors
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var selectedTemplate by remember {
        mutableStateOf(
            BotTeamStore.TEMPLATES.firstOrNull { it.id == initialBot?.templateId }
                ?: BotTeamStore.TEMPLATES.first(),
        )
    }
    var templateId by remember { mutableStateOf(initialBot?.templateId ?: selectedTemplate.id) }
    var engine by remember { mutableStateOf(initialBot?.engine ?: BotModelPolicy.defaultEngine(selectedTemplate.id)) }
    var model by remember { mutableStateOf(initialBot?.model ?: BotModelPolicy.defaultModel(selectedTemplate.id)) }
    var name by remember {
        mutableStateOf(
            initialBot?.name?.ifBlank { BotTeamStore.defaultModelNameForEngine(engine) }
                ?: selectedTemplate.defaultName.ifBlank { BotTeamStore.defaultModelNameForEngine(engine) },
        )
    }
    var avatar by remember {
        mutableStateOf(
            initialBot?.avatar?.ifBlank {
                selectedTemplate.defaultAvatar
            } ?: selectedTemplate.defaultAvatar,
        )
    }
    var jobTitle by remember { mutableStateOf(initialBot?.jobTitle ?: selectedTemplate.jobTitle) }
    var department by remember { mutableStateOf(initialBot?.department ?: selectedTemplate.department) }
    var lifecycle by remember { mutableStateOf(initialBot?.lifecycle ?: selectedTemplate.lifecycle) }
    var thinkingEffort by remember { mutableStateOf(initialBot?.thinkingEffort ?: selectedTemplate.thinkingEffort) }
    var approvalMode by remember {
        mutableStateOf(
            initialBot?.approvalMode?.ifBlank { selectedTemplate.approvalMode } ?: selectedTemplate.approvalMode,
        )
    }
    var exampleRequests by remember {
        mutableStateOf(
            initialBot?.exampleRequests?.ifEmpty { selectedTemplate.exampleRequests } ?: selectedTemplate.exampleRequests,
        )
    }
    var requires by remember {
        mutableStateOf(
            initialBot?.requires?.ifEmpty { selectedTemplate.requires } ?: selectedTemplate.requires,
        )
    }
    var isolationMode by remember {
        mutableStateOf(
            initialBot?.isolationMode ?: selectedTemplate.isolationMode,
        )
    }
    var cwd by remember { mutableStateOf(initialBot?.cwd ?: "D:\\AI\\VibeLink") }
    var publicDescription by remember {
        mutableStateOf(initialBot?.publicDescription?.ifBlank { selectedTemplate.publicDescription } ?: selectedTemplate.publicDescription)
    }
    var rolePrompt by remember { mutableStateOf(initialBot?.rolePrompt ?: selectedTemplate.rolePrompt) }
    var disabledTools by remember {
        mutableStateOf(initialBot?.disabledTools?.ifEmpty { selectedTemplate.disabledTools } ?: selectedTemplate.disabledTools)
    }
    var skills by remember {
        mutableStateOf(initialBot?.skills?.ifEmpty { selectedTemplate.skills } ?: selectedTemplate.skills)
    }
    var defaultRoutines by remember {
        mutableStateOf(initialBot?.defaultRoutines?.ifEmpty { selectedTemplate.defaultRoutines } ?: selectedTemplate.defaultRoutines)
    }
    var openWork by remember {
        mutableStateOf(
            initialBot?.openWork?.ifEmpty {
                selectedTemplate.defaultOpenWork.mapIndexed { idx, w ->
                    BotOpenWorkItem(id = "work_tpl_$idx", title = w)
                }
            } ?: selectedTemplate.defaultOpenWork.mapIndexed { idx, w ->
                BotOpenWorkItem(id = "work_tpl_$idx", title = w)
            },
        )
    }
    var memories by remember {
        mutableStateOf(
            initialBot?.memories?.ifEmpty {
                selectedTemplate.seedMemories.mapIndexed { idx, m ->
                    BotMemoryFact(id = "mem_tpl_$idx", fact = m, category = "workflow", source = "recipe")
                }
            } ?: selectedTemplate.seedMemories.mapIndexed { idx, m ->
                BotMemoryFact(id = "mem_tpl_$idx", fact = m, category = "workflow", source = "recipe")
            },
        )
    }
    var newMemoryText by remember { mutableStateOf("") }
    var newMemoryTopic by remember { mutableStateOf("") }
    var newMemoryAliases by remember { mutableStateOf("") }
    var newOpenWorkText by remember { mutableStateOf("") }
    var showRecipeImportExport by remember { mutableStateOf(false) }
    var recipeImportText by remember { mutableStateOf("") }
    var recipeStatusMsg by remember { mutableStateOf("") }

    fun applyTemplateToFields(tpl: BotTemplate) {
        selectedTemplate = tpl
        templateId = tpl.id
        name = tpl.defaultName.ifBlank { tpl.jobTitle }
        if (avatar.isBlank() || avatar.startsWith("preset:")) {
            avatar = tpl.defaultAvatar
        }
        jobTitle = tpl.jobTitle
        department = tpl.department
        lifecycle = tpl.lifecycle
        thinkingEffort = tpl.thinkingEffort
        approvalMode = tpl.approvalMode
        exampleRequests = tpl.exampleRequests
        requires = tpl.requires
        isolationMode = tpl.isolationMode
        publicDescription = tpl.publicDescription
        rolePrompt = tpl.rolePrompt
        disabledTools = tpl.disabledTools
        skills = tpl.skills
        defaultRoutines = tpl.defaultRoutines
        openWork = tpl.defaultOpenWork.mapIndexed { idx, w ->
            BotOpenWorkItem(id = "work_tpl_$idx", title = w)
        }
        memories = tpl.seedMemories.mapIndexed { idx, m ->
            BotMemoryFact(id = "mem_tpl_$idx", fact = m, category = "workflow", source = "recipe")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (initialBot == null) "👤 招募专家 Bot" else "⚙️ 编辑角色与技能设定",
                    style = VibeLinkType.Title,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .clip(VibeLinkShapes.Pill)
                        .background(c.accent.copy(alpha = 0.14f))
                        .clickable { showRecipeImportExport = !showRecipeImportExport }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        "📋 导入/导出",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.accent,
                    )
                }
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (showRecipeImportExport) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.fill)
                            .border(0.5.dp, c.accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "open-dots.agent JSON / SOUL.md 导入与导出",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.ink,
                        )
                        OutlinedTextField(
                            value = recipeImportText,
                            onValueChange = { recipeImportText = it },
                            placeholder = {
                                Text(
                                    "粘贴 open-dots.agent JSON 或 SOUL.md / AGENTS.md 文本…",
                                    fontSize = 11.5.sp,
                                )
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 140.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(c.accent)
                                    .clickable {
                                        val res = BotTeamStore.importBotRecipeText(
                                            context = ctx,
                                            rawText = recipeImportText,
                                            targetBotId = initialBot?.id,
                                        )
                                        res.onSuccess { imported ->
                                            onSaved(imported)
                                        }.onFailure { e ->
                                            recipeStatusMsg = "导入失败：${e.message}"
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text("📥 立即套用导入配方", fontSize = 11.5.sp, color = c.onAccent, fontWeight = FontWeight.SemiBold)
                            }
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(c.surface)
                                    .border(0.5.dp, c.edge, RoundedCornerShape(8.dp))
                                    .clickable {
                                        val previewBot = BotFriend(
                                            id = initialBot?.id ?: "bot_export",
                                            templateId = templateId,
                                            name = name,
                                            jobTitle = jobTitle,
                                            department = department,
                                            lifecycle = lifecycle,
                                            engine = "codex",
                        model = model,
                                            cwd = cwd,
                                            publicDescription = publicDescription,
                                            rolePrompt = rolePrompt,
                                            thinkingEffort = thinkingEffort,
                                            isolationMode = isolationMode,
                                            disabledTools = disabledTools,
                                            skills = skills,
                                            defaultRoutines = defaultRoutines,
                                            openWork = openWork,
                                            memories = memories,
                                        )
                                        val jsonStr = BotTeamStore.exportBotRecipeJson(previewBot)
                                        recipeImportText = jsonStr
                                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(jsonStr))
                                        recipeStatusMsg = "✅ 已生成并复制角色 JSON 到剪贴板"
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text("📤 导出并复制当前配置", fontSize = 11.5.sp, color = c.ink, fontWeight = FontWeight.Medium)
                            }
                        }
                        if (recipeStatusMsg.isNotBlank()) {
                            Text(recipeStatusMsg, fontSize = 11.sp, color = c.accent)
                        }
                    }
                }

                // 一键切换预设角色职位模板
                Text("职位模板 · 定义职责、技能和权限", style = VibeLinkType.Label, color = c.ink3)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BotTeamStore.TEMPLATES.forEach { tpl ->
                        val selected = selectedTemplate.id == tpl.id
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) c.accent else c.fill)
                                .clickable { applyTemplateToFields(tpl) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                tpl.jobTitle,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (selected) c.onAccent else c.ink,
                            )
                        }
                    }
                }
                Text(selectedTemplate.summary, style = VibeLinkType.Hint, color = c.ink3)

                Text("运行模型 · 默认 agent：Codex 官方 CLI", style = VibeLinkType.Label, color = c.ink3)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BotModelPolicy.models.forEach { selectedModel ->
                        val active = model == selectedModel
                        Box(
                            Modifier
                                .clip(VibeLinkShapes.Pill)
                                .background(if (active) c.ink else c.fill)
                                .clickable {
                                    model = selectedModel
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                BotModelPolicy.modelLabel(selectedModel),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (active) c.bg else c.ink,
                            )
                        }
                    }
                }



                // 展示当前配方的绑定 Skills 与 工具权限边界
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.fill)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "🛡️ 工具边界 (Layer 3)",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.ink,
                        )
                        val isReadOnly = disabledTools.isNotEmpty()
                        Box(
                            Modifier
                                .clip(VibeLinkShapes.Pill)
                                .background(if (isReadOnly) Color(0xFFD97706).copy(alpha = 0.16f) else Color(0xFF1F9D55).copy(alpha = 0.16f))
                                .clickable {
                                    disabledTools = if (isReadOnly) emptyList() else listOf("write_file", "desktop_act", "git_commit")
                                }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                if (isReadOnly) "🔒 只读核查/审校 (禁写文件，点此切换)" else "🌿 全栈读写 + Worktree (点此切换)",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isReadOnly) Color(0xFFD97706) else Color(0xFF1F9D55),
                            )
                        }
                    }
                    if (skills.isNotEmpty()) {
                        Text(
                            "📚 绑定 6-Section 技能剧本 (Layer 4 · 共 ${skills.size} 项 SKILL.md · 点击切换启停)：",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.ink,
                        )
                        skills.forEach { sk ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(c.surface)
                                    .clickable {
                                        skills = skills.map {
                                            if (it.id == sk.id) it.copy(enabled = !it.enabled) else it
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "[${sk.source}] ${sk.name}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (sk.enabled) c.ink else c.ink3,
                                    )
                                    Text(
                                        sk.description,
                                        fontSize = 10.sp,
                                        color = c.ink3,
                                    )
                                }
                                Text(
                                    if (sk.enabled) "✓ 已启用" else "🔒 已禁用",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (sk.enabled) Color(0xFF1F9D55) else Color(0xFFD97706),
                                )
                            }
                        }
                    }
                    if (requires.isNotEmpty()) {
                        Text(
                            "🔌 依赖能力声明 (Requires)：${requires.joinToString(", ")}",
                            fontSize = 11.sp,
                            color = c.ink3,
                        )
                    }
                    if (defaultRoutines.isNotEmpty()) {
                        Text(
                            "⏰ 预置例行巡检 (Layer 5)：${defaultRoutines.joinToString("；") { "${it.name} [${it.cron}]" }}",
                            fontSize = 11.sp,
                            color = c.ink3,
                        )
                    }
                }

                // Open Duet 审批边界模式 (approvalMode)
                Text("高危动作审批边界 (Open Duet Approval Mode)", style = VibeLinkType.Label, color = c.ink3)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "ask" to "🙋 询问(ask)",
                        "accept_edits" to "✏️ 允许改码",
                        "plan" to "📋 仅规划",
                        "auto" to "⚡ 全自动",
                    ).forEach { (am, label) ->
                        val active = approvalMode == am
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(VibeLinkShapes.Pill)
                                .background(if (active) c.accent else c.fill)
                                .clickable { approvalMode = am }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (active) c.onAccent else c.ink,
                                maxLines = 1,
                            )
                        }
                    }
                }

                // 自定义 Bot 头像选择器（默认绑定底层模型四宫格 AI 拟人头像，支持相册上传自定义头像）
                BotAvatarPickerSection(
                    currentAvatar = avatar,
                    engine = "codex",
                    name = name,
                    onAvatarChange = { avatar = it },
                )

                Text("Bot 定位分类", style = VibeLinkType.Label, color = c.ink3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotLifecycle.entries.forEach { lc ->
                        val active = lifecycle == lc
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) Color(lc.badgeColorHex) else c.fill)
                                .clickable { lifecycle = lc }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (lc == BotLifecycle.MANAGEMENT) "👔 长期管理 Bot" else "🛠️ 短期项目 Bot",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (active) Color.White else c.ink,
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Bot 名 (默认底层模型名)") },
                        placeholder = { Text(BotTeamStore.defaultModelNameForEngine(engine)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = jobTitle,
                        onValueChange = { jobTitle = it },
                        label = { Text("职位头衔") },
                        singleLine = true,
                        modifier = Modifier.weight(1.2f),
                    )
                }

                // 思考深度档位（对标 Rakazo P0）
                Text("思考深度档位 (Thinking Effort)", style = VibeLinkType.Label, color = c.ink3)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "low" to "⚡ 低",
                        "medium" to "⚖️ 标准",
                        "high" to "🧠 深度",
                        "max" to "🔥 极限",
                    ).forEach { (eff, label) ->
                        val active = thinkingEffort == eff
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(VibeLinkShapes.Pill)
                                .background(if (active) c.accent else c.fill)
                                .clickable { thinkingEffort = eff }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (active) c.onAccent else c.ink,
                            )
                        }
                    }
                }

                // 工作区隔离模式（对标 Rakazo P1 Per-Bot Git Worktree）
                Text("工作区隔离模式 (Git Worktree)", style = VibeLinkType.Label, color = c.ink3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "shared" to "📂 共享主工作区",
                        "worktree" to "🌿 独立 Git Worktree",
                    ).forEach { (mode, label) ->
                        val active = isolationMode == mode
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) c.accent.copy(alpha = 0.16f) else c.fill)
                                .border(
                                    0.5.dp,
                                    if (active) c.accent else Color.Transparent,
                                    RoundedCornerShape(10.dp),
                                )
                                .clickable { isolationMode = mode }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (active) c.accent else c.ink2,
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = cwd,
                    onValueChange = { cwd = it },
                    label = { Text("负责的项目工作区目录 (cwd)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Layer 1: 对外协作名片 (`publicDescription` — How other bots see it in `<bot_directory>`)
                OutlinedTextField(
                    value = publicDescription,
                    onValueChange = { publicDescription = it },
                    label = { Text("对外协作名片 Public Description（其他 Bot 在通讯录中看到的派单依据）") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                )

                // Layer 2: 私有常驻指令 (`SOUL.md` + `AGENTS.md`)
                OutlinedTextField(
                    value = rolePrompt,
                    onValueChange = { rolePrompt = it },
                    label = { Text("私有常驻指令 SOUL.md + AGENTS.md (v${initialBot?.instructionsVersion ?: 1L})") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                )

                // Layer 5: Scratchpad 未完结待办 (`<scratchpad_open>`)
                Text("📋 Scratchpad 待办便签 (${openWork.count { it.status != "done" }} 项进行中)", style = VibeLinkType.Label, color = c.ink3)
                openWork.forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.fill)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "☐ ${item.title}",
                            fontSize = 12.sp,
                            color = c.ink,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "移除",
                            fontSize = 11.sp,
                            color = Color(0xFFEF4444),
                            modifier = Modifier
                                .clickable { openWork = openWork.filterNot { it.id == item.id } }
                                .padding(start = 8.dp),
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = newOpenWorkText,
                        onValueChange = { newOpenWorkText = it },
                        placeholder = { Text("添加跨轮次跟进待办（Scratchpad Open Work）", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = c.accent,
                        modifier = Modifier.clickable {
                            val t = newOpenWorkText.trim()
                            if (t.isNotEmpty()) {
                                openWork = listOf(
                                    BotOpenWorkItem(
                                        id = "work_${System.currentTimeMillis()}",
                                        title = t,
                                    ),
                                ) + openWork
                                newOpenWorkText = ""
                            }
                        },
                    ) {
                        Text(
                            "+ 待办",
                            color = c.onAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        )
                    }
                }

                // Layer 6: 专属长期记忆（对标 Open Duet 预算容量核心记忆 + 按需挂载专题记忆 Topic Memory）
                val activeCount = memories.count { !it.archived && it.topic.isBlank() }
                val topicCount = memories.count { it.topic.isNotBlank() }
                Text(
                    "🧠 长期记忆与专题记忆 (核心: $activeCount/40 · 专题: $topicCount 个)",
                    style = VibeLinkType.Label,
                    color = c.ink3,
                )
                memories.forEach { mem ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.fill)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val badge = buildString {
                            if (mem.topic.isNotBlank()) append("[专题:${mem.topic}] ")
                            if (mem.archived) append("[已归档] ")
                        }
                        Text(
                            "• $badge${mem.fact}",
                            fontSize = 12.sp,
                            color = if (mem.archived) c.ink3 else c.ink,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "删除",
                            fontSize = 11.sp,
                            color = Color(0xFFEF4444),
                            modifier = Modifier
                                .clickable { memories = memories.filterNot { it.id == mem.id } }
                                .padding(start = 8.dp),
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = newMemoryTopic,
                        onValueChange = { newMemoryTopic = it },
                        placeholder = { Text("专题名(可选，如 rust-build)", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = newMemoryAliases,
                        onValueChange = { newMemoryAliases = it },
                        placeholder = { Text("触发词别名(逗号分隔)", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = newMemoryText,
                        onValueChange = { newMemoryText = it },
                        placeholder = { Text("添加长期记忆或专题手册正文（如：偏好使用 Rust / 严禁改生产配置）", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = c.accent,
                        modifier = Modifier.clickable {
                            val f = newMemoryText.trim()
                            if (f.isNotEmpty()) {
                                val parsedAliases = newMemoryAliases.split(",", "，")
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                memories = listOf(
                                    BotMemoryFact(
                                        id = "mem_${System.currentTimeMillis()}",
                                        fact = BotTeamStore.redactSecrets(f).first,
                                        topic = newMemoryTopic.trim(),
                                        aliases = parsedAliases,
                                    ),
                                ) + memories
                                newMemoryText = ""
                                newMemoryTopic = ""
                                newMemoryAliases = ""
                            }
                        },
                    ) {
                        Text(
                            "+ 记忆",
                            color = c.onAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val promptChanged = initialBot != null && initialBot.rolePrompt != rolePrompt
                    val nextVer = if (promptChanged) (initialBot?.instructionsVersion ?: 1L) + 1L else (initialBot?.instructionsVersion ?: 1L)
                    val resolvedName = name.trim().ifBlank { jobTitle }
                    val resolvedAvatar = avatar.trim()
                    val saved = (initialBot ?: BotFriend(
                        id = "bot_" + UUID.randomUUID().toString().take(8),
                        templateId = templateId,
                        name = resolvedName,
                        avatar = resolvedAvatar,
                        jobTitle = jobTitle.ifBlank { "工程师" },
                        department = department,
                        lifecycle = lifecycle,
                        engine = "codex",
                        model = model,
                        activeSessionId = if (initialBot?.engine == engine && initialBot.model == model) initialBot.activeSessionId else "",
                        sessionIds = if (initialBot?.engine == engine && initialBot.model == model) initialBot.sessionIds else emptyList(),
                        cwd = cwd,
                        publicDescription = publicDescription,
                        rolePrompt = rolePrompt,
                        thinkingEffort = thinkingEffort,
                        approvalMode = approvalMode,
                        isolationMode = isolationMode,
                        instructionsVersion = nextVer,
                        disabledTools = disabledTools,
                        skills = skills,
                        defaultRoutines = defaultRoutines,
                        openWork = openWork,
                        memories = memories,
                        exampleRequests = exampleRequests,
                        requires = requires,
                        lastText = publicDescription.ifBlank { "[${jobTitle.ifBlank { "工程师" }}] 已入职，点击可分配任务或查看底层会话" },
                        lastAt = System.currentTimeMillis(),
                    )).copy(
                        templateId = templateId,
                        name = resolvedName,
                        avatar = resolvedAvatar,
                        jobTitle = jobTitle.ifBlank { "工程师" },
                        department = department,
                        lifecycle = lifecycle,
                        engine = "codex",
                        model = model,
                        activeSessionId = if (initialBot?.engine == engine && initialBot.model == model) initialBot.activeSessionId else "",
                        sessionIds = if (initialBot?.engine == engine && initialBot.model == model) initialBot.sessionIds else emptyList(),
                        cwd = cwd,
                        publicDescription = publicDescription,
                        rolePrompt = rolePrompt,
                        thinkingEffort = thinkingEffort,
                        approvalMode = approvalMode,
                        isolationMode = isolationMode,
                        instructionsVersion = nextVer,
                        disabledTools = disabledTools,
                        skills = skills,
                        defaultRoutines = defaultRoutines,
                        openWork = openWork,
                        memories = memories,
                        exampleRequests = exampleRequests,
                        requires = requires,
                    )
                    BotTeamStore.upsertBot(ctx, saved)
                    onSaved(saved)
                },
            ) {
                Text(if (initialBot == null) "招募入职" else "保存配方", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * Open Duet 对标：从对话一键提炼 6-Section 结构化技能弹窗 (`skill_learning.py`)
 */
@Composable
fun LearnSkillDraftDialog(
    bot: BotFriend,
    userPrompt: String,
    assistantReply: String,
    onDismiss: () -> Unit,
    onSaved: (BotSkillSpec) -> Unit,
) {
    val ctx = LocalContext.current
    val c = VibeLinkTheme.colors
    val initialDraft = remember(bot.id, userPrompt, assistantReply) {
        BotTeamStore.draftSkillFromTurn(
            userPrompt = userPrompt,
            assistantReply = assistantReply,
            bot = bot,
        )
    }
    var skillName by remember { mutableStateOf(initialDraft.skill.name) }
    var skillDesc by remember { mutableStateOf(initialDraft.skill.description) }
    var skillContent by remember { mutableStateOf(initialDraft.skill.playbook) }
    val liveWarnings = remember(skillContent) {
        BotTeamStore.scanSkillWarnings(skillContent)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "✨ 提炼 6-Section 技能剧本 → ${bot.name}",
                style = VibeLinkType.Title,
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "基于本轮实战对话自动萃取 Open Duet 标准 6 段式技能剧本（Purpose / Required inputs / Steps / Validation checks / Output format / Approval boundary），保存后自动挂载到该 Bot。",
                    style = VibeLinkType.Hint,
                    color = c.ink3,
                )
                if (initialDraft.redactedSecretsCount > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1F9D55).copy(alpha = 0.14f))
                            .padding(8.dp),
                    ) {
                        Text(
                            "🔒 已自动扫描并脱敏对话中的 ${initialDraft.redactedSecretsCount} 处 API Key / Token / 敏感凭据",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1F9D55),
                        )
                    }
                }
                if (liveWarnings.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFD97706).copy(alpha = 0.14f))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            "⚠️ 安全边界扫描提醒：",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD97706),
                        )
                        liveWarnings.forEach { w ->
                            Text("• $w", fontSize = 10.5.sp, color = c.ink)
                        }
                    }
                }
                OutlinedTextField(
                    value = skillName,
                    onValueChange = { skillName = it },
                    label = { Text("技能名称 (Skill Name)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = skillDesc,
                    onValueChange = { skillDesc = it },
                    label = { Text("适用场景简述 (Description)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = skillContent,
                    onValueChange = { skillContent = it },
                    label = { Text("6-Section 技能剧本正文 (SKILL.md)") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 300.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = skillName.isNotBlank() && skillContent.isNotBlank(),
                onClick = {
                    val savedSkill = initialDraft.skill.copy(
                        name = skillName.trim(),
                        description = skillDesc.trim(),
                        playbook = skillContent.trim(),
                    )
                    BotTeamStore.saveLearnedSkill(
                        context = ctx,
                        botId = bot.id,
                        skill = savedSkill,
                    )
                    onSaved(savedSkill)
                },
            ) {
                Text("💾 保存技能到 ${bot.name}", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * Open Duet 对标：原会话打回重做弹窗 (`Rework Loop`)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReworkBotTurnDialog(
    bot: BotFriend,
    previousMessage: TeamGroupMessage,
    onDismiss: () -> Unit,
    onConfirmRework: (reason: String, mode: String) -> Unit,
) {
    val c = VibeLinkTheme.colors
    var reason by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("work") }
    val quickReasons = listOf(
        "请给出精确的文件路径、函数名和行号证据，禁止模糊概括",
        "刚才的方案只停留在了口头思路，请直接在工作区落盘修改代码并执行编译验证",
        "存在边界条件或潜在回归风险，请补齐单元测试或验证输出",
        "输出格式不符合 6-Section 技能规范，请按标准结构重新输出",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "🔄 打回重做 (Rework) → ${bot.name} [${bot.jobTitle}]",
                style = VibeLinkType.Title,
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "在当前会话线程中将该结果打回给 ${bot.name} 返工修正，自动携带上一轮输出上下文。",
                    style = VibeLinkType.Hint,
                    color = c.ink3,
                )
                Text("快捷返工原因（点击填入）：", style = VibeLinkType.Label, color = c.ink2)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    quickReasons.forEach { q ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(c.fill)
                                .border(0.5.dp, c.edge, RoundedCornerShape(8.dp))
                                .clickable { reason = q }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        ) {
                            Text(q, fontSize = 11.sp, color = c.ink)
                        }
                    }
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("具体返工要求 / 验收缺失点") },
                    placeholder = { Text("输入需要修正的具体问题…") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "work" to "🛠️ 实操落盘重做 (Work)",
                        "consult" to "🔍 只读核查重做 (Consult)",
                    ).forEach { (m, label) ->
                        val active = mode == m
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) c.accent else c.fill)
                                .clickable { mode = m }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (active) c.onAccent else c.ink,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = reason.isNotBlank(),
                onClick = { onConfirmRework(reason.trim(), mode) },
            ) {
                Text("🚀 立即打回重做", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 发起 / 编辑多 Bot Team 群聊弹窗
 */
@Composable
fun CreateOrEditTeamGroupDialog(
    initialGroup: TeamGroup? = null,
    onDismiss: () -> Unit,
    onSaved: (TeamGroup) -> Unit,
) {
    val ctx = LocalContext.current
    val c = VibeLinkTheme.colors
    val allBots = remember { BotTeamStore.listBots(ctx, includeArchived = false) }
    var name by remember { mutableStateOf(initialGroup?.name ?: "新项目协作群") }
    var description by remember { mutableStateOf(initialGroup?.description ?: "长期管理 Bot 拆解督办 + 短期项目 Bot 编码测试") }
    var cwd by remember { mutableStateOf(initialGroup?.cwd ?: "C:\\Users\\22771\\Desktop\\项目\\VibeLink") }
    var selectedBotIds by remember {
        mutableStateOf(
            initialGroup?.botIds?.toSet() ?: allBots.map { it.id }.toSet(),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initialGroup == null) "💬 组建 Team 群聊（拉多个 Bot 进群协作）" else "👥 管理群聊成员与项目目录",
                style = VibeLinkType.Title,
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("群聊名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("群协作目标 / 公告") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cwd,
                    onValueChange = { cwd = it },
                    label = { Text("项目工作区路径 (cwd)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("勾选拉入群聊的 Bot 成员（已选 ${selectedBotIds.size} 人）", style = VibeLinkType.Label, color = c.ink3)
                allBots.forEach { bot ->
                    val checked = bot.id in selectedBotIds
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (checked) c.accent.copy(alpha = 0.08f) else c.fill)
                            .clickable {
                                selectedBotIds = if (checked) selectedBotIds - bot.id else selectedBotIds + bot.id
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                selectedBotIds = if (it) selectedBotIds + bot.id else selectedBotIds - bot.id
                            },
                        )
                        AgentAvatar(bot.id, bot.name, 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(bot.name, style = VibeLinkType.BodyStrong, color = c.ink)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "[${bot.jobTitle}]",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(bot.lifecycle.badgeColorHex),
                                )
                            }
                            Text(
                                "${bot.lifecycle.label} · 驱动引擎: ${agentDisplayName(bot.engine)}",
                                style = VibeLinkType.Hint,
                                color = c.ink3,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedBotIds.isNotEmpty(),
                onClick = {
                    val now = System.currentTimeMillis()
                    val g = (initialGroup ?: TeamGroup(
                        id = "group_" + UUID.randomUUID().toString().take(8),
                        name = name.ifBlank { "Team 协作群" },
                        description = description,
                        botIds = selectedBotIds.toList(),
                        cwd = cwd,
                        lastText = "群聊已创建，包含 ${selectedBotIds.size} 名 Bot 成员",
                        lastAt = now,
                    )).copy(
                        name = name.ifBlank { "Team 协作群" },
                        description = description,
                        botIds = selectedBotIds.toList(),
                        cwd = cwd,
                    )
                    BotTeamStore.upsertGroup(ctx, g)
                    onSaved(g)
                },
            ) {
                Text(if (initialGroup == null) "立即建群" else "保存", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

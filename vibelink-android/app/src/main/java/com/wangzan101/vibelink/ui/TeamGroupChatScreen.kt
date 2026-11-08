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
import com.wangzan101.vibelink.remote.GroupWorkSubmission
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
    // Hub 负责 auto 调度、多人分工和接力；页面仅提交所选模式。
    var collabMode by remember { mutableStateOf("auto") }
    // Open Duet 委派权限模式："work" (全权实操落盘) | "consult" (只读咨询核查围栏)
    var delegationMode by remember { mutableStateOf("work") }
    // Open Duet 单次发言按需挂载的技能 ID 集合 (/skill)
    var attachedSkillIds by remember(groupId) { mutableStateOf(BotTeamStore.getDraftSkills(draftKey)) }
    var showSkillPicker by remember { mutableStateOf(false) }
    var learnSkillTarget by remember { mutableStateOf<Pair<BotFriend, TeamGroupMessage>?>(null) }
    var reworkTarget by remember { mutableStateOf<Pair<BotFriend, TeamGroupMessage>?>(null) }
    var excerptDialogTarget by remember { mutableStateOf<TeamGroupMessage?>(null) }
    val workByGroup by GroupWorkStore.work.collectAsState()
    val workErrors by GroupWorkStore.errors.collectAsState()
    val pendingByGroup by GroupWorkStore.pending.collectAsState()
    val workKey = GroupWorkStore.key(ctx, groupId)
    val workItems = workByGroup[workKey].orEmpty()
    val runningBotIds = workItems.filter { it.active }.flatMap { work ->
        work.steps.filter { it.status in setOf("queued", "running", "dispatching", "waiting") }.map { it.botId }
    }.toSet()
    val activeTaskCount = workItems.count { it.active }
    var replyToWorkId by remember(groupId) { mutableStateOf<String?>(null) }
    var showEditGroup by remember { mutableStateOf(false) }
    var showMemberModels by remember { mutableStateOf(false) }
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
        if (messages.isEmpty() && workItems.isEmpty()) return
        val targetIdx = messages.size + workItems.size
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
        workItems.maxOfOrNull { it.updatedAt },
        messages.size,
        messages.lastOrNull()?.text?.length,
        messages.lastOrNull()?.reasoning?.length,
        messages.lastOrNull()?.tools?.size,
    ) {
        if (messages.isNotEmpty() && stick) {
            scrollToBottomImmediate()
        }
    }

    // Polling may stop at any time; execution belongs to the Hub.
    LaunchedEffect(groupId) {
        while (true) {
            GroupWorkStore.refresh(ctx, groupId)
            delay(2500L)
        }
    }

    fun sendUserMessage(rawText: String) {
        val text = rawText.trim()
        val files = pendingAttachments.toList()
        if (text.isBlank() && files.isEmpty()) return
        if (workItems.any { it.id == replyToWorkId && it.status == "cancelled" }) {
            Toast.makeText(ctx, "原任务已取消，请点击“改发新任务”后发送", Toast.LENGTH_LONG).show()
            return
        }
        val mentioned = memberBots.filter { bot -> text.contains("@${bot.name}") || text.contains("@${bot.jobTitle}") }
        try {
            GroupWorkStore.enqueue(ctx, groupId, GroupWorkSubmission(
                requestId = UUID.randomUUID().toString(), text = text, mode = collabMode,
                botIds = mentioned.map { it.id }, replyToWorkId = replyToWorkId,
                delegationMode = delegationMode, skillIds = attachedSkillIds.toList(),
            ), files)
            input = ""
            pendingAttachments = emptyList()
            replyToWorkId = null
            BotTeamStore.clearDraft(ctx, draftKey)
            attachOpen = false
        } catch (e: Exception) {
            Toast.makeText(ctx, e.message ?: "任务保存失败，草稿已保留", Toast.LENGTH_LONG).show()
        }
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
                        memberBots.joinToString(" · ") { "${it.name} ${if (it.model == BotModelPolicy.DEEPSEEK_MODEL) "DeepSeek" else "GPT"}" }.ifBlank { "点击添加 Bot 群成员" },
                        style = VibeLinkType.Hint,
                        color = c.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { showMemberModels = true }) {
                    Text("模型", color = c.accent, fontWeight = FontWeight.SemiBold)
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
                // Hub 主脑路由或显式成员分工；执行进度保存在服务端。
                Box(
                    Modifier
                        .clip(VibeLinkShapes.Pill)
                        .background(c.accent.copy(alpha = 0.14f))
                        .border(0.5.dp, c.accent, VibeLinkShapes.Pill)
                        .clickable {
                            collabMode = when (collabMode) {
                                "auto" -> "parallel"
                                "parallel" -> "relay"
                                "relay" -> "auto"
                                else -> "auto"
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        when (collabMode) {
                            "auto" -> "主脑调度 · 拆解→执行→验收→交付"
                            "parallel" -> "多人分工 · 按工位顺序执行"
                            "relay" -> "成员接力 · 按顺序执行"
                            else -> "主脑调度"
                        },
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.accent,
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
                                presetAppliedToast = "已重置团队职责配置"
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
                    items(workItems, key = { "task_${it.id}" }) { work ->
                        GroupWorkCard(work = work, bots = memberBots,
                            onCancel = { GroupWorkStore.action(ctx, groupId, work.id, "cancel") },
                            onRetry = { GroupWorkStore.action(ctx, groupId, work.id, "retry") },
                            onReply = { replyToWorkId = work.id },
                            onSession = { botId, sid -> onInspectSession(botId, sid) })
                    }
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
                                                            replyToWorkId = workItems.firstOrNull { work -> msg.id.startsWith("${work.id}_") }?.id
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
                                                        replyToWorkId = workItems.firstOrNull { work -> msg.id.startsWith("${work.id}_") }?.id
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
                            "技能 · 已选择 ${attachedSkillIds.size} 项",
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
            workErrors[workKey]?.let { error ->
                Row(Modifier.fillMaxWidth().background(c.fill).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = c.ink2, style = VibeLinkType.Caption, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { GroupWorkStore.refresh(ctx, groupId) } }) { Text("重试连接") }
                }
            }
            if ((pendingByGroup[workKey] ?: 0) > 0) {
                Text("${pendingByGroup[workKey]} 条任务等待 Hub 确认；重试沿用原请求编号。", color = c.ink3,
                    style = VibeLinkType.Caption, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
            replyToWorkId?.let { id ->
                Row(Modifier.fillMaxWidth().background(c.accent.copy(alpha = 0.08f)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("追问：${workItems.firstOrNull { it.id == id }?.title ?: id.take(8)}", color = c.accent,
                        style = VibeLinkType.Caption, modifier = Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = { replyToWorkId = null }) { Text("改发新任务") }
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
                    workItems.filter { it.active }.forEach { GroupWorkStore.action(ctx, groupId, it.id, "cancel") }
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
                    replyToWorkId != null -> "回复当前任务，输入补充或答复…"
                    else -> "发起新任务，或 $hintMentions 限定参与成员…"
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

    if (showMemberModels) {
        AlertDialog(
            onDismissRequest = { showMemberModels = false },
            title = { Text("成员模型", color = c.ink) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("按成员绑定模型，下次发送时使用。会话由 Codex 管理。", style = VibeLinkType.Hint, color = c.ink2)
                    memberBots.forEach { bot ->
                        val busy = bot.id in runningBotIds
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(bot.name, style = VibeLinkType.BodyStrong, color = c.ink, modifier = Modifier.weight(1f))
                                Text(if (busy) "执行中" else "Codex", style = VibeLinkType.Hint, color = c.ink3)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BotModelPolicy.models.forEach { choice ->
                                    val selected = if (choice.isEmpty()) bot.model.isEmpty() || bot.model.startsWith("gpt-") else bot.model == choice
                                    Box(
                                        Modifier.weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (selected) c.accent.copy(alpha = 0.12f) else c.fill)
                                            .border(1.dp, if (selected) c.accent else c.edge, RoundedCornerShape(12.dp))
                                            .clickable(enabled = !busy) {
                                                if (!selected) {
                                                    val latest = BotTeamStore.getBot(ctx, bot.id) ?: bot
                                                    BotTeamStore.upsertBot(ctx, latest.copy(engine = "codex", model = choice))
                                                }
                                            }
                                            .padding(horizontal = 10.dp, vertical = 12.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            (if (selected) "✓ " else "") + if (choice == BotModelPolicy.DEEPSEEK_MODEL) "DeepSeek" else "GPT",
                                            style = VibeLinkType.BodyStrong,
                                            color = if (selected) c.accent else c.ink2,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (memberBots.isEmpty()) Text("先添加群成员，再绑定模型。", color = c.ink2)
                }
            },
            confirmButton = { TextButton(onClick = { showMemberModels = false }) { Text("完成") } },
            containerColor = c.surface,
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
                val sourceWork = workItems.firstOrNull { work ->
                    targetMsg.id.startsWith("${work.id}_") || work.steps.any { it.sessionId == targetMsg.linkedSessionId && it.sessionId.isNotBlank() }
                }
                runCatching {
                    check(sourceWork?.status != "cancelled") { "原任务已取消，请发起新任务" }
                    GroupWorkStore.enqueue(ctx, groupId, GroupWorkSubmission(
                        requestId = UUID.randomUUID().toString(),
                        text = "[返工要求] $reworkReason\n\n上一轮结果：${targetMsg.text.take(600)}",
                        mode = "auto", botIds = listOf(targetBot.id), replyToWorkId = sourceWork?.id,
                        delegationMode = mode,
                    ), emptyList())
                }.onSuccess { reworkTarget = null }
                    .onFailure { Toast.makeText(ctx, it.message ?: "返工请求未保存，请重试", Toast.LENGTH_LONG).show() }
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
    var modelExplicitlySelected by remember { mutableStateOf(initialBot != null) }
    var name by remember {
        mutableStateOf(
            initialBot?.name?.ifBlank { selectedTemplate.defaultName.ifBlank { selectedTemplate.jobTitle } }
                ?: selectedTemplate.defaultName.ifBlank { selectedTemplate.jobTitle },
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
        model = BotModelPolicy.modelAfterTemplateChange(model, modelExplicitlySelected, tpl.id)
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
                        val active = model == selectedModel || (selectedModel.isEmpty() && model.startsWith("gpt-"))
                        Box(
                            Modifier
                                .clip(VibeLinkShapes.Pill)
                                .background(if (active) c.ink else c.fill)
                                .clickable {
                                    model = selectedModel
                                    modelExplicitlySelected = true
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
                            "🛡️ 工具权限",
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
                            "技能 · ${skills.size} 项（点击启用或停用）：",
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
                            "⏰ 例行巡检：${defaultRoutines.joinToString("；") { "${it.name} [${it.cron}]" }}",
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
                    defaultAvatar = selectedTemplate.defaultAvatar,
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
                        label = { Text("显示昵称") },
                        placeholder = { Text(selectedTemplate.defaultName.ifBlank { jobTitle }) },
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
                "✨ 提炼为技能 → ${bot.name}",
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
                    label = { Text("技能步骤") },
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

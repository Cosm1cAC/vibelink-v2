package com.wangzan101.vibelink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.vibelink.chat.ConversationStore
import com.wangzan101.vibelink.ui.components.BarIconButton
import com.wangzan101.vibelink.ui.theme.VibeLinkIcons
import com.wangzan101.vibelink.ui.theme.VibeLinkShapes
import com.wangzan101.vibelink.ui.theme.VibeLinkTheme
import com.wangzan101.vibelink.ui.theme.VibeLinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 微信个人资料页风格（WeChat Contact Profile Style）的角色/工位主页：
 * - 顶部白底：左侧圆角方形头像 + 右侧备注名/职位、底层模型昵称、工位 ID、所属部门与工作区
 * - 资料行：展示角色对外职责简介，点击可编辑角色配方、权限与头像
 * - 技能与记忆行（仿微信朋友圈四宫格预览）：展示指令版本、挂载技能、长期记忆与工具边界，点击弹出详情面板
 * - 关联会话行：展示当前绑定的底层 Agent 会话，点击可切换或穿透接管底层会话
 * - 底部微信风格居中通栏按钮：「发消息」与「语音通话」
 */
@Composable
fun AgentProfileScreen(
    agentId: String,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenSession: (String) -> Unit = {},
    onOpenEngineSession: (engine: String, sessionId: String) -> Unit = { _, s -> onOpenSession(s) },
    onOpenSettings: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val c = VibeLinkTheme.colors
    var refreshTick by remember { mutableIntStateOf(0) }
    val storeVersion by BotTeamStore.versionFlow.collectAsState()
    val allBots by BotTeamStore.botsFlow.collectAsState()
    var botFriend by remember(agentId, refreshTick, storeVersion, allBots) {
        mutableStateOf(allBots.firstOrNull { it.id == agentId } ?: BotTeamStore.getBot(ctx, agentId))
    }
    val targetEngine = botFriend?.engine ?: agentId

    var conversations by remember { mutableStateOf(0) }
    var latest by remember { mutableStateOf<String?>(null) }
    var sessions by remember(agentId, targetEngine, botFriend?.model, botFriend?.sessionIds, botFriend?.activeSessionId) {
        val cached = RemoteSessionCache.getCachedSessions(ctx, targetEngine)
        val boundIds = botFriend?.let { (it.sessionIds + it.activeSessionId).filter { id -> id.isNotBlank() }.toSet() }
        val sorted = BotProfilePresentation.sessions(cached, boundIds) { it.id }
        mutableStateOf(sorted)
    }
    var contact by remember(agentId, targetEngine) {
        val latestSession = sessions.firstOrNull()
        val initialContact = if (sessions.isNotEmpty()) {
            AgentContact(
                id = agentId,
                name = botFriend?.name ?: agentDisplayName(agentId),
                kind = AgentKind.Desktop,
                online = true,
                deviceCount = sessions.map { it.device }.filter { it.isNotBlank() }.distinct().size.coerceAtLeast(1),
                sessionCount = sessions.size,
                lastText = latestSession?.title.orEmpty(),
                lastAt = latestSession?.updated ?: 0L,
                model = latestSession?.model?.takeIf { it.isNotBlank() },
            )
        } else null
        mutableStateOf<AgentContact?>(initialContact)
    }
    var muted by remember { mutableStateOf(false) }
    var showManager by remember { mutableStateOf(false) }
    var showNewSession by remember { mutableStateOf(false) }
    var showEditBot by remember { mutableStateOf(false) }
    var showEditAvatar by remember { mutableStateOf(false) }
    var showVoiceCall by remember { mutableStateOf(false) }
    var showCapabilitySheet by remember { mutableStateOf(false) }
    var showSessionsSheet by remember { mutableStateOf(false) }

    LaunchedEffect(agentId, refreshTick) {
        BotTeamStore.ensureSeeded(ctx)
        BotTeamStore.syncWithHub(ctx)
    }
    LaunchedEffect(agentId, refreshTick, storeVersion) {
        muted = AgentPrefs.muted(ctx, agentId)
        botFriend = BotTeamStore.getBot(ctx, agentId)
        val engineToLoad = botFriend?.engine ?: agentId
        if (engineToLoad == VIBELINK_AGENT_ID) {
            val list = withContext(Dispatchers.IO) { ConversationStore.shared(ctx).list() }
            conversations = list.size
            latest = list.maxByOrNull { it.updatedAt }?.title
            contact = AgentContact(
                id = VIBELINK_AGENT_ID,
                name = botFriend?.name?.takeIf { it.isNotBlank() } ?: "Mika",
                kind = AgentKind.Phone,
                online = true,
                deviceCount = 1,
                sessionCount = list.size,
                lastText = latest ?: "",
                lastAt = list.maxOfOrNull { it.updatedAt } ?: 0L,
            )
        } else {
            val allEngineSessions = runCatching { loadAgentSessions(ctx, engineToLoad) }
                .getOrElse { RemoteSessionCache.getCachedSessions(ctx, engineToLoad) }
            val boundIds = botFriend?.let { (it.sessionIds + it.activeSessionId).filter { id -> id.isNotBlank() }.toSet() }
            sessions = BotProfilePresentation.sessions(allEngineSessions, boundIds) { it.id }
            val latestSession = sessions.firstOrNull()
            contact = AgentContact(
                id = agentId,
                name = botFriend?.name ?: agentDisplayName(agentId),
                kind = AgentKind.Desktop,
                online = sessions.isNotEmpty(),
                deviceCount = sessions.map { it.device }.filter { it.isNotBlank() }.distinct().size.coerceAtLeast(1),
                sessionCount = sessions.size,
                lastText = latestSession?.title.orEmpty(),
                lastAt = latestSession?.updated ?: 0L,
                model = latestSession?.model?.takeIf { it.isNotBlank() },
            )
        }
    }

    val currentBot = botFriend
    val underlyingModelName = BotProfilePresentation.modelName(targetEngine, currentBot?.model, contact?.model)
    val name = currentBot?.name?.takeIf { it.isNotBlank() } ?: currentBot?.jobTitle?.takeIf { it.isNotBlank() } ?: contact?.name ?: agentDisplayName(targetEngine)
    val weChatLinkBlue = Color(0xFF576B95)
    val weChatGrayBg = if (c.bg.red > 0.5f) Color(0xFFF2F3F5) else c.bg

    Column(
        Modifier
            .fillMaxSize()
            .background(weChatGrayBg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 1. 微信极简白底顶栏（左侧返回箭头，右侧设置与更多按钮）
        Row(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(c.surface)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BarIconButton(VibeLinkIcons.Back, "返回", onBack)
            Row(verticalAlignment = Alignment.CenterVertically) {
                BarIconButton(VibeLinkIcons.Gear, "编辑资料与配方", {
                    if (currentBot != null) showEditBot = true else showEditAvatar = true
                })
                if (currentBot != null) {
                    BarIconButton(VibeLinkIcons.Spark, "技能与配方详情", {
                        showCapabilitySheet = true
                    })
                }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // 2. 顶部微信个人名片区（白底：左圆角方形头像 + 右三行灰字信息 + 下方「资料」行）
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(c.surface),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, end = 20.dp, top = 14.dp, bottom = 22.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    AgentAvatar(
                        id = agentId,
                        name = name,
                        size = 66.dp,
                        cornerRadius = 10.dp,
                        onClick = { showEditAvatar = true },
                    )
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        // 第一行：大号加粗主标题（角色名 + 职位备注）+ 标星图标
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val remarkTitle = if (currentBot != null && currentBot.jobTitle.isNotBlank()) {
                                "$name（${currentBot.jobTitle}）"
                            } else {
                                name
                            }
                            Text(
                                remarkTitle,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = c.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "👤",
                                fontSize = 13.sp,
                            )
                            if (currentBot?.lifecycle == BotLifecycle.MANAGEMENT) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "★",
                                    fontSize = 16.sp,
                                    color = Color(0xFFF59E0B),
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        // 模型由当前 Bot 绑定决定，Agent 单独展示。
                        Text(
                            "运行模型：  $underlyingModelName",
                            fontSize = 13.5.sp,
                            color = c.ink3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "Agent：  ${agentDisplayName(targetEngine)}${if (targetEngine == "codex") " · 官方 CLI" else ""}",
                            fontSize = 13.5.sp,
                            color = c.ink3,
                        )
                        Spacer(Modifier.height(3.dp))
                        // 第三行：工位 ID / 引擎标识
                        Text(
                            "工位 ID：  ${currentBot?.id ?: agentId}",
                            fontSize = 13.5.sp,
                            color = c.ink3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        // 第四行：部门 / 工作区模式
                        val regionOrDept = if (currentBot != null) {
                            "${currentBot.department} · ${if (currentBot.isolationMode == "worktree") "独立 Worktree" else "共享工作区"}"
                        } else if (contact?.kind == AgentKind.Phone) {
                            "手机端本机常驻"
                        } else {
                            "电脑端编码引擎"
                        }
                        Text(
                            "部门：  $regionOrDept",
                            fontSize = 13.5.sp,
                            color = c.ink3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // 微信风格极细分割线
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp)
                        .height(0.5.dp)
                        .background(c.edge),
                )

                // 「资料」行（完全对齐图2微信「资料 + 灰字副标题 + 右箭头」）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (currentBot != null) showEditBot = true else showEditAvatar = true
                        }
                        .padding(horizontal = 22.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "资料",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = c.ink,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            currentBot?.publicDescription?.ifBlank {
                                "设置角色的职位备注、常驻指令 Prompt、工具边界与自定义头像。"
                            } ?: "更换自定义头像、设置通知权限与工作区偏好。",
                            fontSize = 13.sp,
                            color = c.ink3,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 18.sp,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("›", fontSize = 22.sp, color = c.ink3)
                }
            }

            Spacer(Modifier.height(8.dp))

            // 3. 中部信息区（仿微信「朋友圈」四宫格缩略图行 + 关联会话行）
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(c.surface),
            ) {
                if (currentBot != null) {
                    // 仿微信「朋友圈」四宫格缩略卡片行：展示技能、指令、记忆与权限边界
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { showCapabilitySheet = true }
                            .padding(horizontal = 22.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "技能与记忆",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = c.ink,
                            modifier = Modifier.width(88.dp),
                        )
                        Row(
                            Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val activeSkills = currentBot.skills.count { it.enabled }
                            val activeMem = currentBot.memories.count { !it.archived }
                            val tiles = listOf(
                                Triple("📜 指令", "SOP v${currentBot.instructionsVersion}", Color(0xFF3B82F6)),
                                Triple("📚 技能", "${activeSkills}项启用", Color(0xFF8B5CF6)),
                                Triple("🧠 记忆", "${activeMem}条常驻", Color(0xFF10B981)),
                                Triple(
                                    "🛡️ 边界",
                                    if (currentBot.disabledTools.isEmpty()) "全栈读写" else "只读核查",
                                    if (currentBot.disabledTools.isEmpty()) Color(0xFF059669) else Color(0xFFD97706),
                                ),
                            )
                            tiles.forEach { (title, sub, tint) ->
                                Column(
                                    Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(tint.copy(alpha = 0.10f))
                                        .border(0.5.dp, tint.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                                        .padding(4.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        title,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = tint,
                                        maxLines = 1,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        sub,
                                        fontSize = 9.5.sp,
                                        color = c.ink2,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("›", fontSize = 22.sp, color = c.ink3)
                    }

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 22.dp)
                            .height(0.5.dp)
                            .background(c.edge),
                    )
                }

                // 关联会话行
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showSessionsSheet = true }
                        .padding(horizontal = 22.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "底层会话",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = c.ink,
                        modifier = Modifier.width(88.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        val activeTitle = sessions.firstOrNull { it.id == currentBot?.activeSessionId }?.title
                            ?: sessions.firstOrNull()?.title
                            ?: "暂无绑定会话（点击新建或切换）"
                        Text(
                            activeTitle,
                            fontSize = 14.sp,
                            color = c.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "共 ${sessions.size} 个 ${agentDisplayName(targetEngine)} 会话 · 思考强度: ${currentBot?.thinkingEffort ?: "medium"}",
                            fontSize = 12.sp,
                            color = c.ink3,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("›", fontSize = 22.sp, color = c.ink3)
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp)
                        .height(0.5.dp)
                        .background(c.edge),
                )

                // 消息免打扰行
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            muted = !muted
                            AgentPrefs.setMuted(ctx, agentId, muted)
                        }
                        .padding(horizontal = 22.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "消息通知",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = c.ink,
                        modifier = Modifier.width(88.dp),
                    )
                    Text(
                        if (muted) "免打扰" else "已开启",
                        fontSize = 14.sp,
                        color = c.ink3,
                        modifier = Modifier.weight(1f),
                    )
                    Text("›", fontSize = 22.sp, color = c.ink3)
                }
            }

            Spacer(Modifier.height(8.dp))

            // 4. 底部微信风格居中操作通栏（「发消息」+「音视频通话」）
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(c.surface),
            ) {
                // 发消息（傳送訊息）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clickable {
                            if (currentBot != null) {
                                onOpenEngineSession(currentBot.id, currentBot.activeSessionId)
                            } else {
                                onOpenChat()
                            }
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = VibeLinkIcons.Message,
                        contentDescription = null,
                        tint = weChatLinkBlue,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "发消息",
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = weChatLinkBlue,
                    )
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(c.edge),
                )

                // 语音/实时通话（語音/視訊通話）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clickable {
                            if (currentBot != null) {
                                showVoiceCall = true
                            } else {
                                onOpenChat()
                            }
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = VibeLinkIcons.Phone,
                        contentDescription = null,
                        tint = weChatLinkBlue,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "语音通话",
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = weChatLinkBlue,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    // 技能、指令与记忆详情弹窗（从「技能与记忆」行点击打开，保持主页如微信般极简干净）
    if (showCapabilitySheet && currentBot != null) {
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        var copiedToast by remember { mutableStateOf(false) }
        var expandSop by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showCapabilitySheet = false },
            title = {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("$name · 技能与记忆详情", style = VibeLinkType.Title)
                    Text(
                        if (copiedToast) "✅ 已复制" else "📤 导出 JSON",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.accent,
                        modifier = Modifier.clickable {
                            val jsonStr = BotTeamStore.exportBotRecipeJson(currentBot)
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(jsonStr))
                            copiedToast = true
                        },
                    )
                }
            },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "🛡️ 工具边界：${
                            if (currentBot.disabledTools.isEmpty()) "🌿 全栈读写 + Git Worktree"
                            else "🔒 只读核查/审校边界 (禁用: ${currentBot.disabledTools.joinToString(", ")})"
                        } · 审批模式: ${currentBot.approvalMode}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (currentBot.disabledTools.isEmpty()) Color(0xFF1F9D55) else Color(0xFFD97706),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { expandSop = !expandSop },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "📜 常驻角色指令 (SOUL.md · v${currentBot.instructionsVersion})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.ink,
                        )
                        Text(
                            if (expandSop) "收起 ▲" else "展开 ▼",
                            fontSize = 11.sp,
                            color = c.accent,
                        )
                    }
                    Text(
                        currentBot.rolePrompt.ifBlank { "未设置特定职责 Prompt" },
                        style = VibeLinkType.Caption,
                        color = c.ink2,
                        maxLines = if (expandSop) 100 else 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (currentBot.skills.isNotEmpty()) {
                        Text(
                            "技能（点击启用或停用）：",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.ink,
                        )
                        currentBot.skills.forEach { sk ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(c.fill)
                                    .clickable {
                                        BotTeamStore.toggleSkillEnabled(ctx, currentBot.id, sk.id, !sk.enabled)
                                        refreshTick++
                                    }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "[${sk.source}] ${sk.name}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (sk.enabled) c.ink else c.ink3,
                                    )
                                    Text(sk.description, fontSize = 11.sp, color = c.ink3)
                                }
                                Text(
                                    if (sk.enabled) "✓ 已启用" else "🔒 已禁用",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (sk.enabled) Color(0xFF1F9D55) else Color(0xFFD97706),
                                )
                            }
                        }
                    }
                    val exampleReqs = currentBot.exampleRequests.ifEmpty {
                        BotTeamStore.TEMPLATES.firstOrNull { it.id == currentBot.templateId }?.exampleRequests.orEmpty()
                    }
                    if (exampleReqs.isNotEmpty()) {
                        Text(
                            "💡 快捷指令示例（点击进入会话）：",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.ink,
                        )
                        exampleReqs.forEach { req ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(c.accent.copy(alpha = 0.08f))
                                    .clickable {
                                        showCapabilitySheet = false
                                        onOpenEngineSession(currentBot.id, currentBot.activeSessionId)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text("💬 $req", fontSize = 11.5.sp, color = c.accent)
                            }
                        }
                    }
                    if (currentBot.memories.isNotEmpty()) {
                        Text(
                            "🧠 常驻记忆 (${currentBot.memories.count { !it.archived }} 条活跃)：",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.ink,
                        )
                        currentBot.memories.filter { !it.archived }.take(8).forEach { m ->
                            val prefix = if (m.topic.isNotBlank()) "[专题:${m.topic}] " else ""
                            Text("• $prefix${m.fact}", fontSize = 11.5.sp, color = c.ink2)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCapabilitySheet = false
                        showEditBot = true
                    },
                ) {
                    Text("编辑角色与配方", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCapabilitySheet = false }) {
                    Text("关闭")
                }
            },
        )
    }

    // 关联底层会话列表弹窗（从「底层会话」行点击打开）
    if (showSessionsSheet) {
        AlertDialog(
            onDismissRequest = { showSessionsSheet = false },
            title = {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${agentDisplayName(targetEngine)} 会话列表 (${sessions.size})", style = VibeLinkType.Title)
                    Text(
                        "+ 新建会话",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.accent,
                        modifier = Modifier.clickable {
                            showSessionsSheet = false
                            showNewSession = true
                        },
                    )
                }
            },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (sessions.isEmpty()) {
                        Text("当前暂无底层会话，点击右上角「+ 新建会话」即可创建。", style = VibeLinkType.Caption, color = c.ink3)
                    } else {
                        val boundSet = currentBot?.sessionIds.orEmpty().toSet()
                        sessions.take(30).forEach { s ->
                            val isBound = s.id in boundSet || s.id == currentBot?.activeSessionId
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isBound) c.accent.copy(alpha = 0.08f) else c.fill)
                                    .border(
                                        0.5.dp,
                                        if (isBound) c.accent.copy(alpha = 0.4f) else c.edge,
                                        RoundedCornerShape(10.dp),
                                    )
                                    .clickable {
                                        showSessionsSheet = false
                                        if (currentBot != null) {
                                            BotTeamStore.bindSessionToBot(ctx, currentBot.id, s.id, s.title)
                                            onOpenEngineSession(currentBot.id, s.id)
                                        } else {
                                            onOpenEngineSession(targetEngine, s.id)
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isBound) {
                                            Box(
                                                Modifier
                                                    .clip(VibeLinkShapes.Pill)
                                                    .background(c.accent)
                                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                                            ) {
                                                Text(
                                                    "当前绑定",
                                                    fontSize = 9.5.sp,
                                                    color = c.onAccent,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(
                                            s.title.ifBlank { s.id },
                                            style = VibeLinkType.Body,
                                            color = c.ink,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        "${s.device} · ${agentDisplayName(s.agent)} · ${conversationTime(s.updated, System.currentTimeMillis())}",
                                        style = VibeLinkType.Hint,
                                        color = c.ink3,
                                        maxLines = 1,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "介入 ›",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = c.accent,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSessionsSheet = false }) {
                    Text("关闭")
                }
            },
        )
    }

    if (showManager) ConversationManagerDialog(onDismiss = { showManager = false })
    if (showEditBot && currentBot != null) {
        CreateOrEditBotDialog(
            initialBot = currentBot,
            onDismiss = { showEditBot = false },
            onSaved = {
                showEditBot = false
                refreshTick++
            },
        )
    }
    if (showVoiceCall && currentBot != null) {
        BotVoiceCallDialog(
            bot = currentBot,
            onDismiss = { showVoiceCall = false },
        )
    }
    if (showNewSession) {
        val recentCwds = sessions.map { it.cwd.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("C--") && !it.startsWith("D--") }
            .distinct()
            .take(5)
        NewRemoteSessionDialog(
            boundBot = currentBot,
            initialAgent = if (targetEngine in BotModelPolicy.engines.toSet()) targetEngine else "codex",
            initialDevice = currentBot?.device ?: sessions.firstOrNull()?.device ?: "pc",
            initialCwd = currentBot?.cwd ?: recentCwds.firstOrNull() ?: "D:\\AI\\VibeLink",
            recentCwds = recentCwds,
            onDismiss = { showNewSession = false },
            onCreated = { created ->
                showNewSession = false
                if (currentBot != null) {
                    BotTeamStore.bindSessionToBot(ctx, currentBot.id, created.id, created.title)
                    onOpenEngineSession(currentBot.id, created.id)
                } else {
                    onOpenEngineSession(created.agent, created.id)
                }
            },
        )
    }

    if (showEditAvatar) {
        AvatarQuickEditDialog(
            agentId = agentId,
            currentName = name,
            engine = targetEngine,
            initialAvatar = currentBot?.avatar.orEmpty(),
            onDismiss = { showEditAvatar = false },
            onSaved = {
                showEditAvatar = false
                botFriend = BotTeamStore.getBot(ctx, agentId)
                refreshTick++
            },
        )
    }
}

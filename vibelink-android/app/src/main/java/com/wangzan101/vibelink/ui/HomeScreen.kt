package com.wangzan101.vibelink.ui

import com.wangzan101.vibelink.remote.RemoteApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.vibelink.ui.components.BarIconButton
import com.wangzan101.vibelink.ui.components.PageBackground
import com.wangzan101.vibelink.ui.theme.VibeLinkIcons
import com.wangzan101.vibelink.ui.theme.VibeLinkShapes
import com.wangzan101.vibelink.ui.theme.VibeLinkTheme
import com.wangzan101.vibelink.ui.theme.VibeLinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private enum class HomeFilter(val label: String) {
    ALL("全部"),
    GROUPS("💬 群聊"),
    MANAGEMENT("👔 长期 Bot"),
    PROJECT("🛠️ 项目 Bot"),
    ENGINES("🖥 引擎"),
}

/**
 * 消息页：QQ/微信式 IM 列表
 * - 点击 Team 群聊 -> 直接进入多 Bot 群聊页
 * - 点击 Bot 好友行 -> 直接进入与该 Bot 的 1 对 1 聊天页发消息（符合 QQ/微信直觉）
 * - 点击 Bot 好友左侧头像 -> 进入 Bot 资料与底层会话工位管理页
 */
@Composable
fun HomeScreen(
    onOpenVibeLink: () -> Unit,
    onOpenAgent: (String) -> Unit,
    onOpenAgentSession: (String, String) -> Unit = { agent, _ -> onOpenAgent(agent) },
    onOpenProfile: (String) -> Unit,
    onOpenGroup: (String) -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenCindy: () -> Unit = {},
    onOpenClash: () -> Unit = {},
    updateAvailable: Boolean = false,
    vibelinkRunning: Boolean = false,
) {
    val ctx = LocalContext.current
    val c = VibeLinkTheme.colors
    var contacts by remember { mutableStateOf<List<AgentContact>>(emptyList()) }

    LaunchedEffect(Unit) {
        BotTeamStore.ensureSeeded(ctx)
    }
    val allBots by BotTeamStore.botsFlow.collectAsState()
    val groups by BotTeamStore.groupsFlow.collectAsState()
    val storeVersion by BotTeamStore.versionFlow.collectAsState()
    val bots = remember(allBots, storeVersion) { allBots.filter { !it.archived } }

    var filter by remember { mutableStateOf(HomeFilter.ALL) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var botMenuFor by remember { mutableStateOf<String?>(null) }
    var editingBot by remember { mutableStateOf<BotFriend?>(null) }
    var showPlusMenu by remember { mutableStateOf(false) }
    var showRecruitBot by remember { mutableStateOf(false) }
    var showCreateGroup by remember { mutableStateOf(false) }
    var newSessionAgent by remember { mutableStateOf<String?>(null) }
    var showHomeWorkspace by remember { mutableStateOf(false) }
    var showRemoteDesktop by remember { mutableStateOf(false) }
    var showBetaInviteDialog by remember { mutableStateOf(false) }
    var betaInviteInput by remember { mutableStateOf(com.wangzan101.vibelink.remote.RemoteStore.betaInviteCode(ctx)) }
    var betaActivating by remember { mutableStateOf(false) }
    var betaError by remember { mutableStateOf<String?>(null) }
    var betaQuotaText by remember { mutableStateOf(com.wangzan101.vibelink.remote.RemoteStore.betaLabel(ctx)) }
    LaunchedEffect(Unit) {
        if (com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION && com.wangzan101.vibelink.remote.RemoteStore.isPaired(ctx)) {
            val quota = withContext(Dispatchers.IO) {
                runCatching { RemoteApi.betaQuota(com.wangzan101.vibelink.remote.RemoteStore.hub(ctx), com.wangzan101.vibelink.remote.RemoteStore.token(ctx)) }.getOrNull()
            }
            if (quota != null) betaQuotaText = "今日调用 ${quota.optLong("usedRequests")}/${quota.optLong("dailyLimitRequests")} 次 · ${quota.optLong("totalTokens")} tokens"
        }
    }

    val homeScope = rememberCoroutineScope()

    // 扫一扫：扫到 vibelink://pair 就交给 PairInbox，VibeLinkRoot 会打开远程会话页弹「连接到 xx？」
    val pairInbox by com.wangzan101.vibelink.remote.PairInbox.pending.collectAsState()
    val paired = remember(tick, pairInbox) { com.wangzan101.vibelink.remote.RemoteStore.isPaired(ctx) }
    val scanPair = androidx.activity.compose.rememberLauncherForActivityResult(io.github.g00fy2.quickie.ScanQRCode()) { r ->
        when (r) {
            is io.github.g00fy2.quickie.QRResult.QRSuccess -> {
                val raw = r.content.rawValue ?: r.content.rawBytes?.let { String(it) }
                if (!com.wangzan101.vibelink.remote.PairInbox.offer(raw)) {
                    android.widget.Toast.makeText(ctx, "这不是 VibeLink 配对码：请扫电脑上「远程控制」弹窗（或 VibeLink 控制台「📱 连接手机」）里的二维码", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            io.github.g00fy2.quickie.QRResult.QRMissingPermission ->
                android.widget.Toast.makeText(ctx, "需要相机权限才能扫码", android.widget.Toast.LENGTH_SHORT).show()
            is io.github.g00fy2.quickie.QRResult.QRError ->
                android.widget.Toast.makeText(ctx, "扫码出错：${r.exception.message}", android.widget.Toast.LENGTH_SHORT).show()
            else -> Unit
        }
    }

    LaunchedEffect(tick) {
        loading = true
        // 本地 VibeLink 与缓存的远程引擎立即上屏（0ms）
        val local = runCatching { loadVibeLinkContact(ctx) }.getOrNull()
        val cached = withContext(Dispatchers.IO) { readAgentCache(ctx) }
        if (local != null) {
            contacts = (listOf(local) + cached)
                .sortedWith(compareByDescending<AgentContact> { it.pinned }.thenByDescending { it.lastAt })
        }
        loading = false
        // 远程最多等 8 秒：连不上就保留上次的列表（标离线），不要一离线就消失
        val remote = withTimeoutOrNull(8_000) {
            runCatching { loadRemoteContacts(ctx) }.getOrElse { emptyList() }
        }.orEmpty()
        if (remote.isNotEmpty()) {
            withContext(Dispatchers.IO) { writeAgentCache(ctx, remote) }
            contacts = (listOfNotNull(local) + remote)
                .sortedWith(compareByDescending<AgentContact> { it.pinned }.thenByDescending { it.lastAt })
            // 后台静默预热常用会话与 Bot 绑定会话的消息缓存，保证点进任意联系人秒开
            launch(Dispatchers.IO) {
                runCatching { RemoteSessionCache.prewarmRecentSessions(ctx) }
            }
        } else if (local != null) {
            contacts = (listOf(local) + cached)
                .sortedWith(compareByDescending<AgentContact> { it.pinned }.thenByDescending { it.lastAt })
        }
        error = null
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }

    PageBackground {
        Column(Modifier.fillMaxSize()) {
            // 顶栏（严格控制元素宽度，适配 320dp–420dp 全尺寸真机屏幕，绝不溢出）
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "VibeLink",
                    style = VibeLinkType.Title.copy(fontSize = 21.sp),
                    color = c.ink,
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .padding(end = 4.dp)
                        .clip(VibeLinkShapes.Pill)
                        .background(c.surface)
                        .border(0.5.dp, c.edge, VibeLinkShapes.Pill)
                        .clickable { showRemoteDesktop = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        "🖥 桌面",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = c.ink,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                BarIconButton(VibeLinkIcons.Terminal, "浏览远程工作区文件、Git、定时任务与插件", { showHomeWorkspace = true })
                Box {
                    BarIconButton(VibeLinkIcons.Plus, "招募 Bot / 发起群聊 / 新建会话", { showPlusMenu = true })
                    DropdownMenu(
                        expanded = showPlusMenu,
                        onDismissRequest = { showPlusMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("📷 扫一扫（连接电脑）") },
                            onClick = {
                                showPlusMenu = false
                                scanPair.launch(null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("💬 发起 Team 群聊（多 Bot 协同）") },
                            onClick = {
                                showPlusMenu = false
                                showCreateGroup = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("👤 招募新 Bot 好友（设置职位与模板）") },
                            onClick = {
                                showPlusMenu = false
                                showRecruitBot = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("⚡ 新建底层远程编码会话") },
                            onClick = {
                                showPlusMenu = false
                                newSessionAgent = "codex"
                            },
                        )
                    }
                }
                BarIconButton(VibeLinkIcons.Refresh, "刷新", { tick++ })
            }

            // 还没连过电脑：顶部给一个扫码入口（内测版额外提供邀请码一键激活免费 DeepSeek 入口）
            if (com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(VibeLinkShapes.Pill)
                        .background(c.accent.copy(alpha = 0.12f))
                        .border(0.5.dp, c.accent.copy(alpha = 0.4f), VibeLinkShapes.Pill)
                        .clickable { showBetaInviteDialog = true }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🎁", fontSize = 15.sp)
                    Text(
                        if (betaQuotaText.isNotBlank()) "已激活 $betaQuotaText · DeepSeek 免费通道"
                        else "内测专享 · 点此输入邀请码免费解锁 DeepSeek 模型",
                        Modifier.padding(start = 8.dp).weight(1f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (betaQuotaText.isNotBlank()) "查看/切换" else "去激活",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = c.accent,
                        maxLines = 1,
                    )
                }
            } else if (!paired) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(VibeLinkShapes.Pill)
                        .background(c.surface)
                        .border(0.5.dp, c.edge, VibeLinkShapes.Pill)
                        .clickable { scanPair.launch(null) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("📷", fontSize = 16.sp)
                    Text(
                        "还没连接电脑 · 点这里扫码连接",
                        Modifier.padding(start = 8.dp).weight(1f),
                        fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = c.ink, maxLines = 1,
                    )
                    Text("扫一扫", fontSize = 13.sp, color = c.accent, maxLines = 1)
                }
            }

            // QQ/微信分组筛选胶囊栏
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeFilter.entries.forEach { f ->
                    val selected = filter == f
                    Box(
                        Modifier
                            .clip(VibeLinkShapes.Pill)
                            .background(if (selected) c.ink else c.surface)
                            .border(0.5.dp, if (selected) c.ink else c.edge, VibeLinkShapes.Pill)
                            .clickable { filter = f }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    ) {
                        Text(
                            f.label,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) c.bg else c.ink2,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }

            if (error != null) {
                Text(
                    error.orEmpty(),
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    style = VibeLinkType.Hint,
                    color = c.bad,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val visibleGroups = remember(groups, filter, storeVersion) {
                if (filter == HomeFilter.ALL || filter == HomeFilter.GROUPS) groups else emptyList()
            }
            val visibleBots = remember(bots, filter, storeVersion) {
                when (filter) {
                    HomeFilter.ALL -> bots
                    HomeFilter.MANAGEMENT -> bots.filter { it.lifecycle == BotLifecycle.MANAGEMENT }
                    HomeFilter.PROJECT -> bots.filter { it.lifecycle == BotLifecycle.PROJECT }
                    else -> emptyList()
                }
            }
            val visibleContacts = remember(contacts, filter) {
                when (filter) {
                    HomeFilter.ALL, HomeFilter.ENGINES -> contacts
                    HomeFilter.MANAGEMENT -> contacts.filter { it.kind == AgentKind.Phone }
                    else -> emptyList()
                }
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                // 1. Team 群聊区
                if (visibleGroups.isNotEmpty()) {
                    item("header_groups") {
                        SectionSmallHeader(
                            title = "Team 协作群聊 (${visibleGroups.size})",
                            actionText = "+ 建群",
                            onAction = { showCreateGroup = true },
                        )
                    }
                    items(visibleGroups, key = { "grp_" + it.id }) { group ->
                        TeamGroupRow(
                            group = group,
                            allBots = allBots,
                            storeVersion = storeVersion,
                            onClick = { onOpenGroup(group.id) },
                        )
                    }
                }

                // 2. Bot 好友团队区（点击行直接发消息单聊，点击头像进工位与会话设置）
                if (visibleBots.isNotEmpty()) {
                    item("header_bots") {
                        SectionSmallHeader(
                            title = "Bot 团队好友 (${visibleBots.size})",
                            actionText = "+ 招募 Bot",
                            onAction = { showRecruitBot = true },
                        )
                    }
                    items(visibleBots, key = { "bot_" + it.id }) { bot ->
                        BotFriendRow(
                            bot = bot,
                            menuOpen = botMenuFor == bot.id,
                            // 遵循 QQ/微信操作逻辑：点击联系人直接进入聊天窗口发消息！
                            onClick = {
                                onOpenAgentSession(bot.id, bot.activeSessionId)
                            },
                            // 点击左侧头像：进入该 Bot 的资料与底层会话工位管理页
                            onAvatarClick = {
                                onOpenProfile(bot.id)
                            },
                            onLongClick = { botMenuFor = bot.id },
                            onDismissMenu = { botMenuFor = null },
                            onEditBot = {
                                botMenuFor = null
                                editingBot = bot
                            },
                            onOpenWorkstation = {
                                botMenuFor = null
                                onOpenProfile(bot.id)
                            },
                        )
                    }
                }

                // 3. 本机 VibeLink 与底层远程编码引擎
                if (visibleContacts.isNotEmpty()) {
                    item("header_engines") {
                        SectionSmallHeader(
                            title = "本机助理与底层 Agent 引擎 (${visibleContacts.size})",
                            actionText = "+ 新建会话",
                            onAction = { newSessionAgent = "codex" },
                        )
                    }
                    items(visibleContacts, key = { "eng_" + it.id }) { contact ->
                        ContactRow(
                            contact = contact,
                            running = contact.running || (contact.kind == AgentKind.Phone && vibelinkRunning),
                            menuOpen = menuFor == contact.id,
                            onClick = {
                                AgentPrefs.markSeen(ctx, contact.id)
                                if (contact.kind == AgentKind.Phone) onOpenVibeLink() else onOpenAgent(contact.id)
                            },
                            onAvatar = { onOpenProfile(contact.id) },
                            onLongClick = { menuFor = contact.id },
                            onDismissMenu = { menuFor = null },
                            onTogglePin = {
                                AgentPrefs.setPinned(ctx, contact.id, !contact.pinned)
                                menuFor = null
                                tick++
                            },
                            onMarkRead = {
                                AgentPrefs.markSeen(ctx, contact.id)
                                menuFor = null
                                tick++
                            },
                            onToggleMute = {
                                AgentPrefs.setMuted(ctx, contact.id, !contact.muted)
                                menuFor = null
                                tick++
                            },
                        )
                    }
                }

                if (visibleGroups.isEmpty() && visibleBots.isEmpty() && visibleContacts.isEmpty() && !loading) {
                    item("empty") {
                        Text(
                            "当前分类下暂无条目。\n可点击右上角「+」招募新 Bot 好友或组建 Team 群聊。",
                            Modifier.fillMaxWidth().padding(28.dp),
                            style = VibeLinkType.Caption,
                            color = c.ink3,
                        )
                    }
                }
            }

            BottomBar(
                updateAvailable = updateAvailable,
                onMessages = {},
                onClash = onOpenClash,
                onMine = onOpenSettings,
            )
        }
    }

    if (showRecruitBot) {
        CreateOrEditBotDialog(
            initialBot = null,
            onDismiss = { showRecruitBot = false },
            onSaved = {
                showRecruitBot = false
            },
        )
    }

    editingBot?.let { target ->
        CreateOrEditBotDialog(
            initialBot = target,
            onDismiss = { editingBot = null },
            onSaved = {
                editingBot = null
            },
        )
    }

    if (showCreateGroup) {
        CreateOrEditTeamGroupDialog(
            initialGroup = null,
            onDismiss = { showCreateGroup = false },
            onSaved = { created ->
                showCreateGroup = false
                onOpenGroup(created.id)
            },
        )
    }

    if (showBetaInviteDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!betaActivating) showBetaInviteDialog = false },
            title = { Text("🎁 VibeLink 内测邀请码激活") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "输入您的专属内测邀请码（如 VLK-BETA-01），即可免费使用 DeepSeek V4.1 Flash 模型（含思考链与百万上下文），并隔离绑定您自己的远程工作台：",
                        style = VibeLinkType.Caption,
                        color = c.ink2,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = betaInviteInput,
                        onValueChange = {
                            betaInviteInput = it
                            betaError = null
                        },
                        singleLine = true,
                        label = { Text("内测邀请码") },
                        placeholder = { Text("例如：VLK-BETA-01") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (betaError != null) {
                        Text(betaError.orEmpty(), style = VibeLinkType.Caption, color = c.bad)
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = !betaActivating && betaInviteInput.isNotBlank(),
                    onClick = {
                        betaActivating = true
                        betaError = null
                        homeScope.launch {
                            val res = withContext(Dispatchers.IO) {
                                runCatching {
                                    com.wangzan101.vibelink.remote.RemoteApi.activateBetaInvite(
                                        betaInviteInput.trim(),
                                        android.os.Build.MODEL ?: "Android",
                                    )
                                }
                            }
                            betaActivating = false
                            res.onSuccess { json ->
                                val tok = json.optString("token")
                                val hubUrl = json.optString("hubUrl", "https://hub.vibelink.cloud")
                                val label = json.optString("label", "内测席位")
                                val used = json.optInt("usedRequests", 0)
                                val limit = json.optInt("dailyLimitRequests", 300)
                                com.wangzan101.vibelink.remote.RemoteStore.saveBetaActivation(
                                    ctx,
                                    betaInviteInput.trim(),
                                    hubUrl,
                                    tok,
                                    label,
                                )
                                com.wangzan101.vibelink.settings.Preconfigure.onPaired(ctx)
                                betaQuotaText = "$label (今日调用 $used/$limit 次 · ${json.optLong("totalTokens")} tokens)"
                                showBetaInviteDialog = false
                                tick++
                                android.widget.Toast.makeText(
                                    ctx,
                                    "🎉 激活成功！已为您免费开启 DeepSeek 模型通道",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }.onFailure { e ->
                                betaError = e.message ?: "激活失败，请检查网络或邀请码"
                            }
                        }
                    },
                ) {
                    Text(if (betaActivating) "激活中…" else "立即激活")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    enabled = !betaActivating,
                    onClick = { showBetaInviteDialog = false },
                ) {
                    Text("取消")
                }
            },
        )
    }

    if (showHomeWorkspace) {
        val hub = remember { com.wangzan101.vibelink.remote.RemoteStore.hub(ctx) }
        val token = remember { com.wangzan101.vibelink.remote.RemoteStore.token(ctx) }
        WorkspaceInspectorDialog(
            hub = hub,
            token = token,
            device = "pc",
            initialCwd = if (com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION) "" else "D:\\AI\\VibeLink",
            initialTab = 0,
            onDismiss = { showHomeWorkspace = false },
            onSelectCwd = { showHomeWorkspace = false },
            onInsertFileMention = { showHomeWorkspace = false },
            onRequestUploadToDir = { showHomeWorkspace = false },
        )
    }

    if (showRemoteDesktop) {
        val hub = remember { com.wangzan101.vibelink.remote.RemoteStore.hub(ctx) }
        val token = remember { com.wangzan101.vibelink.remote.RemoteStore.token(ctx) }
        RemoteDesktopDialog(
            hub = hub,
            token = token,
            device = "pc",
            onDismiss = { showRemoteDesktop = false },
        )
    }

    newSessionAgent?.let { targetAgent ->
        NewRemoteSessionDialog(
            initialAgent = targetAgent,
            onDismiss = { newSessionAgent = null },
            onCreated = { created ->
                newSessionAgent = null
                onOpenAgentSession(created.agent, created.id)
            },
        )
    }
}

@Composable
private fun SectionSmallHeader(
    title: String,
    actionText: String,
    onAction: () -> Unit,
) {
    val c = VibeLinkTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            actionText,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.accent,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.clickable(onClick = onAction),
        )
    }
}

@Composable
private fun TeamGroupRow(
    group: TeamGroup,
    allBots: List<BotFriend>,
    storeVersion: Int,
    onClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val c = VibeLinkTheme.colors
    val draftText = remember(group.id, storeVersion) {
        BotTeamStore.getDraftText(ctx, "group:${group.id}").trim()
    }
    val previewText = remember(group, allBots, storeVersion) {
        BotTeamStore.formatGroupPreview(ctx, group, allBots)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamGroupAvatar(group, allBots, 52.dp, onClick = onClick)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        group.name,
                        style = VibeLinkType.BodyStrong,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(c.accent.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 1.5.dp),
                    ) {
                        Text(
                            "${group.botIds.size}人",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.accent,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (group.lastAt > 0) conversationTime(group.lastAt, System.currentTimeMillis()) else "",
                    style = VibeLinkType.Hint,
                    color = c.ink3,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Spacer(Modifier.height(3.dp))
            if (draftText.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "[草稿] ",
                        style = VibeLinkType.Caption,
                        color = c.bad,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        draftText.replace("\n", " "),
                        style = VibeLinkType.Caption,
                        color = c.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    previewText,
                    style = VibeLinkType.Caption,
                    color = c.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BotFriendRow(
    bot: BotFriend,
    menuOpen: Boolean,
    onClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onLongClick: () -> Unit,
    onDismissMenu: () -> Unit,
    onEditBot: () -> Unit,
    onOpenWorkstation: () -> Unit,
) {
    val ctx = LocalContext.current
    val c = VibeLinkTheme.colors
    val storeVersion by BotTeamStore.versionFlow.collectAsState()
    val botDraft = remember(bot.id, storeVersion) {
        BotTeamStore.getDraftText(ctx, "bot:${bot.id}").trim()
    }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 点击头像 -> 打开 Bot 资料与底层会话管理页；点击行主体 -> 直接进入 1 对 1 聊天页
            AgentAvatar(bot.id, bot.name, 52.dp, onClick = onAvatarClick)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            bot.name,
                            style = VibeLinkType.BodyStrong,
                            color = c.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 135.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        // 职位头衔徽章（严格单行不折行）
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(bot.lifecycle.badgeColorHex).copy(alpha = 0.14f))
                                .padding(horizontal = 6.dp, vertical = 1.5.dp),
                        ) {
                            Text(
                                bot.jobTitle,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(bot.lifecycle.badgeColorHex),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${bot.lifecycle.label.take(2)} · ${agentDisplayName(bot.engine)}",
                            style = VibeLinkType.Hint,
                            color = c.ink3,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (bot.lastAt > 0) conversationTime(bot.lastAt, System.currentTimeMillis()) else "",
                        style = VibeLinkType.Hint,
                        color = c.ink3,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Spacer(Modifier.height(3.dp))
                if (botDraft.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "[草稿] ",
                            style = VibeLinkType.Caption,
                            color = c.bad,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            botDraft.replace("\n", " "),
                            style = VibeLinkType.Caption,
                            color = c.ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        bot.lastText.ifBlank { bot.rolePrompt },
                        style = VibeLinkType.Caption,
                        color = c.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
            DropdownMenuItem(
                text = { Text("💬 发消息（进入单聊）") },
                onClick = {
                    onDismissMenu()
                    onClick()
                },
            )
            DropdownMenuItem(
                text = { Text("👤 Bot 工位与底层会话管理") },
                onClick = onOpenWorkstation,
            )
            DropdownMenuItem(
                text = { Text("📷 自定义头像 / 修改配方") },
                onClick = onEditBot,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(
    contact: AgentContact,
    running: Boolean,
    menuOpen: Boolean,
    onClick: () -> Unit,
    onAvatar: () -> Unit,
    onLongClick: () -> Unit,
    onDismissMenu: () -> Unit,
    onTogglePin: () -> Unit,
    onMarkRead: () -> Unit,
    onToggleMute: () -> Unit,
) {
    val c = VibeLinkTheme.colors
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentAvatar(contact.id, contact.name, 52.dp, onClick = onAvatar)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (contact.pinned) {
                            Icon(VibeLinkIcons.Pin, "置顶", Modifier.size(13.dp), tint = c.ink3)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            contact.name,
                            style = VibeLinkType.BodyStrong,
                            color = c.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (contact.kind == AgentKind.Phone) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(c.accent.copy(alpha = 0.14f))
                                    .padding(horizontal = 6.dp, vertical = 1.5.dp),
                            ) {
                                Text(
                                    "首席秘书 · 本机",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = c.accent,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                        if (contact.muted) {
                            Spacer(Modifier.width(4.dp))
                            Icon(VibeLinkIcons.Mute, "免打扰", Modifier.size(13.dp), tint = c.ink3)
                        }
                        if (running) {
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.size(7.dp).clip(VibeLinkShapes.Pill).background(c.ok))
                        }
                        if (contact.kind == AgentKind.Desktop) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (contact.online) "底层引擎" else "离线",
                                style = VibeLinkType.Hint,
                                color = if (contact.online) c.ink3 else c.bad,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    if (contact.unread > 0) {
                        Box(
                            Modifier.defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                                .clip(CircleShape)
                                .background(c.bad)
                                .padding(horizontal = 5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (contact.unread > 99) "99+" else contact.unread.toString(),
                                color = c.onAccent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        if (contact.lastAt > 0) conversationTime(contact.lastAt, System.currentTimeMillis()) else "",
                        style = VibeLinkType.Hint,
                        color = c.ink3,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        contact.lastText,
                        Modifier.weight(1f),
                        style = VibeLinkType.Caption,
                        color = c.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (contact.kind == AgentKind.Desktop) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${contact.deviceCount} 台 · ${contact.sessionCount} 个会话",
                            style = VibeLinkType.Hint,
                            color = c.ink3,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
        DropdownMenu(menuOpen, onDismissMenu) {
            DropdownMenuItem(
                text = { Text(if (contact.pinned) "取消置顶" else "置顶") },
                onClick = onTogglePin,
            )
            DropdownMenuItem(
                text = { Text("标为已读") },
                onClick = onMarkRead,
                enabled = contact.unread > 0,
            )
            DropdownMenuItem(
                text = { Text(if (contact.muted) "取消免打扰" else "免打扰") },
                onClick = onToggleMute,
            )
        }
    }
}

@Composable
private fun BottomBar(
    updateAvailable: Boolean,
    onMessages: () -> Unit,
    onClash: () -> Unit,
    onMine: () -> Unit,
) {
    val c = VibeLinkTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .navigationBarsPadding()
            .height(56.dp),
    ) {
        BottomTab(VibeLinkIcons.Message, "会话", true, false, onMessages, Modifier.weight(1f))
        if (!com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION) {
            BottomTab(VibeLinkIcons.Shield, "网络", false, false, onClash, Modifier.weight(1f))
        }
        BottomTab(VibeLinkIcons.Gear, "我的", false, updateAvailable, onMine, Modifier.weight(1f))
    }
}

@Composable
private fun BottomTab(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    badge: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = VibeLinkTheme.colors
    val tint = if (selected) c.accent else c.ink3
    Box(modifier.fillMaxSize().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box {
                Icon(icon, label, Modifier.size(22.dp), tint = tint)
                if (badge) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(VibeLinkShapes.Pill)
                            .background(c.bad)
                            .align(Alignment.TopEnd),
                    )
                }
            }
            Text(label, style = VibeLinkType.Hint, color = tint)
        }
    }
}

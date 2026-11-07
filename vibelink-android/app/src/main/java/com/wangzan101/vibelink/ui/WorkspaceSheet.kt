package com.wangzan101.vibelink.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.vibelink.remote.RemoteApi
import com.wangzan101.vibelink.ui.theme.VibeLinkTheme
import com.wangzan101.vibelink.ui.theme.VibeLinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Cindy 风格的远程电脑工作区抽屉：
 *  Tab 0 -> 📁 远程工作区文件树（支持浏览目录、预览代码/图片、下载文件到手机、上传文件到当前目录、切换 cwd、插入 @file）
 *  Tab 1 -> 🔀 Git 变更与 Diff 审查（支持查看 git status 修改文件列表与红绿高亮 Unified Diff）
 */
@Composable
fun WorkspaceInspectorDialog(
    hub: String,
    token: String,
    device: String,
    initialCwd: String,
    initialTab: Int = 0,
    onDismiss: () -> Unit,
    onSelectCwd: (String) -> Unit,
    onInsertFileMention: (String) -> Unit,
    onRequestUploadToDir: (String) -> Unit,
) {
    val c = VibeLinkTheme.colors
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(initialTab) }
    var currentCwd by remember { mutableStateOf(initialCwd.ifBlank { "D:\\AI\\VibeLink" }) }
    var parentCwd by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<RemoteApi.WorkspaceEntry>>(emptyList()) }
    var gitInfo by remember { mutableStateOf<RemoteApi.WorkspaceGit?>(null) }
    var worktrees by remember { mutableStateOf<List<RemoteApi.WorktreeItem>>(emptyList()) }
    var tasks by remember { mutableStateOf<List<RemoteApi.ScheduledTask>>(emptyList()) }
    var pluginsInfo by remember { mutableStateOf<RemoteApi.PluginsInfo?>(null) }
    var terminalData by remember { mutableStateOf<RemoteApi.TerminalListResult?>(null) }
    var terminalInput by remember { mutableStateOf("") }
    var approvalsInfo by remember { mutableStateOf<RemoteApi.ApprovalsInfo?>(null) }
    var newTaskName by remember { mutableStateOf("") }
    var newTaskPrompt by remember { mutableStateOf("") }
    var newTaskAgent by remember { mutableStateOf("codex") }
    var newTaskCron by remember { mutableStateOf("每天 09:00") }
    var newMcpName by remember { mutableStateOf("") }
    var newMcpUrl by remember { mutableStateOf("") }
    var newMcpDesc by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var viewingFilePath by remember { mutableStateOf<String?>(null) }

    fun loadTree(targetDir: String) {
        scope.launch {
            loading = true
            errorMsg = null
            runCatching {
                withContext(Dispatchers.IO) {
                    RemoteApi.workspaceTree(hub, token, device, targetDir, depth = 1)
                }
            }.onSuccess { tree ->
                currentCwd = tree.cwd
                parentCwd = tree.parent
                entries = tree.entries
                loading = false
            }.onFailure { e ->
                errorMsg = e.message ?: "读取远端目录失败"
                loading = false
            }
        }
    }

    fun loadGit(targetDir: String) {
        scope.launch {
            loading = true
            errorMsg = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val g = RemoteApi.workspaceGit(hub, token, device, targetDir)
                    val wts = runCatching { RemoteApi.worktreeList(hub, token, device, targetDir) }.getOrDefault(emptyList())
                    g to wts
                }
            }.onSuccess { (git, wts) ->
                gitInfo = git
                worktrees = wts
                loading = false
            }.onFailure { e ->
                errorMsg = e.message ?: "读取 Git 状态失败"
                loading = false
            }
        }
    }

    fun loadSchedules() {
        scope.launch {
            loading = true
            errorMsg = null
            runCatching {
                withContext(Dispatchers.IO) { RemoteApi.schedulerList(hub, token, device) }
            }.onSuccess { list ->
                tasks = list
                loading = false
            }.onFailure { e ->
                errorMsg = e.message ?: "读取定时任务失败"
                loading = false
            }
        }
    }

    fun loadPlugins() {
        scope.launch {
            loading = true
            errorMsg = null
            runCatching {
                withContext(Dispatchers.IO) { RemoteApi.pluginsList(hub, token, device) }
            }.onSuccess { info ->
                pluginsInfo = info
                loading = false
            }.onFailure { e ->
                errorMsg = e.message ?: "读取插件列表失败"
                loading = false
            }
        }
    }

    fun loadTerminal() {
        scope.launch {
            loading = true
            errorMsg = null
            runCatching {
                withContext(Dispatchers.IO) { RemoteApi.terminalList(hub, token, device) }
            }.onSuccess { res ->
                terminalData = res
                loading = false
            }.onFailure { e ->
                errorMsg = e.message ?: "读取交互终端失败"
                loading = false
            }
        }
    }

    fun loadApprovals() {
        scope.launch {
            loading = true
            errorMsg = null
            runCatching {
                withContext(Dispatchers.IO) { RemoteApi.approvalsList(hub, token) }
            }.onSuccess { info ->
                approvalsInfo = info
                loading = false
            }.onFailure { e ->
                errorMsg = e.message ?: "读取审批列表失败"
                loading = false
            }
        }
    }

    LaunchedEffect(tab, currentCwd) {
        when (tab) {
            0 -> loadTree(currentCwd)
            1 -> loadGit(currentCwd)
            2 -> loadSchedules()
            3 -> loadPlugins()
            4 -> loadTerminal()
            5 -> loadApprovals()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 36.dp),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = c.bg,
        ) {
            Column(Modifier.fillMaxSize()) {
                // 顶部标题与 Tab 切换
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(c.surface)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "远程工作区 ($device)",
                            style = VibeLinkType.Title,
                            color = c.ink,
                        )
                        Text(
                            currentCwd,
                            style = VibeLinkType.Mono,
                            color = c.ink3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = {
                            when (tab) {
                                0 -> loadTree(currentCwd)
                                1 -> loadGit(currentCwd)
                                2 -> loadSchedules()
                                3 -> loadPlugins()
                                4 -> loadTerminal()
                                5 -> loadApprovals()
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新", tint = c.ink2)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = c.ink2)
                    }
                }

                // Tab 胶囊栏 + 快捷操作
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(c.surface)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(
                        0 to "📁 文件树",
                        1 to "🔀 Git / Worktree",
                        2 to "⏰ 自动任务",
                        3 to "🔌 插件 & MCP",
                        4 to ">_ 交互终端",
                        5 to "🛡️ 安全审批",
                    ).forEach { (idx, label) ->
                        val active = tab == idx
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) c.accent else c.surface)
                                .border(1.dp, if (active) c.accent else c.edge, RoundedCornerShape(10.dp))
                                .clickable { tab = idx }
                                .padding(horizontal = 11.dp, vertical = 6.dp),
                        ) {
                            Text(
                                label,
                                style = VibeLinkType.Caption,
                                color = if (active) c.onAccent else c.ink,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.surface)
                            .border(1.dp, c.edge, RoundedCornerShape(8.dp))
                            .clickable { onRequestUploadToDir(currentCwd) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text("📤 传文件", style = VibeLinkType.Hint, color = c.accent, fontWeight = FontWeight.Medium)
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.accent.copy(alpha = 0.14f))
                            .border(1.dp, c.accent, RoundedCornerShape(8.dp))
                            .clickable {
                                onSelectCwd(currentCwd)
                                onDismiss()
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text("设为会话目录", style = VibeLinkType.Hint, color = c.accent, fontWeight = FontWeight.SemiBold)
                    }
                }

                HorizontalDivider(color = c.edge, thickness = 0.5.dp)

                if (loading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = c.accent,
                        trackColor = Color.Transparent,
                    )
                }

                errorMsg?.let { err ->
                    Text(
                        err,
                        style = VibeLinkType.Hint,
                        color = c.bad,
                        modifier = Modifier.padding(14.dp),
                    )
                }

                if (tab == 0) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (parentCwd.isNotBlank() && parentCwd != currentCwd) {
                            item(key = "__parent__") {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(c.surface)
                                        .clickable { loadTree(parentCwd) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text("⬆️", fontSize = 15.sp)
                                    Text(".. (返回上级目录)", style = VibeLinkType.Body, color = c.accent)
                                }
                            }
                        }
                        items(entries, key = { it.path }) { item ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(c.surface)
                                    .border(0.5.dp, c.edge, RoundedCornerShape(10.dp))
                                    .clickable {
                                        if (item.isDir) {
                                            loadTree(item.path)
                                        } else {
                                            viewingFilePath = item.path
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(if (item.isDir) "📁" else "📄", fontSize = 16.sp)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.name,
                                        style = VibeLinkType.Body,
                                        color = c.ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!item.isDir) {
                                        Text(
                                            formatByteSize(item.size),
                                            style = VibeLinkType.Hint,
                                            color = c.ink3,
                                        )
                                    }
                                }
                                if (!item.isDir) {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(c.surface)
                                            .clickable {
                                                onInsertFileMention(item.path)
                                                onDismiss()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Text("@引用", style = VibeLinkType.Hint, color = c.accent)
                                    }
                                }
                            }
                        }
                    }
                } else if (tab == 1) {
                    val git = gitInfo
                    if (git == null && !loading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("暂无 Git 仓库信息", style = VibeLinkType.Body, color = c.ink3)
                        }
                    } else if (git != null) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            item {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(c.surface)
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        "分支: ${git.branch.ifBlank { "HEAD" }}",
                                        style = VibeLinkType.Label,
                                        color = c.accent,
                                    )
                                    Text(
                                        "未提交变更: ${git.files.size} 个文件",
                                        style = VibeLinkType.Hint,
                                        color = c.ink2,
                                    )
                                }
                            }
                            item {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(c.surface)
                                        .border(0.5.dp, c.edge, RoundedCornerShape(10.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            "🌿 Per-Bot Git Worktree 隔离池 (${worktrees.size})",
                                            style = VibeLinkType.Label,
                                            color = c.ink,
                                        )
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(c.accent.copy(alpha = 0.14f))
                                                .clickable {
                                                    scope.launch {
                                                        runCatching {
                                                            withContext(Dispatchers.IO) {
                                                                RemoteApi.worktreeAction(
                                                                    hub,
                                                                    token,
                                                                    device,
                                                                    JSONObject()
                                                                        .put("action", "ensure")
                                                                        .put("botId", "bot-dev")
                                                                        .put("cwd", currentCwd),
                                                                )
                                                                RemoteApi.worktreeList(hub, token, device, currentCwd)
                                                            }
                                                        }.onSuccess { worktrees = it }
                                                    }
                                                }
                                                .padding(horizontal = 10.dp, vertical = 5.dp),
                                        ) {
                                            Text("+ 初始化主程 Worktree", style = VibeLinkType.Hint, color = c.accent, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                    if (worktrees.isEmpty()) {
                                        Text(
                                            "当项目型 Bot 开启「独立 Git Worktree」时，会自动在 .vibelink-worktrees/<bot-id> 下建立独立分支与工作目录，避免多 Bot 并发冲突。",
                                            style = VibeLinkType.Hint,
                                            color = c.ink3,
                                        )
                                    } else {
                                        worktrees.forEach { wt ->
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(c.bg)
                                                    .clickable {
                                                        onSelectCwd(wt.worktreePath)
                                                        currentCwd = wt.worktreePath
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(
                                                        "🌿 ${wt.branch} (${wt.headCommit.ifBlank { "HEAD" }})",
                                                        style = VibeLinkType.BodyStrong,
                                                        color = c.ink,
                                                    )
                                                    Text(
                                                        wt.worktreePath,
                                                        style = VibeLinkType.Mono,
                                                        color = c.ink3,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                                Text(
                                                    "变更 ${wt.dirtyFiles}",
                                                    style = VibeLinkType.Hint,
                                                    color = if (wt.dirtyFiles > 0) Color(0xFFD97706) else c.ok,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            items(git.files, key = { it.path }) { gf ->
                                val badgeColor = when {
                                    gf.status.contains("M") -> Color(0xFFD97706)
                                    gf.status.contains("A") || gf.status.contains("?") -> c.ok
                                    gf.status.contains("D") -> c.bad
                                    else -> c.ink2
                                }
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(c.surface)
                                        .border(0.5.dp, c.edge, RoundedCornerShape(8.dp))
                                        .clickable { viewingFilePath = gf.absPath }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(badgeColor.copy(alpha = 0.16f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text(gf.status, style = VibeLinkType.Mono, color = badgeColor, fontWeight = FontWeight.Bold)
                                    }
                                    Text(
                                        gf.path,
                                        style = VibeLinkType.Mono,
                                        color = c.ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text("查看", style = VibeLinkType.Hint, color = c.accent)
                                }
                            }
                            if (git.diff.isNotBlank()) {
                                item {
                                    Text(
                                        "Unified Diff 预览",
                                        style = VibeLinkType.Label,
                                        color = c.ink2,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                    DiffCodeBox(diff = git.diff)
                                }
                            }
                        }
                    }
                } else if (tab == 2) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(c.surface)
                                    .border(0.5.dp, c.edge, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("新建定时自动任务 (支持标准 5 段 Cron / 每日定点)", style = VibeLinkType.Label, color = c.ink)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("codex" to "Codex 官方 CLI").forEach { (id, label) ->
                                        val sel = newTaskAgent == id
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (sel) c.accent else c.bg)
                                                .clickable { newTaskAgent = id }
                                                .padding(horizontal = 10.dp, vertical = 5.dp),
                                        ) {
                                            Text(label, style = VibeLinkType.Hint, color = if (sel) c.onAccent else c.ink2)
                                        }
                                    }
                                }
                                // 快捷 Cron 预设胶囊
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    listOf(
                                        "每天 09:00",
                                        "工作日 09:30",
                                        "*/30 * * * *",
                                        "0 */6 * * *",
                                        "每 6 小时",
                                    ).forEach { preset ->
                                        val sel = newTaskCron == preset
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (sel) c.accent.copy(alpha = 0.16f) else c.bg)
                                                .border(0.5.dp, if (sel) c.accent else c.edge, RoundedCornerShape(8.dp))
                                                .clickable { newTaskCron = preset }
                                                .padding(horizontal = 9.dp, vertical = 5.dp),
                                        ) {
                                            Text(
                                                preset,
                                                style = VibeLinkType.Hint,
                                                color = if (sel) c.accent else c.ink2,
                                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                            )
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = newTaskName,
                                        onValueChange = { newTaskName = it },
                                        label = { Text("任务名称") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1.2f),
                                    )
                                    OutlinedTextField(
                                        value = newTaskCron,
                                        onValueChange = { newTaskCron = it },
                                        label = { Text("Cron / 时间") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                OutlinedTextField(
                                    value = newTaskPrompt,
                                    onValueChange = { newTaskPrompt = it },
                                    label = { Text("执行指令 (Prompt)") },
                                    maxLines = 3,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(c.accent)
                                        .clickable {
                                            if (newTaskPrompt.isBlank()) return@clickable
                                            scope.launch {
                                                runCatching {
                                                    withContext(Dispatchers.IO) {
                                                        RemoteApi.schedulerAction(
                                                            hub,
                                                            token,
                                                            device,
                                                            JSONObject()
                                                                .put("action", "create")
                                                                .put("name", newTaskName.ifBlank { "自动巡检任务" })
                                                                .put("agent", newTaskAgent)
                                                                .put("cwd", currentCwd)
                                                                .put("prompt", newTaskPrompt)
                                                                .put("cron", newTaskCron.ifBlank { "每天 09:00" }),
                                                        )
                                                    }
                                                }.onSuccess {
                                                    tasks = it
                                                    newTaskName = ""
                                                    newTaskPrompt = ""
                                                }
                                            }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Text("+ 添加定时任务", style = VibeLinkType.Caption, color = c.onAccent, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        items(tasks, key = { it.id }) { t ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(c.surface)
                                    .border(0.5.dp, c.edge, RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(t.name, style = VibeLinkType.BodyStrong, color = c.ink, modifier = Modifier.weight(1f))
                                    Text(
                                        if (t.enabled) "● 已启用" else "○ 已暂停",
                                        style = VibeLinkType.Hint,
                                        color = if (t.enabled) c.ok else c.ink3,
                                    )
                                }
                                Text("${t.agent} · ${t.cron} · 状态: ${t.lastStatus}", style = VibeLinkType.Hint, color = c.ink3)
                                Text(t.prompt, style = VibeLinkType.Caption, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(c.accent.copy(alpha = 0.15f))
                                            .clickable {
                                                scope.launch {
                                                    runCatching {
                                                        withContext(Dispatchers.IO) {
                                                            RemoteApi.schedulerAction(hub, token, device, JSONObject().put("action", "run").put("id", t.id))
                                                        }
                                                    }.onSuccess { tasks = it }
                                                }
                                            }
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                    ) {
                                        Text("▶ 立即执行", style = VibeLinkType.Hint, color = c.accent, fontWeight = FontWeight.SemiBold)
                                    }
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(c.bg)
                                            .clickable {
                                                scope.launch {
                                                    runCatching {
                                                        withContext(Dispatchers.IO) {
                                                            RemoteApi.schedulerAction(hub, token, device, JSONObject().put("action", "toggle").put("id", t.id))
                                                        }
                                                    }.onSuccess { tasks = it }
                                                }
                                            }
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                    ) {
                                        Text(if (t.enabled) "暂停" else "启用", style = VibeLinkType.Hint, color = c.ink2)
                                    }
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(c.bg)
                                            .clickable {
                                                scope.launch {
                                                    runCatching {
                                                        withContext(Dispatchers.IO) {
                                                            RemoteApi.schedulerAction(hub, token, device, JSONObject().put("action", "delete").put("id", t.id))
                                                        }
                                                    }.onSuccess { tasks = it }
                                                }
                                            }
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                    ) {
                                        Text("删除", style = VibeLinkType.Hint, color = c.bad)
                                    }
                                }
                            }
                        }
                    }
                } else if (tab == 3) {
                    val info = pluginsInfo
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // 新增远程 HTTPS MCP 服务卡片（Phase P3）
                        item {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(c.surface)
                                    .border(0.5.dp, c.edge, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("🌐 注册远程 HTTPS / Stdio MCP 服务", style = VibeLinkType.Label, color = c.ink)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = newMcpName,
                                        onValueChange = { newMcpName = it },
                                        label = { Text("MCP 名称") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = newMcpDesc,
                                        onValueChange = { newMcpDesc = it },
                                        label = { Text("用途说明") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1.2f),
                                    )
                                }
                                OutlinedTextField(
                                    value = newMcpUrl,
                                    onValueChange = { newMcpUrl = it },
                                    label = { Text("远程 HTTPS Endpoint 或本地命令") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(c.accent)
                                        .clickable {
                                            if (newMcpName.isBlank() || newMcpUrl.isBlank()) return@clickable
                                            scope.launch {
                                                runCatching {
                                                    withContext(Dispatchers.IO) {
                                                        RemoteApi.pluginsAction(
                                                            hub,
                                                            token,
                                                            device,
                                                            JSONObject()
                                                                .put("action", "add_mcp")
                                                                .put("name", newMcpName.trim())
                                                                .put("url", newMcpUrl.trim())
                                                                .put("description", newMcpDesc.ifBlank { "自定义远程 MCP 服务" }),
                                                        )
                                                    }
                                                }.onSuccess {
                                                    pluginsInfo = it
                                                    newMcpName = ""
                                                    newMcpUrl = ""
                                                    newMcpDesc = ""
                                                }
                                            }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Text("+ 挂载 MCP 服务", style = VibeLinkType.Caption, color = c.onAccent, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        if (info != null) {
                            if (info.mcpServers.isNotEmpty()) {
                                item {
                                    Text("已配置 MCP 服务 (${info.mcpServers.size})", style = VibeLinkType.Label, color = c.ink2)
                                }
                                items(info.mcpServers, key = { it.id }) { mcp ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(c.surface)
                                            .border(0.5.dp, c.edge, RoundedCornerShape(10.dp))
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text("🔌 ${mcp.name}", style = VibeLinkType.BodyStrong, color = c.ink)
                                                Text(mcp.transport.uppercase(), style = VibeLinkType.Hint, color = c.accent)
                                            }
                                            Text(mcp.url, style = VibeLinkType.Mono, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (mcp.description.isNotBlank()) {
                                                Text(mcp.description, style = VibeLinkType.Hint, color = c.ink2, maxLines = 1)
                                            }
                                        }
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    runCatching {
                                                        withContext(Dispatchers.IO) {
                                                            RemoteApi.pluginsAction(
                                                                hub,
                                                                token,
                                                                device,
                                                                JSONObject().put("action", "toggle_mcp").put("id", mcp.id),
                                                            )
                                                        }
                                                    }.onSuccess { pluginsInfo = it }
                                                }
                                            },
                                        ) {
                                            Text(
                                                if (mcp.enabled) "● 已启用" else "○ 已停用",
                                                color = if (mcp.enabled) c.ok else c.ink3,
                                                fontSize = 12.sp,
                                            )
                                        }
                                    }
                                }
                            }
                            item {
                                Text("远端电脑已接入 Agent 引擎 (${info.engines.size})", style = VibeLinkType.Label, color = c.ink2)
                            }
                            items(info.engines, key = { it.id }) { eng ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(c.surface)
                                        .border(0.5.dp, c.edge, RoundedCornerShape(10.dp))
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(eng.name, style = VibeLinkType.BodyStrong, color = c.ink)
                                        Text(eng.bin, style = VibeLinkType.Mono, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(if (eng.ready) "● 就绪" else "离线", style = VibeLinkType.Hint, color = if (eng.ready) c.ok else c.bad)
                                }
                            }
                            item {
                                Text("已安装 Skill 与插件 (${info.skills.size})", style = VibeLinkType.Label, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
                            }
                            items(info.skills, key = { it.path }) { sk ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(c.surface)
                                        .border(0.5.dp, c.edge, RoundedCornerShape(10.dp))
                                        .clickable { viewingFilePath = "${sk.path}\\SKILL.md" }
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("🧩 ${sk.name}", style = VibeLinkType.BodyStrong, color = c.ink, modifier = Modifier.weight(1f))
                                        Text(sk.scope, style = VibeLinkType.Hint, color = c.accent)
                                    }
                                    Text(sk.description, style = VibeLinkType.Hint, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                } else if (tab == 4) {
                    // Tab 4 -> >_ 交互终端与 computer.command 活动流（对标 Rakazo P1）
                    val term = terminalData
                    val mainSess = term?.sessions?.firstOrNull()
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                ">_ 持久化终端 (${mainSess?.shell ?: "pwsh"}) · ${mainSess?.cwd ?: currentCwd}",
                                style = VibeLinkType.Label,
                                color = c.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                RemoteApi.terminalAction(
                                                    hub,
                                                    token,
                                                    device,
                                                    JSONObject().put("action", "clear").put("sessionId", mainSess?.id ?: "term-main"),
                                                )
                                                RemoteApi.terminalList(hub, token, device)
                                            }
                                        }.onSuccess { terminalData = it }
                                    }
                                },
                            ) {
                                Text("清屏", color = c.ink3, fontSize = 12.sp)
                            }
                        }

                        // 黑底终端输出区
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0B1220))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val lines = mainSess?.lines.orEmpty()
                            if (lines.isEmpty()) {
                                item {
                                    Text(
                                        "终端就绪，支持 cd 持久切换目录、设置环境变量与执行构建测试命令…",
                                        color = Color(0xFF64748B),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                    )
                                }
                            } else {
                                items(lines, key = { it.seq }) { ln ->
                                    val color = when (ln.stream) {
                                        "Prompt" -> Color(0xFF38BDF8)
                                        "stderr" -> Color(0xFFF87171)
                                        "system" -> Color(0xFFA78BFA)
                                        else -> Color(0xFFE2E8F0)
                                    }
                                    Text(
                                        text = ln.text,
                                        color = color,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                    )
                                }
                            }
                        }

                        // 快捷命令胶囊栏
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf(
                                "git status -s",
                                "git branch -a",
                                "cargo --version",
                                "dir",
                            ).forEach { quickCmd ->
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(c.surface)
                                        .border(0.5.dp, c.edge, RoundedCornerShape(8.dp))
                                        .clickable {
                                            scope.launch {
                                                loading = true
                                                runCatching {
                                                    withContext(Dispatchers.IO) {
                                                        RemoteApi.terminalAction(
                                                            hub,
                                                            token,
                                                            device,
                                                            JSONObject()
                                                                .put("action", "input")
                                                                .put("sessionId", mainSess?.id ?: "term-main")
                                                                .put("cwd", currentCwd)
                                                                .put("command", quickCmd),
                                                        )
                                                        RemoteApi.terminalList(hub, token, device)
                                                    }
                                                }.onSuccess {
                                                    terminalData = it
                                                    loading = false
                                                }.onFailure { loading = false }
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                ) {
                                    Text(quickCmd, style = VibeLinkType.Mono, color = c.accent, fontSize = 11.sp)
                                }
                            }
                        }

                        // 终端命令输入栏
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = terminalInput,
                                onValueChange = { terminalInput = it },
                                placeholder = { Text("输入 PowerShell / Shell 命令…", fontSize = 13.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = {
                                    val cmd = terminalInput.trim()
                                    if (cmd.isEmpty()) return@Button
                                    terminalInput = ""
                                    scope.launch {
                                        loading = true
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                RemoteApi.terminalAction(
                                                    hub,
                                                    token,
                                                    device,
                                                    JSONObject()
                                                        .put("action", "input")
                                                        .put("sessionId", mainSess?.id ?: "term-main")
                                                        .put("cwd", currentCwd)
                                                        .put("command", cmd),
                                                )
                                                RemoteApi.terminalList(hub, token, device)
                                            }
                                        }.onSuccess {
                                            terminalData = it
                                            loading = false
                                        }.onFailure { loading = false }
                                    }
                                },
                            ) {
                                Text("执行")
                            }
                        }
                    }
                } else {
                    // Tab 5 -> 🛡️ 三级安全审批门禁 (Ask / Auto / Never，对标 Rakazo P3)
                    val app = approvalsInfo
                    val curMode = app?.mode ?: "ask"
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(c.surface)
                                    .border(0.5.dp, c.edge, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("🛡️ 全局工具与子 Bot 审批策略 (Three-Tier Approval)", style = VibeLinkType.Label, color = c.ink)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(
                                        "ask" to "🔒 Ask (高危需确认)",
                                        "auto" to "⚡ Auto (安全放行)",
                                        "never" to "🚀 Never (全自动)",
                                    ).forEach { (m, label) ->
                                        val active = curMode.equals(m, ignoreCase = true)
                                        Box(
                                            Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (active) c.accent else c.bg)
                                                .clickable {
                                                    scope.launch {
                                                        runCatching {
                                                            withContext(Dispatchers.IO) {
                                                                RemoteApi.approvalsPolicyUpdate(hub, token, m)
                                                            }
                                                        }.onSuccess { approvalsInfo = it }
                                                    }
                                                }
                                                .padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                label,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (active) c.onAccent else c.ink2,
                                            )
                                        }
                                    }
                                }
                                Text(
                                    "说明：在 Ask 与 Auto 模式下，管理 Bot 调用 set_child_instructions / set_child_bot_model 修改下属 Bot 指令或模型时，均会强制弹出人工审批卡片。",
                                    style = VibeLinkType.Hint,
                                    color = c.ink3,
                                )
                            }
                        }

                        val reqs = app?.requests.orEmpty()
                        if (reqs.isEmpty()) {
                            item {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("暂无待处理的风险操作审批记录", style = VibeLinkType.Body, color = c.ink3)
                                }
                            }
                        } else {
                            items(reqs, key = { it.id }) { req ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(c.surface)
                                        .border(0.5.dp, c.edge, RoundedCornerShape(10.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "🤖 ${req.botName.ifBlank { req.botId }} 请求调用 ${req.toolName}",
                                            style = VibeLinkType.BodyStrong,
                                            color = c.ink,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            req.status.uppercase(),
                                            style = VibeLinkType.Hint,
                                            color = when (req.status) {
                                                "approved" -> c.ok
                                                "denied" -> c.bad
                                                else -> Color(0xFFD97706)
                                            },
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    Text(req.commandOrAction, style = VibeLinkType.Mono, color = c.ink2, maxLines = 3)
                                    if (req.reason.isNotBlank()) {
                                        Text("原因: ${req.reason}", style = VibeLinkType.Hint, color = c.ink3)
                                    }
                                    if (req.status == "pending") {
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Button(
                                                onClick = {
                                                    scope.launch {
                                                        runCatching {
                                                            withContext(Dispatchers.IO) {
                                                                RemoteApi.approvalDecide(hub, token, req.id, true)
                                                                RemoteApi.approvalsList(hub, token)
                                                            }
                                                        }.onSuccess { approvalsInfo = it }
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = c.ok),
                                            ) {
                                                Text("✓ 批准执行", fontSize = 12.sp)
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    scope.launch {
                                                        runCatching {
                                                            withContext(Dispatchers.IO) {
                                                                RemoteApi.approvalDecide(hub, token, req.id, false)
                                                                RemoteApi.approvalsList(hub, token)
                                                            }
                                                        }.onSuccess { approvalsInfo = it }
                                                    }
                                                },
                                            ) {
                                                Text("✕ 拒绝拦截", color = c.bad, fontSize = 12.sp)
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
    }

    viewingFilePath?.let { fpath ->
        RemoteFileViewerDialog(
            hub = hub,
            token = token,
            device = device,
            filePath = fpath,
            onDismiss = { viewingFilePath = null },
            onInsertMention = {
                onInsertFileMention(fpath)
                viewingFilePath = null
                onDismiss()
            },
        )
    }
}

@Composable
fun DiffCodeBox(diff: String, maxLines: Int = 180) {
    val c = VibeLinkTheme.colors
    val lines = remember(diff) { diff.lines().take(maxLines) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF14171F))
            .border(0.5.dp, c.edge, RoundedCornerShape(10.dp))
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        lines.forEach { line ->
            val bg = when {
                line.startsWith("+") && !line.startsWith("+++") -> Color(0x3322C55E)
                line.startsWith("-") && !line.startsWith("---") -> Color(0x33EF4444)
                line.startsWith("@@") -> Color(0x333B82F6)
                else -> Color.Transparent
            }
            val fg = when {
                line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF86EFAC)
                line.startsWith("-") && !line.startsWith("---") -> Color(0xFFFCA5A5)
                line.startsWith("@@") -> Color(0xFF93C5FD)
                else -> Color(0xFFE2E8F0)
            }
            Text(
                text = line,
                color = fg,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * 远程电脑文件在线预览与一键下载到手机弹窗
 */
@Composable
fun RemoteFileViewerDialog(
    hub: String,
    token: String,
    device: String,
    filePath: String,
    onDismiss: () -> Unit,
    onInsertMention: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val c = VibeLinkTheme.colors
    val scope = rememberCoroutineScope()
    var fileData by remember { mutableStateOf<RemoteApi.WorkspaceFile?>(null) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(filePath) {
        loading = true
        err = null
        runCatching {
            withContext(Dispatchers.IO) {
                RemoteApi.workspaceFile(hub, token, device, filePath)
            }
        }.onSuccess { f ->
            fileData = f
            loading = false
        }.onFailure { e ->
            err = e.message ?: "读取远端文件失败"
            loading = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 40.dp),
            shape = RoundedCornerShape(16.dp),
            color = c.bg,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(c.surface)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            fileData?.name ?: filePath.substringAfterLast('\\').substringAfterLast('/'),
                            style = VibeLinkType.Title,
                            color = c.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            filePath,
                            style = VibeLinkType.Mono,
                            color = c.ink3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    onInsertMention?.let { insert ->
                        TextButton(onClick = insert) {
                            Text("@引用", color = c.accent)
                        }
                    }
                    fileData?.let { f ->
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val savedPath = withContext(Dispatchers.IO) {
                                        saveRemoteFileToPhone(ctx, f)
                                    }
                                    Toast.makeText(
                                        ctx,
                                        if (savedPath != null) "已保存到手机: $savedPath" else "保存失败",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            },
                        ) {
                            Text("💾 下载到手机", color = c.accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = c.ink2)
                    }
                }

                HorizontalDivider(color = c.edge, thickness = 0.5.dp)

                Box(Modifier.fillMaxSize().padding(12.dp)) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.accent)
                        err != null -> Text(err ?: "", color = c.bad, modifier = Modifier.align(Alignment.Center))
                        fileData != null -> {
                            val f = fileData!!
                            if (f.isText) {
                                Column(
                                    Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF14171F))
                                        .verticalScroll(rememberScrollState())
                                        .horizontalScroll(rememberScrollState())
                                        .padding(12.dp),
                                ) {
                                    Text(
                                        f.text,
                                        color = Color(0xFFE2E8F0),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp,
                                    )
                                }
                            } else if (f.mime.startsWith("image/") && f.base64.isNotBlank()) {
                                val bmp = remember(f.base64) {
                                    runCatching {
                                        val bytes = Base64.decode(f.base64, Base64.DEFAULT)
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                    }.getOrNull()
                                }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = f.name,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Text("图片解码失败，请点击右上角下载到手机查看", color = c.ink2)
                                }
                            } else {
                                Column(
                                    Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text("📦 二进制文件 (${formatByteSize(f.size)})", style = VibeLinkType.Title, color = c.ink)
                                    Text("点击右上角「💾 下载到手机」保存到本机 Download 目录", style = VibeLinkType.Hint, color = c.ink3)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Cindy 风格的图片涂鸦标注弹窗：在发送截图/照片前用红笔圈出重点区域
 */
@Composable
fun ImageMarkupDialog(
    initialBase64: String,
    fileName: String,
    onDismiss: () -> Unit,
    onConfirm: (annotatedBase64: String) -> Unit,
) {
    val c = VibeLinkTheme.colors
    val srcBitmap = remember(initialBase64) {
        runCatching {
            val raw = initialBase64.substringAfter("base64,", initialBase64)
            val bytes = Base64.decode(raw, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    if (srcBitmap == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var canvasWidth by remember { mutableIntStateOf(1) }
    var canvasHeight by remember { mutableIntStateOf(1) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = c.bg,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(c.surface)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("涂鸦标注: $fileName", style = VibeLinkType.Title, color = c.ink, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
                        },
                    ) {
                        Text("撤销", color = c.ink2)
                    }
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = c.ink3)
                    }
                    TextButton(
                        onClick = {
                            val outBmp = srcBitmap.copy(Bitmap.Config.ARGB_8888, true)
                            val androidCanvas = AndroidCanvas(outBmp)
                            val scaleX = outBmp.width.toFloat() / canvasWidth.coerceAtLeast(1)
                            val scaleY = outBmp.height.toFloat() / canvasHeight.coerceAtLeast(1)
                            val paint = AndroidPaint().apply {
                                color = android.graphics.Color.RED
                                style = AndroidPaint.Style.STROKE
                                strokeWidth = 8f * ((scaleX + scaleY) / 2f).coerceAtLeast(1f)
                                isAntiAlias = true
                                strokeCap = AndroidPaint.Cap.ROUND
                                strokeJoin = AndroidPaint.Join.ROUND
                            }
                            for (pts in strokes) {
                                for (i in 0 until pts.size - 1) {
                                    androidCanvas.drawLine(
                                        pts[i].x * scaleX,
                                        pts[i].y * scaleY,
                                        pts[i + 1].x * scaleX,
                                        pts[i + 1].y * scaleY,
                                        paint,
                                    )
                                }
                            }
                            val bos = ByteArrayOutputStream()
                            outBmp.compress(Bitmap.CompressFormat.PNG, 92, bos)
                            val encoded = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                            onConfirm(encoded)
                        },
                    ) {
                        Text("完成标注", color = c.accent, fontWeight = FontWeight.Bold)
                    }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val imgBmp = remember(srcBitmap) { srcBitmap.asImageBitmap() }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(srcBitmap.width.toFloat() / srcBitmap.height.coerceAtLeast(1).toFloat()),
                    ) {
                        Image(
                            bitmap = imgBmp,
                            contentDescription = fileName,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            canvasWidth = size.width
                                            canvasHeight = size.height
                                            currentStroke = listOf(offset)
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            currentStroke = currentStroke + change.position
                                        },
                                        onDragEnd = {
                                            if (currentStroke.size > 1) {
                                                strokes.add(currentStroke)
                                            }
                                            currentStroke = emptyList()
                                        },
                                    )
                                },
                        ) {
                            canvasWidth = size.width.toInt().coerceAtLeast(1)
                            canvasHeight = size.height.toInt().coerceAtLeast(1)
                            val all = strokes + if (currentStroke.size > 1) listOf(currentStroke) else emptyList()
                            all.forEach { pts ->
                                val path = Path().apply {
                                    moveTo(pts.first().x, pts.first().y)
                                    for (i in 1 until pts.size) {
                                        lineTo(pts[i].x, pts[i].y)
                                    }
                                }
                                drawPath(
                                    path = path,
                                    color = Color(0xFFEF4444),
                                    style = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun formatByteSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024f)
    else -> String.format("%.2f MB", bytes / (1024f * 1024f))
}

private fun saveRemoteFileToPhone(ctx: Context, f: RemoteApi.WorkspaceFile): String? = runCatching {
    val rawBytes = if (f.isText) {
        f.text.toByteArray(Charsets.UTF_8)
    } else {
        Base64.decode(f.base64, Base64.DEFAULT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, f.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(rawBytes) }
        "Download/${f.name}"
    } else {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        val out = File(dir, f.name)
        out.writeBytes(rawBytes)
        out.absolutePath
    }
}.getOrNull()

/**
 * 远程电脑桌面实时画面流与触控键鼠控制弹窗（对齐原版 Cindy remote-desktop 能力）
 */
@Composable
fun RemoteDesktopDialog(
    hub: String,
    token: String,
    device: String,
    onDismiss: () -> Unit,
) {
    val c = VibeLinkTheme.colors
    val scope = rememberCoroutineScope()
    var frame by remember { mutableStateOf<RemoteApi.DesktopFrame?>(null) }
    var bitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var lastFrameHash by remember { mutableStateOf("") }
    var leaseActive by remember { mutableStateOf(false) }
    var autoRefresh by remember { mutableStateOf(true) }
    var pointerMode by remember { mutableStateOf("click") } // click, right_click, double_click, move
    var textToSend by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("正在连接远程电脑桌面…") }

    DisposableEffect(device) {
        scope.launch(Dispatchers.IO) {
            runCatching { RemoteApi.desktopLease(hub, token, device, action = "acquire") }
                .onSuccess { leaseActive = it.optBoolean("leaseActive", true) }
        }
        onDispose {
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                runCatching { RemoteApi.desktopLease(hub, token, device, action = "release") }
            }
        }
    }

    suspend fun fetchFrame() {
        runCatching {
            withContext(Dispatchers.IO) {
                val f = RemoteApi.desktopFrame(hub, token, device, width = 1280, lastHash = lastFrameHash)
                val bmp = if (!f.unchanged && f.jpegBase64.isNotBlank()) {
                    val bytes = Base64.decode(f.jpegBase64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                } else null
                f to bmp
            }
        }.onSuccess { (f, bmp) ->
            frame = f
            if (f.frameHash.isNotBlank()) lastFrameHash = f.frameHash
            if (bmp != null) bitmap = bmp
            val leaseTag = if (f.leaseActive || leaseActive) " · 🛡️ 接管保护中" else ""
            val cacheTag = if (f.unchanged) " · 帧未变(0流量)" else ""
            statusText = "${f.width}×${f.height} · 光标 (${f.cursorX}, ${f.cursorY})$leaseTag$cacheTag"
        }.onFailure { e ->
            statusText = "画面同步失败: ${e.message}"
        }
    }

    LaunchedEffect(device, autoRefresh) {
        fetchFrame()
        while (autoRefresh) {
            delay(900)
            fetchFrame()
        }
    }

    fun sendInput(payload: JSONObject) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    RemoteApi.desktopInput(hub, token, device, payload)
                }
            }
            delay(180)
            fetchFrame()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(top = 28.dp),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = Color(0xFF0F172A),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E293B))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("🖥 远程电脑桌面 ($device)", style = VibeLinkType.Title, color = Color.White)
                        Text(statusText, style = VibeLinkType.Hint, color = Color(0xFF94A3B8))
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (autoRefresh) c.accent else Color(0xFF334155))
                            .clickable { autoRefresh = !autoRefresh }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(if (autoRefresh) "● 实时流" else "已暂停", style = VibeLinkType.Hint, color = Color.White)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color.White)
                    }
                }

                // 触控鼠标模式切换栏
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E293B))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "click" to "👆 单击左键",
                        "right_click" to "🖱 右键菜单",
                        "double_click" to "⚡ 双击打开",
                        "move" to "🎯 仅移动光标",
                    ).forEach { (m, label) ->
                        val active = pointerMode == m
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) c.accent else Color(0xFF334155))
                                .clickable { pointerMode = m }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(label, style = VibeLinkType.Hint, color = Color.White)
                        }
                    }
                }

                // 远端电脑屏幕画布（点击位置直接映射为远端电脑坐标）
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = bitmap
                    if (bmp == null) {
                        CircularProgressIndicator(color = c.accent)
                    } else {
                        Image(
                            bitmap = bmp,
                            contentDescription = "远程桌面",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(pointerMode) {
                                    detectTapGestures { offset ->
                                        val nx = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                        val ny = (offset.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                                        sendInput(
                                            JSONObject()
                                                .put("type", pointerMode)
                                                .put("x", nx.toDouble())
                                                .put("y", ny.toDouble()),
                                        )
                                    }
                                },
                        )
                    }
                }

                // 电脑虚拟快捷键栏
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E293B))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "滚轮↑" to JSONObject().put("type", "scroll").put("delta", 360),
                        "滚轮↓" to JSONObject().put("type", "scroll").put("delta", -360),
                        "Enter" to JSONObject().put("type", "key").put("key", "{ENTER}"),
                        "Esc" to JSONObject().put("type", "key").put("key", "{ESC}"),
                        "Tab" to JSONObject().put("type", "key").put("key", "{TAB}"),
                        "退格" to JSONObject().put("type", "key").put("key", "{BACKSPACE}"),
                        "Ctrl+C" to JSONObject().put("type", "key").put("key", "^c"),
                        "Ctrl+V" to JSONObject().put("type", "key").put("key", "^v"),
                        "Ctrl+Z" to JSONObject().put("type", "key").put("key", "^z"),
                        "Alt+Tab" to JSONObject().put("type", "key").put("key", "%{TAB}"),
                        "Win" to JSONObject().put("type", "key").put("key", "^{ESC}"),
                    ).forEach { (label, payload) ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF334155))
                                .clickable { sendInput(payload) }
                                .padding(horizontal = 11.dp, vertical = 6.dp),
                        ) {
                            Text(label, style = VibeLinkType.Caption, color = Color.White)
                        }
                    }
                }

                // 手机输入文字直接粘贴至远端电脑当前焦点窗口
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F172A))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = textToSend,
                        onValueChange = { textToSend = it },
                        placeholder = { Text("输入文字直接粘贴到电脑窗口…", color = Color(0xFF64748B)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(c.accent)
                            .clickable {
                                if (textToSend.isBlank()) return@clickable
                                val txt = textToSend
                                textToSend = ""
                                sendInput(JSONObject().put("type", "text").put("text", txt))
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text("发送文字", style = VibeLinkType.Caption, color = c.onAccent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

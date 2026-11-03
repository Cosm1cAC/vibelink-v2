package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.remote.MessageBubble
import com.wangzan101.mika.remote.RemoteApi
import com.wangzan101.mika.remote.RemoteStore
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.GlassBar
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 电脑端 agent 的聊天页：进来先挑这个 agent 最近的一个会话，顶部可以切会话。
 * 头像点一下进 agent 资料页。消息渲染复用 RemoteScreen 的气泡。
 */
@Composable
fun AgentChatScreen(
    agentId: String,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    initialSessionId: String? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = MikaTheme.colors
    val hub = remember { RemoteStore.hub(ctx) }
    val token = remember { RemoteStore.token(ctx) }

    var sessions by remember { mutableStateOf<List<RemoteApi.Session>>(emptyList()) }
    var session by remember { mutableStateOf<RemoteApi.Session?>(null) }
    var latest by remember { mutableStateOf<List<RemoteApi.Message>>(emptyList()) }
    var older by remember { mutableStateOf<List<RemoteApi.Message>>(emptyList()) }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var loadingEarlier by remember { mutableStateOf(false) }
    val messages: List<RemoteApi.Message> = remember(latest, older) {
        (older + latest).distinctBy { m -> m.id.ifBlank { "local:${m.at}:${m.text.hashCode()}" } }
    }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var sentAt by remember { mutableStateOf(0L) }
    var stick by remember { mutableStateOf(true) }
    LaunchedEffect(session?.id) {
        older = emptyList()
        nextCursor = null
        stick = true
    }
    val listState = rememberLazyListState()

    val title = agentDisplayName(agentId)

    fun fail(e: Throwable) {
        error = e.message ?: e.toString()
    }

    suspend fun loadMessages(s: RemoteApi.Session) {
        runCatching {
            withContext(Dispatchers.IO) { RemoteApi.messagesPage(hub, token, s.device, s.agent, s.id) }
        }.onSuccess { page ->
            latest = page.messages
            // 已经翻过更早的页时不要退回第一页的 cursor，否则会重复拉同一段
            if (older.isEmpty()) nextCursor = page.next
            error = null
        }.onFailure(::fail)
    }

    fun loadEarlier() {
        val s = session ?: return
        val cursor = nextCursor ?: return
        if (loadingEarlier) return
        loadingEarlier = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { RemoteApi.messagesPage(hub, token, s.device, s.agent, s.id, cursor) }
            }.onSuccess { page ->
                older = page.messages + older
                nextCursor = page.next
                error = null
            }.onFailure(::fail)
            loadingEarlier = false
        }
    }

    fun loadAll() {
        scope.launch {
            loading = true
            error = null
            val list = runCatching { loadAgentSessions(ctx, agentId) }.getOrElse {
                fail(it)
                emptyList()
            }
            sessions = list
            val target = list.firstOrNull { it.id == initialSessionId } ?: list.firstOrNull()
            session = target
            if (target != null) loadMessages(target)
            loading = false
        }
    }

    LaunchedEffect(agentId) { loadAll() }
    // 用户往上翻了就不要再被新消息拽回底部；回到底部附近才重新跟随
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }.collect { (last, total) ->
            if (total > 0) stick = last >= total - 2
        }
    }
    LaunchedEffect(messages.size, session?.id) {
        if (messages.isNotEmpty() && stick) {
            val extra = if (nextCursor != null) 1 else 0
            listState.animateScrollToItem(messages.size - 1 + extra)
        }
    }
    // 会话在跑的时候消息会一直长：跑着的时候 1.2 秒拉一次，空闲 4 秒
    LaunchedEffect(session?.id) {
        val s = session ?: return@LaunchedEffect
        while (true) {
            val busy = latest.lastOrNull()?.let { it.role == "assistant" && it.at == 0L } == true
            val recent = System.currentTimeMillis() - sentAt < 30_000
            delay(if (busy || recent || session?.running == true) 1200 else 4000)
            if (!sending) loadMessages(s)
        }
    }
    // 「运行中」要能自己消失：每 20 秒刷一次会话状态（不重新选会话）
    LaunchedEffect(agentId) {
        while (true) {
            delay(20_000)
            val fresh = runCatching { loadAgentSessions(ctx, agentId) }.getOrNull() ?: continue
            sessions = fresh
            val id = session?.id ?: continue
            fresh.firstOrNull { it.id == id }?.let { session = it }
        }
    }

    PageBackground {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarIconButton(MikaIcons.Back, "返回", onBack)
                AgentAvatar(agentId, title, 30.dp, onClick = onOpenProfile)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MikaType.Title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = when {
                        loading -> "正在连接…"
                        session != null -> buildString {
                            append(session?.device.orEmpty())
                            append(" · ")
                            append(session?.title.orEmpty().ifBlank { session?.id.orEmpty() })
                            if (session?.running == true) append(" · 运行中")
                        }
                        else -> "还没有会话"
                    }
                    Text(sub, style = MikaType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    BarIconButton(MikaIcons.Chats, "切换会话", { menu = true }, enabled = sessions.isNotEmpty())
                    DropdownMenu(menu, { menu = false }) {
                        sessions.take(50).forEach { s ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            s.title.ifBlank { s.id },
                                            style = MikaType.Body,
                                            color = c.ink,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "${s.device} · ${conversationTime(s.updated, System.currentTimeMillis())}",
                                            style = MikaType.Hint,
                                            color = c.ink3,
                                        )
                                    }
                                },
                                onClick = {
                                    menu = false
                                    session = s
                                    latest = emptyList()
                                    older = emptyList()
                                    nextCursor = null
                                    scope.launch { loadMessages(s) }
                                },
                            )
                        }
                    }
                }
            }

            error?.let {
                Text(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MikaType.Hint, color = c.bad, maxLines = 2)
            }

            if (session == null && !loading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (error == null) "这个 agent 还没有会话" else "连不上 hub",
                        style = MikaType.Caption,
                        color = c.ink3,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                    if (nextCursor != null) {
                        item("earlier") {
                            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                TextButton(onClick = ::loadEarlier, enabled = !loadingEarlier) {
                                    Text(
                                        if (loadingEarlier) "载入中…" else "载入更早",
                                        style = MikaType.Hint,
                                        color = c.ink3,
                                    )
                                }
                            }
                        }
                    }
                    itemsIndexed(messages) { i, m ->
                        MessageBubble(
                            m,
                            streaming = i == messages.lastIndex && m.role == "assistant" && m.at == 0L,
                        )
                    }
                }
            }

            val canSend = session?.agent == "opencode"
            GlassBar(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 14.dp)) {
                Box(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
                    if (input.isEmpty()) {
                        Text(
                            if (canSend) "想让 $title 做什么？" else "这个 agent 只读",
                            style = MikaType.Body,
                            color = c.ink3,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        input,
                        { input = it },
                        Modifier.fillMaxWidth(),
                        enabled = canSend,
                        textStyle = MikaType.Body.copy(color = c.ink),
                        cursorBrush = SolidColor(c.accent),
                        maxLines = 5,
                    )
                }
                if (sending) {
                    Box(
                        Modifier.size(MikaSize.IconButton).background(c.ink, CircleShape).clip(CircleShape)
                            .clickable(enabled = canSend) {
                                val s = session ?: return@clickable
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) { RemoteApi.interrupt(hub, token, s.device, s.id) }
                                    }.onFailure(::fail)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MikaIcons.Stop, "停止", Modifier.size(24.dp), tint = c.bg)
                    }
                } else {
                    val ready = input.isNotBlank() && canSend
                    Box(
                        Modifier.size(MikaSize.IconButton)
                            .background(if (ready) c.accent else c.greySoft, CircleShape).clip(CircleShape)
                            .clickable(enabled = ready) {
                                val s = session ?: return@clickable
                                val text = input.trim()
                                if (text.isEmpty()) return@clickable
                                input = ""
                                sending = true
                                val pending = RemoteApi.Message("user", text, "", System.currentTimeMillis(), emptyList())
                                latest = latest + pending
                                scope.launch {
                                    val r = runCatching {
                                        withContext(Dispatchers.IO) { RemoteApi.prompt(hub, token, s.device, s.id, text) }
                                    }
                                    if (r.isFailure) {
                                        latest = latest.filter { it !== pending }
                                        input = text
                                        fail(r.exceptionOrNull() ?: RuntimeException("发送失败"))
                                    } else {
                                        sentAt = System.currentTimeMillis()
                                        loadMessages(s)
                                    }
                                    sending = false
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MikaIcons.Up, "发送", Modifier.size(20.dp), tint = if (ready) c.onAccent else c.ink3)
                    }
                }
            }
        }
    }
}

package com.wangzan101.mika.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 消息页：agent 就是聊天对象。Mika 是手机端，opencode / codex 是电脑端；
 * 点头像进资料页，点整行进对话。底部只有「消息 / 我的」两栏。
 */
@Composable
fun HomeScreen(
    onOpenMika: () -> Unit,
    onOpenAgent: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenSettings: () -> Unit,
    updateAvailable: Boolean = false,
    mikaRunning: Boolean = false,
) {
    val ctx = LocalContext.current
    val c = MikaTheme.colors
    var contacts by remember { mutableStateOf<List<AgentContact>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tick) {
        loading = true
        // 本地 Mika 先上屏，不等 hub
        val local = runCatching { loadMikaContact(ctx) }.getOrNull()
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
        } else if (local != null) {
            contacts = (listOf(local) + cached)
                .sortedWith(compareByDescending<AgentContact> { it.pinned }.thenByDescending { it.lastAt })
        }
        error = null
    }
    // 会话在别处也会变：30 秒刷新一次，回到前台时再刷一次（由调用方切 tick）
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }

    PageBackground {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("消息", style = MikaType.Title.copy(fontSize = 21.sp), color = c.ink)
                Spacer(Modifier.weight(1f))
                BarIconButton(MikaIcons.Refresh, "刷新", { tick++ })
            }

            if (error != null) {
                Text(
                    error.orEmpty(),
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    style = MikaType.Hint,
                    color = c.bad,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(contacts, key = { it.id }) { contact ->
                    ContactRow(
                        contact = contact,
                        running = contact.running || (contact.kind == AgentKind.Phone && mikaRunning),
                        menuOpen = menuFor == contact.id,
                        onClick = {
                            AgentPrefs.markSeen(ctx, contact.id)
                            if (contact.kind == AgentKind.Phone) onOpenMika() else onOpenAgent(contact.id)
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
                if (contacts.isEmpty() && !loading) {
                    item("empty") {
                        Text(
                            "还没有可聊的 agent。\n先在「我的 → 网络与 VPN」里连上 hub，或直接和 Mika 说话。",
                            Modifier.fillMaxWidth().padding(28.dp),
                            style = MikaType.Caption,
                            color = c.ink3,
                        )
                    }
                }
            }

            BottomBar(
                updateAvailable = updateAvailable,
                onMessages = {},
                onClash = {
                    runCatching { ctx.startActivity(android.content.Intent(ctx, com.github.kr328.clash.MainActivity::class.java)) }
                },
                onMine = onOpenSettings,
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
    val c = MikaTheme.colors
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (contact.pinned) {
                        Icon(MikaIcons.Pin, "置顶", Modifier.size(13.dp), tint = c.ink3)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        contact.name,
                        style = MikaType.BodyStrong,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (contact.muted) {
                        Spacer(Modifier.width(4.dp))
                        Icon(MikaIcons.Mute, "免打扰", Modifier.size(13.dp), tint = c.ink3)
                    }
                    if (running) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(7.dp).clip(MikaShapes.Pill).background(c.ok))
                    }
                    if (contact.kind == AgentKind.Desktop) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (contact.online) "电脑" else "离线",
                            style = MikaType.Hint,
                            color = if (contact.online) c.ink3 else c.bad,
                        )
                    }
                    Spacer(Modifier.weight(1f))
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
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        if (contact.lastAt > 0) conversationTime(contact.lastAt, System.currentTimeMillis()) else "",
                        style = MikaType.Hint,
                        color = c.ink3,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        contact.lastText,
                        Modifier.weight(1f),
                        style = MikaType.Caption,
                        color = c.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (contact.kind == AgentKind.Desktop) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${contact.deviceCount} 台 · ${contact.sessionCount} 个会话",
                            style = MikaType.Hint,
                            color = c.ink3,
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
private fun BottomBar(updateAvailable: Boolean, onMessages: () -> Unit, onClash: () -> Unit, onMine: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .navigationBarsPadding()
            .height(56.dp),
    ) {
        BottomTab(MikaIcons.Message, "消息", true, false, onMessages, Modifier.weight(1f))
        BottomTab(MikaIcons.Shield, "Clash", false, false, onClash, Modifier.weight(1f))
        BottomTab(MikaIcons.Gear, "我的", false, updateAvailable, onMine, Modifier.weight(1f))
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
    val c = MikaTheme.colors
    val tint = if (selected) c.accent else c.ink3
    Box(modifier.fillMaxSize().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box {
                Icon(icon, label, Modifier.size(22.dp), tint = tint)
                if (badge) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(MikaShapes.Pill)
                            .background(c.bad)
                            .align(Alignment.TopEnd),
                    )
                }
            }
            Text(label, style = MikaType.Hint, color = tint)
        }
    }
}

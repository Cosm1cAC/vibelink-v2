package com.wangzan101.mika.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * agent 资料页：点头像进来。现在先展示身份、设备和会话数，
 * 后面模型 / 权限 / 通知这些每-agent 的设置都放这一页。
 */
@Composable
fun AgentProfileScreen(
    agentId: String,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenSession: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val c = MikaTheme.colors
    var contact by remember { mutableStateOf<AgentContact?>(null) }
    var conversations by remember { mutableStateOf(0) }
    var latest by remember { mutableStateOf<String?>(null) }
    var sessions by remember { mutableStateOf<List<com.wangzan101.mika.remote.RemoteApi.Session>>(emptyList()) }
    var muted by remember { mutableStateOf(false) }
    var showManager by remember { mutableStateOf(false) }

    LaunchedEffect(agentId) {
        muted = AgentPrefs.muted(ctx, agentId)
        if (agentId == MIKA_AGENT_ID) {
            val list = withContext(Dispatchers.IO) { ConversationStore.shared(ctx).list() }
            conversations = list.size
            latest = list.maxByOrNull { it.updatedAt }?.title
            contact = AgentContact(
                id = MIKA_AGENT_ID,
                name = "Mika",
                kind = AgentKind.Phone,
                online = true,
                deviceCount = 1,
                sessionCount = list.size,
                lastText = latest ?: "",
                lastAt = list.maxOfOrNull { it.updatedAt } ?: 0L,
            )
        } else {
            sessions = runCatching { loadAgentSessions(ctx, agentId) }.getOrElse { emptyList() }
            val latestSession = sessions.firstOrNull()
            contact = AgentContact(
                id = agentId,
                name = agentDisplayName(agentId),
                kind = AgentKind.Desktop,
                online = sessions.isNotEmpty(),
                deviceCount = sessions.map { it.device }.filter { it.isNotBlank() }.distinct().size,
                sessionCount = sessions.size,
                lastText = latestSession?.title.orEmpty(),
                lastAt = latestSession?.updated ?: 0L,
                model = latestSession?.model?.takeIf { it.isNotBlank() },
            )
        }
    }

    val name = contact?.name ?: agentId
    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(name, MikaIcons.Back, onBack, "返回")
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(24.dp))
                AgentAvatar(agentId, name, 84.dp)
                Spacer(Modifier.height(12.dp))
                Text(name, style = MikaType.Title.copy(fontSize = 20.sp), color = c.ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (contact?.kind == AgentKind.Phone) "手机端 agent · 本机" else "电脑端 agent",
                    style = MikaType.Hint,
                    color = c.ink3,
                )
                Spacer(Modifier.height(20.dp))
                StatRow("会话", (contact?.sessionCount ?: conversations).toString())
                if (contact?.kind == AgentKind.Desktop) {
                    StatRow("设备", "${contact?.deviceCount ?: 0} 台在线")
                }
                StatRow("状态", if (contact?.online == true) "在线" else "离线")
                if (contact?.kind == AgentKind.Phone) {
                    SettingRow("模型", "在「我的 → 模型」里设置", onClick = onOpenSettings)
                    SettingRow("权限与审批", "在「我的」里管理", onClick = onOpenSettings)
                    SettingRow("管理对话", "重命名 / 删除 / 置顶", onClick = { showManager = true })
                } else {
                    SettingRow("模型", contact?.model?.takeIf { it.isNotBlank() } ?: "由被控端管理")
                    SettingRow("权限", "由被控端管理")
                }
                SettingRow("通知", if (muted) "免打扰" else "开") {
                    muted = !muted
                    AgentPrefs.setMuted(ctx, agentId, muted)
                }
                latest?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "最近：$it",
                        Modifier.fillMaxWidth(),
                        style = MikaType.Caption,
                        color = c.ink3,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(24.dp))
                MikaButton("打开聊天", onOpenChat, Modifier.fillMaxWidth())
                if (sessions.isNotEmpty()) {
                    Spacer(Modifier.height(28.dp))
                    Text("会话", Modifier.fillMaxWidth(), style = MikaType.Label, color = c.ink3)
                    Spacer(Modifier.height(4.dp))
                    sessions.take(30).forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenSession(s.id) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
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
                            Text("›", style = MikaType.Title, color = c.ink3)
                        }
                    }
                    if (sessions.size > 30) {
                        Text(
                            "还有 ${sessions.size - 30} 个会话",
                            Modifier.fillMaxWidth(),
                            style = MikaType.Hint,
                            color = c.ink3,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showManager) ConversationManagerDialog(onDismiss = { showManager = false })
}

@Composable
private fun StatRow(label: String, value: String) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MikaType.Body, color = c.ink)
        Text(value, style = MikaType.Value, color = c.ink2)
    }
}

@Composable
private fun SettingRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    val c = MikaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MikaType.Body, color = c.ink)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MikaType.Value,
            color = c.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            Text("›", style = MikaType.Title, color = c.ink3)
        }
    }
}

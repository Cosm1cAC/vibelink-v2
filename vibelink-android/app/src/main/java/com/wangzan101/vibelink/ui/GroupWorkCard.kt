package com.wangzan101.vibelink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wangzan101.vibelink.remote.GroupWork
import com.wangzan101.vibelink.ui.theme.VibeLinkTheme
import com.wangzan101.vibelink.ui.theme.VibeLinkType

@Composable
internal fun GroupWorkCard(
    work: GroupWork, bots: List<BotFriend>, onCancel: () -> Unit, onRetry: () -> Unit,
    onReply: () -> Unit, onSession: (String, String) -> Unit,
) {
    val c = VibeLinkTheme.colors
    var details by remember(work.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp))
        .border(1.dp, c.edge, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(work.title.ifBlank { work.text.take(70) }.ifBlank { "附件任务" }, style = VibeLinkType.BodyStrong, color = c.ink)
        val phase = when (work.phase) { "intake" -> "等待调度"; "planning", "route" -> "主脑拆解"; "work" -> "执行"; "consult" -> "核查"; "review" -> "审校"; "deliver", "delivery" -> "验收与交付"; "rework" -> "返工"; else -> work.phase }
        Text("${work.statusLabel} · $phase", style = VibeLinkType.Caption, color = c.accent)
        TextButton(onClick = { details = !details }) { Text(if (details) "收起执行细节" else "执行细节（${work.steps.size}）") }
        if (details) work.steps.forEach { step ->
            val name = bots.firstOrNull { it.id == step.botId }?.name ?: step.botId
            val kind = when (step.kind) { "route" -> "拆解"; "work" -> "执行"; "consult" -> "核查"; "review" -> "审校"; "deliver" -> "验收与交付"; else -> step.kind }
            val state = when (step.status) { "queued" -> "待执行"; "running", "dispatching", "waiting" -> "进行中"; "completed", "done" -> "完成"; "failed" -> "失败"; "cancelled" -> "已取消"; "cancelled_unknown" -> "停止状态待确认"; else -> step.status }
            Text("$name · $kind · $state", style = VibeLinkType.Caption, color = c.ink2)
            if (step.error.isNotBlank()) Text(step.error, style = VibeLinkType.Caption, color = c.ink2)
            if (step.sessionId.isNotBlank()) {
                TextButton(onClick = { onSession(step.botId, step.sessionId) }) { Text("查看 $name 会话") }
            }
        }
        if (work.error.isNotBlank()) Text(work.error, style = VibeLinkType.Caption, color = c.ink2)
        if (work.inputs.isNotEmpty()) {
            val latest = work.inputs.last()
            Text("最近补充：${latest.text.take(140)}", style = VibeLinkType.Caption, color = c.ink3)
            if (latest.status.isNotBlank() || latest.detail.isNotBlank()) {
                Text("${latest.status} ${latest.detail}", style = VibeLinkType.Caption, color = c.ink3)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (work.status != "cancelled") TextButton(onClick = onReply) { Text(if (work.status == "needs_input") "回复并继续" else "追问此任务") }
            if (work.active) TextButton(onClick = onCancel) { Text("取消任务") }
            if (work.retryable) TextButton(onClick = onRetry) { Text("重试任务") }
        }
    }
}

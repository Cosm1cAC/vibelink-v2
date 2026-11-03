package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * 会话管理：重命名 / 删除 / 置顶 Mika 的本地对话。
 * 侧栏去掉后，入口在 Mika 资料页；聊天页右上角只负责切换。
 */
@Composable
fun ConversationManagerDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val c = MikaTheme.colors
    val store = remember { ConversationStore.shared(ctx) }
    var revision by remember { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleting by remember { mutableStateOf<ConversationSummary?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = c.surface) {
            Column(Modifier.padding(16.dp)) {
                Text("会话管理", style = MikaType.Title, color = c.ink)
                Spacer(Modifier.height(8.dp))
                val list = remember(revision) { store.list() }
                if (list.isEmpty()) {
                    Text("还没有会话", style = MikaType.Caption, color = c.ink3, modifier = Modifier.padding(12.dp))
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(list, key = { it.id }) { conv ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    conv.title.ifBlank { conv.id },
                                    Modifier.weight(1f),
                                    style = MikaType.Body,
                                    color = c.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                IconButton(onClick = {
                                    store.setPinned(conv.id, conv.pinnedAt == null)
                                    revision++
                                }) {
                                    Icon(
                                        MikaIcons.Pin,
                                        if (conv.pinnedAt == null) "置顶" else "取消置顶",
                                        Modifier.size(20.dp),
                                        tint = if (conv.pinnedAt == null) c.ink3 else c.accent,
                                    )
                                }
                                IconButton(onClick = { renaming = conv }) {
                                    Icon(MikaIcons.Edit, "重命名", Modifier.size(20.dp), tint = c.ink3)
                                }
                                IconButton(onClick = { deleting = conv }) {
                                    Icon(MikaIcons.Trash, "删除", Modifier.size(20.dp), tint = c.bad)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { conv ->
        var text by remember(conv.id) { mutableStateOf(conv.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    store.rename(conv.id, text)
                    revision++
                    renaming = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }
    deleting?.let { conv ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除对话") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(conv.id)
                    revision++
                    deleting = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

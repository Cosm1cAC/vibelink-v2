package com.wangzan101.vibelink.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.vibelink.about.ApkUpdater
import com.wangzan101.vibelink.about.ReleaseInfo
import com.wangzan101.vibelink.ui.theme.VibeLinkTheme
import com.wangzan101.vibelink.ui.theme.VibeLinkType

/**
 * 开机弹出的更新框：发现新版本就摆在这里，点一下就在 App 内下载并拉起安装器。
 * 以前只有「设置 › 关于」里能看到，日常在用聊天页的人根本发现不了。
 */
@Composable
fun UpdateDialog(r: ReleaseInfo, current: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var progress by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        title = { Text("发现新版本 ${r.version}") },
        text = {
            Column {
                Text("当前 $current · 下载完直接拉起系统安装器", style = VibeLinkType.Caption, color = VibeLinkTheme.colors.ink3)
                if (r.notes.isNotBlank()) {
                    Text(
                        r.notes,
                        Modifier.padding(top = 8.dp),
                        fontSize = 13.sp,
                        maxLines = 6,
                        color = VibeLinkTheme.colors.ink2,
                    )
                }
                error?.let {
                    Text(it, Modifier.padding(top = 8.dp), style = VibeLinkType.Caption, color = VibeLinkTheme.colors.bad)
                }
                progress?.let { p ->
                    LinearProgressIndicator(
                        progress = { p / 100f },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = progress == null,
                onClick = {
                    error = null
                    progress = 0
                    ApkUpdater.start(
                        ctx, r.downloadUrl, expected = r.version,
                        onProgress = { progress = it },
                        onError = { error = it; progress = null },
                        onReady = { progress = null },
                    )
                },
            ) {
                val file = r.downloadUrl.substringBefore('?').substringAfterLast('/')
                Text(progress?.let { "下载中 $it% · $file" } ?: "立即更新")
            }
        },
        dismissButton = {
            TextButton(enabled = progress == null, onClick = onDismiss) { Text("稍后") }
        },
    )
}



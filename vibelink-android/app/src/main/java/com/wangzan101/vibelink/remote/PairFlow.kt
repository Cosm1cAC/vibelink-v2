package com.wangzan101.vibelink.remote

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.vibelink.ui.components.ButtonKind
import com.wangzan101.vibelink.ui.components.GroupCard
import com.wangzan101.vibelink.ui.components.VibeLinkButton
import com.wangzan101.vibelink.ui.theme.VibeLinkTheme
import com.wangzan101.vibelink.ui.theme.VibeLinkType
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 扫码连接 vibelink（流程参考 ChatGPT 的远程控制）：
 * 1. 电脑上 vibelink 桌面端「📱 连接手机」显示二维码；
 * 2. 这里点「扫码连接」扫码（或用系统相机扫，`vibelink://pair` 链接会直接拉起 VibeLink）；
 * 3. 手机先向 hub 报到（桌面端显示「已扫码」），这里弹出「连接到 xx？」确认；
 * 4. 点「连接」才真正换 token，桌面端显示「已连接」。
 */
@Composable
internal fun PairSetup(
    hub: String,
    onHub: (String) -> Unit,
    token: String,
    onToken: (String) -> Unit,
    onManualConnect: () -> Unit,
    onPaired: (hub: String, token: String, link: PairLink, host: String) -> Unit,
) {
    val c = VibeLinkTheme.colors
    val scope = rememberCoroutineScope()
    val deviceName = remember { "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim().take(40) }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** 扫到、已报到、等用户确认的那个码 */
    var link by remember { mutableStateOf<PairLink?>(null) }
    var scan by remember { mutableStateOf<RemoteApi.PairScan?>(null) }
    var manual by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }

    fun checkIn(l: PairLink) {
        val urls = LoginPolicy.allowedUrls(l.urls, com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION)
        if (urls.isEmpty()) { error = "二维码属于其他版本，请使用对应的开发版或内测版"; return }
        val allowed = l.copy(urls = urls)
        error = null
        link = allowed
        scan = null
        busy = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { RemoteApi.pairScan(allowed, deviceName) } }
                .onSuccess { scan = it }
                .onFailure { error = it.message ?: it.toString(); link = null }
            busy = false
        }
    }

    fun handleRaw(raw: String?) {
        if (LoginQrLink.parse(raw) != null) { LoginQrInbox.offer(raw); return }
        val l = PairLink.parse(raw)
        if (l == null) error = "这不是 VibeLink 配对码：请扫电脑上「远程控制」弹窗（或 VibeLink 控制台「📱 连接手机」）里的二维码" else checkIn(l)
    }

    val scanner = rememberLauncherForActivityResult(ScanQRCode()) { r ->
        when (r) {
            is QRResult.QRSuccess -> handleRaw(r.content.rawValue ?: r.content.rawBytes?.let { String(it) })
            QRResult.QRUserCanceled -> Unit
            QRResult.QRMissingPermission -> error = "需要相机权限才能扫码：请在系统设置里允许 VibeLink 使用相机"
            is QRResult.QRError -> error = "扫码出错：${r.exception.message ?: r.exception}"
        }
    }

    // 系统相机扫到 vibelink://pair 拉起 VibeLink 时，直接进确认
    val inbox by PairInbox.pending.collectAsState()
    LaunchedEffect(inbox) {
        if (inbox != null) PairInbox.take()?.let(::checkIn)
    }

    Column(Modifier.padding(horizontal = 16.dp)) {
        val l = link
        val s = scan
        if (l != null && s != null) {
            // ---------- 确认 ----------
            GroupCard(Modifier.padding(top = 12.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("🖥️", fontSize = 40.sp)
                    Text(
                        "登录到 ${s.host.ifBlank { "这台电脑" }}？",
                        Modifier.padding(top = 10.dp),
                        fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = c.ink, textAlign = TextAlign.Center,
                    )
                    Text(
                        "连接后，这台手机可以查看和控制这台电脑上的 AI 会话、设备状态。只连接你自己的电脑。",
                        Modifier.padding(top = 8.dp),
                        style = VibeLinkType.Caption, color = c.ink3, textAlign = TextAlign.Center,
                    )
                    Text(
                        s.hub,
                        Modifier.padding(top = 6.dp),
                        style = VibeLinkType.Caption, color = c.ink3, textAlign = TextAlign.Center,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VibeLinkButton(
                    "取消",
                    onClick = { link = null; scan = null; error = null },
                    modifier = Modifier.weight(1f),
                    kind = ButtonKind.Secondary,
                    enabled = !busy,
                )
                VibeLinkButton(
                    if (busy) "登录中…" else "确认手机登录",
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { RemoteApi.pairConfirm(s.hub, l, deviceName) } }
                                .onSuccess { t -> onPaired(s.hub, t, l, s.host) }
                                .onFailure { error = it.message ?: it.toString(); link = null; scan = null }
                            busy = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                )
            }
        } else {
            // ---------- 扫码入口 ----------
            GroupCard(Modifier.padding(top = 12.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("📷", fontSize = 40.sp)
                    Text(
                        "手机扫码登录",
                        Modifier.padding(top = 10.dp),
                        fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = c.ink,
                    )
                    Text(
                        "扫描电脑 VibeLink 的手机连接码登录这台手机；扫描桌面登录码则进入授权确认。",
                        Modifier.padding(top = 8.dp),
                        style = VibeLinkType.Caption, color = c.ink3, textAlign = TextAlign.Center,
                    )
                }
            }
            VibeLinkButton(
                if (busy) "正在读取…" else "扫一扫",
                onClick = { error = null; scanner.launch(null) },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                enabled = !busy,
            )
        }

        error?.let {
            Text(it, Modifier.padding(top = 10.dp), style = VibeLinkType.Caption, color = c.bad)
        }
        if (busy) {
            Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp))
            }
        }

        if (link == null) {
            TextButton(onClick = { manual = !manual }, modifier = Modifier.padding(top = 8.dp)) {
                Text(if (manual) "收起" else "扫不了码？")
            }
            if (manual) {
                Text(
                    "在电脑上复制配对链接（vibelink://pair?…）粘贴到这里：",
                    style = VibeLinkType.Caption, color = c.ink3,
                )
                OutlinedTextField(
                    pasted, { pasted = it.trim() },
                    label = { Text("配对链接") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                TextButton(onClick = { handleRaw(pasted) }, enabled = !busy && pasted.isNotBlank()) { Text("用链接连接") }
                Text(
                    "已经有这台手机的设备 token：",
                    Modifier.padding(top = 8.dp), style = VibeLinkType.Caption, color = c.ink3,
                )
                OutlinedTextField(
                    hub, onHub,
                    label = { Text("Hub 地址") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    token, onToken,
                    label = { Text("Token") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                TextButton(onClick = onManualConnect, enabled = !busy && token.isNotBlank()) { Text("保存并连接") }
            }
        }
    }
}

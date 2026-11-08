package com.wangzan101.vibelink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wangzan101.vibelink.BuildConfig
import com.wangzan101.vibelink.remote.*
import com.wangzan101.vibelink.ui.components.*
import com.wangzan101.vibelink.ui.theme.VibeLinkTheme
import com.wangzan101.vibelink.ui.theme.VibeLinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One entry point for phone login, beta access, account management and desktop approval. */
@Composable
fun LoginScreen(onContinue: () -> Unit, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val c = VibeLinkTheme.colors
    val scope = rememberCoroutineScope()
    val state by AuthStore.state.collectAsState()
    val ready = state as? LoginState.Ready
    var switching by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var hub by remember { mutableStateOf(RemoteStore.hub(context)) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var desktopLink by remember { mutableStateOf<LoginQrLink?>(null) }
    var scan by remember { mutableStateOf<DesktopScan?>(null) }
    var desktopNotice by remember { mutableStateOf<String?>(null) }
    val incoming by LoginQrInbox.pending.collectAsState()
    val incomingPair by PairInbox.pending.collectAsState()
    val app = context.applicationContext as com.wangzan101.vibelink.VibeLinkApp
    val update by app.updates.state.collectAsState()
    LaunchedEffect(Unit) { app.updates.autoCheckInBackground() }
    LaunchedEffect(incomingPair) { if (incomingPair != null) switching = true }

    LaunchedEffect(incoming) {
        val link = LoginQrInbox.take() ?: return@LaunchedEffect
        desktopLink = link
        scan = null
        desktopNotice = null
        busy = true
        runCatching { withContext(Dispatchers.IO) {
            val allowed = LoginPolicy.allowedUrls(link.urls, BuildConfig.IS_BETA_EDITION)
            check(allowed.isNotEmpty()) { "请使用此二维码对应的开发版或内测版" }
            AuthApi.scan(link.copy(urls = allowed))
        } }
            .onSuccess { scan = it }.onFailure { error = it.message ?: "无法读取桌面登录请求" }
        busy = false
    }

    fun finishLogin() { code = ""; token = ""; switching = false; error = null; if (desktopLink == null) onContinue() }

    PageBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("VibeLink", style = VibeLinkType.LargeTitle, color = c.ink)
                Text(if (ready != null && !switching) "账号与登录" else "登录 VibeLink", style = VibeLinkType.Title, color = c.ink)
                Text(if (BuildConfig.IS_BETA_EDITION) "登录后进入你的内测工作区。" else "连接你的开发 Hub，登录后进入工作区。", style = VibeLinkType.Body, color = c.ink2)
                UpdateBanner(BuildConfig.VERSION_NAME, update, {
                    update.update?.let { com.wangzan101.vibelink.about.ApkUpdater.startWithToasts(context, it.downloadUrl) }
                })

                if (state is LoginState.Checking) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Text("正在验证已有登录…", color = c.ink2)
                    }
                }
                val statusError = (state as? LoginState.SignedOut)?.error
                (error ?: statusError)?.let { Text(it, style = VibeLinkType.Caption, color = c.bad) }
                if ((state as? LoginState.SignedOut)?.retryLogout == true) {
                    TextButton(onClick = { scope.launch { runCatching { AuthStore.logout(context) } } }, enabled = !busy) { Text("重试退出") }
                }

                if (ready != null && !switching) {
                    GroupCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(ready.session.label.ifBlank { "已登录" }, style = VibeLinkType.BodyStrong, color = c.ink)
                            Text(if (ready.session.edition == "beta") "内测账号 · 独立工作区" else "开发账号 · 独立工作区", style = VibeLinkType.Caption, color = c.ink2)
                            if (ready.offline) Text("当前离线，可查看已缓存内容。", style = VibeLinkType.Caption, color = c.ink3)
                            VibeLinkButton("进入首页", onContinue, Modifier.fillMaxWidth(), enabled = !busy)
                            Row {
                                TextButton(onClick = { switching = true }, enabled = !busy) { Text("切换登录") }
                                TextButton(onClick = {
                                    busy = true
                                    scope.launch {
                                        runCatching { AuthStore.logout(context) }
                                        busy = false
                                    }
                                }, enabled = !busy) { Text("退出登录") }
                            }
                        }
                    }
                }

                if (desktopLink != null) {
                    val link = desktopLink!!
                    val request = scan
                    GroupCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("确认桌面登录", style = VibeLinkType.BodyStrong, color = c.ink)
                            if (request == null) Text("正在读取请求…", color = c.ink2)
                            else {
                                Text(request.deviceName.ifBlank { "桌面设备" }, style = VibeLinkType.Body, color = c.ink)
                                Text("请核对电脑上的确认码", style = VibeLinkType.Caption, color = c.ink2)
                                Text(request.verificationCode, style = VibeLinkType.LargeTitle, fontWeight = FontWeight.Bold, color = c.accent)
                                Text(request.hub, style = VibeLinkType.Caption, color = c.ink3)
                                val sameHub = ready != null && LoginPolicy.mayApproveDesktop(RemoteApi.candidates(RemoteStore.hub(context)), request.hub,
                                    ready.session.issuer, request.issuer, ready.session.edition, request.edition)
                                if (!sameHub) Text("请先登录此 Hub，再确认电脑上的登录请求。", style = VibeLinkType.Caption, color = c.ink2)
                                val usable = request.expiresAt > System.currentTimeMillis() && request.status in setOf("pending", "scanned")
                                if (!usable) Text("请求已过期或已处理，请在电脑上刷新二维码。", style = VibeLinkType.Caption, color = c.ink2)
                                VibeLinkButton(if (busy) "处理中…" else "允许这台电脑登录", onClick = {
                                    busy = true; error = null
                                    scope.launch {
                                        runCatching { AuthStore.confirmDesktop(context, link, request, "approve") }
                                            .onSuccess { desktopLink = null; scan = null; desktopNotice = "已确认，请回电脑继续登录" }
                                            .onFailure { error = it.message ?: "确认失败" }
                                        busy = false
                                    }
                                }, modifier = Modifier.fillMaxWidth(), enabled = sameHub && usable && !busy)
                                TextButton(onClick = {
                                    if (sameHub && usable) scope.launch { runCatching { AuthStore.confirmDesktop(context, link, request, "deny") } }
                                    desktopLink = null; scan = null
                                }, enabled = !busy) { Text("取消") }
                            }
                        }
                    }
                }
                desktopNotice?.let { Text(it, style = VibeLinkType.Caption, color = c.accent) }

                if ((ready == null || switching) && state !is LoginState.Checking) {
                    if (BuildConfig.IS_BETA_EDITION) {
                        GroupCard {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("内测码登录", style = VibeLinkType.BodyStrong, color = c.ink)
                                OutlinedTextField(code, { code = it; error = null }, label = { Text("内测码") },
                                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                                VibeLinkButton(if (busy) "登录中…" else "登录", onClick = {
                                    busy = true; error = null
                                    scope.launch {
                                        runCatching { AuthStore.loginBeta(context, code) }.onSuccess { finishLogin() }
                                            .onFailure { error = it.message ?: "登录失败，请检查网络和内测码" }
                                        busy = false
                                    }
                                }, modifier = Modifier.fillMaxWidth(), enabled = !busy && code.isNotBlank())
                            }
                        }
                    }
                    PairSetup(hub, onHub = { hub = it }, token = token, onToken = { token = it },
                        onManualConnect = {
                            busy = true; error = null
                            scope.launch {
                                runCatching { AuthStore.loginCredential(context, hub, token) }.onSuccess { finishLogin() }
                                    .onFailure { error = it.message ?: "登录失败" }
                                busy = false
                            }
                        }, onPaired = { pairedHub, credential, pair, _ ->
                            busy = true; error = null
                            scope.launch {
                                runCatching { AuthStore.loginCredential(context, pairedHub, credential, pair.urls.filter { it != pairedHub }) }
                                    .onSuccess { finishLogin() }.onFailure { error = it.message ?: "无法验证这台手机的登录" }
                                busy = false
                            }
                        })
                    if (RemoteStore.token(context).isNotBlank()) TextButton(onClick = { AuthStore.retry(context) }, enabled = !busy) { Text("重新验证已有登录") }
                }
                if (ready != null && !switching) {
                    TextButton(onClick = { switching = true }) { Text("扫码登录另一台电脑") }
                }
                Text("Mika 的手机助手模型与本机对话保存在这台手机上，不作为 Hub 账号的云端数据同步。手机操作权限在使用 Mika 时按需配置。", style = VibeLinkType.Caption, color = c.ink3)
                if (ready != null && onBack != null) TextButton(onClick = onBack, enabled = !busy) { Text("返回") }
            }
        }
    }
}

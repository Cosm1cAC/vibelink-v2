package com.wangzan101.vibelink.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One entry point for phone login, beta access, account management and desktop approval. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onContinue: () -> Unit, onBack: (() -> Unit)? = null, onDesktopRequest: () -> Unit = {}) {
    val context = LocalContext.current
    val c = VibeLinkTheme.colors
    val scope = rememberCoroutineScope()
    val state by AuthStore.state.collectAsState()
    val ready = state as? LoginState.Ready
    var phoneLoginOpen by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var hub by remember { mutableStateOf(RemoteStore.hub(context)) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var desktopLink by remember { mutableStateOf<LoginQrLink?>(null) }
    var scan by remember { mutableStateOf<DesktopScan?>(null) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var scanLoading by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var desktopNotice by remember { mutableStateOf<String?>(null) }
    val desktopScanner = rememberLauncherForActivityResult(ScanQRCode()) { result ->
        when (result) {
            is QRResult.QRSuccess -> {
                val raw = result.content.rawValue ?: result.content.rawBytes?.let { String(it) }
                val next = LoginQrLink.parse(raw)
                if (next != null) LoginQrInbox.pending.value = next
                else error = "请扫描电脑上的登录授权码；手机连接码请从手机登录入口扫描。"
            }
            QRResult.QRMissingPermission -> error = "需要相机权限才能扫码，请在系统设置中允许使用相机。"
            is QRResult.QRError -> error = "无法启动扫码，请重试。"
            QRResult.QRUserCanceled -> Unit
        }
    }
    val incomingPair by PairInbox.pending.collectAsState()
    val app = context.applicationContext as com.wangzan101.vibelink.VibeLinkApp
    val update by app.updates.state.collectAsState()
    LaunchedEffect(Unit) { app.updates.autoCheckInBackground() }
    LaunchedEffect(incomingPair) { if (incomingPair != null) { switching = true; if (desktopLink != null) phoneLoginOpen = true } }

    LaunchedEffect(Unit) {
        LoginQrInbox.consume(onAccepted = onDesktopRequest) { link ->
            desktopLink = link
            phoneLoginOpen = false
            scan = null
            scanError = null
            error = null
            desktopNotice = null
            scanJob = currentCoroutineContext().job
            scanLoading = true
            try {
                scan = withContext(Dispatchers.IO) {
                    val allowed = LoginPolicy.scanUrls(link.urls, BuildConfig.IS_BETA_EDITION,
                        RemoteApi.candidates(RemoteStore.hub(context)))
                    check(allowed.isNotEmpty()) { "请使用此二维码对应的开发版或内测版" }
                    AuthApi.scan(link.copy(urls = allowed))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scanError = e.message ?: "无法读取桌面登录请求"
            } finally {
                scanLoading = false
                scanJob = null
            }
        }
    }

    fun finishLogin() { code = ""; token = ""; switching = false; phoneLoginOpen = false; error = null; if (desktopLink == null) onContinue() }

    val loginMethods: @Composable () -> Unit = {
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

    PageBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("VibeLink", style = VibeLinkType.LargeTitle, color = c.ink)
                Text(if (ready != null && !switching) "账号与登录" else "登录 VibeLink", style = VibeLinkType.Title, color = c.ink)
                Text(if (BuildConfig.IS_BETA_EDITION) "登录后进入你的内测工作区。" else "连接你的开发 Hub，登录后进入工作区。", style = VibeLinkType.Body, color = c.ink2)
                UpdateBanner(BuildConfig.VERSION_NAME, update, {
                    update.update?.let { com.wangzan101.vibelink.about.ApkUpdater.startWithToasts(context, it.downloadUrl) }
                })
                if (!BuildConfig.IS_BETA_EDITION) {
                    GroupCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("开发网络", style = VibeLinkType.BodyStrong, color = c.ink)
                            Text("还连不上开发 Hub？可先启动 Clash。首次使用日本出口时，在面板检测连接并授权 Tailscale，然后返回登录。", style = VibeLinkType.Caption, color = c.ink2)
                            VibeLinkButton("打开 Clash / 连接开发网络", {
                                runCatching {
                                    context.startActivity(android.content.Intent(context, com.github.kr328.clash.MainActivity::class.java))
                                }.onFailure { error = "Clash 面板未能打开，请稍后重试" }
                            }, Modifier.fillMaxWidth())
                        }
                    }
                }

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
                                TextButton(onClick = { switching = true; if (desktopLink != null) phoneLoginOpen = true }, enabled = !busy) { Text("切换登录") }
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
                            if (request == null) {
                                if (scanLoading) {
                                    Text("正在连接电脑并读取请求…", color = c.ink2)
                                    TextButton(onClick = { scanJob?.cancel(); desktopLink = null; scanError = null }) { Text("取消读取") }
                                } else {
                                    Text(scanError ?: "请求读取已停止，请重试。", color = c.bad)
                                    TextButton(onClick = { LoginQrInbox.pending.value = link }, enabled = !busy) { Text("重新读取") }
                                    TextButton(onClick = { desktopLink = null; scanError = null }, enabled = !busy) { Text("取消") }
                                }
                            } else {
                                Text(request.deviceName.ifBlank { "桌面设备" }, style = VibeLinkType.Body, color = c.ink)
                                Text("请核对电脑上的确认码", style = VibeLinkType.Caption, color = c.ink2)
                                Text(request.verificationCode, style = VibeLinkType.LargeTitle, fontWeight = FontWeight.Bold, color = c.accent)
                                Text(request.hub, style = VibeLinkType.Caption, color = c.ink3)
                                val action = desktopApprovalAction(ready?.session, state is LoginState.Checking,
                                    RemoteApi.candidates(RemoteStore.hub(context)), request, System.currentTimeMillis())
                                val sameHub = action == DesktopApprovalAction.Confirm
                                when (action) {
                                    DesktopApprovalAction.LoginPhone -> Text("这台手机尚未登录。先登录手机，再允许电脑使用同一账号。", style = VibeLinkType.Caption, color = c.ink2)
                                    DesktopApprovalAction.SwitchHub -> Text("手机当前账号不属于这台电脑。请明确切换手机登录后再确认。", style = VibeLinkType.Caption, color = c.ink2)
                                    DesktopApprovalAction.RenewQr -> Text("请求已过期或已处理，请在电脑上刷新二维码后重扫。", style = VibeLinkType.Caption, color = c.ink2)
                                    else -> Unit
                                }
                                if (sameHub) VibeLinkButton(if (busy) "处理中…" else "允许这台电脑登录", onClick = {
                                    busy = true; error = null
                                    scope.launch {
                                        runCatching { AuthStore.confirmDesktop(context, link, request, "approve") }
                                            .onSuccess {
                                                if (desktopLink == link) { desktopLink = null; scan = null; desktopNotice = "已确认，请回电脑继续登录" }
                                            }
                                            .onFailure { if (desktopLink == link) error = it.message ?: "确认失败" }
                                        busy = false
                                    }
                                }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                                else VibeLinkButton(when (action) {
                                    DesktopApprovalAction.LoginPhone -> "先登录这台手机"
                                    DesktopApprovalAction.SwitchHub -> "切换手机登录"
                                    DesktopApprovalAction.RenewQr -> "重新扫码"
                                    else -> "正在验证手机登录…"
                                }, onClick = {
                                    if (action == DesktopApprovalAction.RenewQr) {
                                        desktopScanner.launch(null)
                                    } else {
                                        hub = request.hub
                                        switching = true
                                        phoneLoginOpen = true
                                    }
                                }, modifier = Modifier.fillMaxWidth(), enabled = !busy && action != DesktopApprovalAction.Checking)
                                TextButton(onClick = {
                                    if (sameHub) scope.launch { runCatching { AuthStore.confirmDesktop(context, link, request, "deny") } }
                                    desktopLink = null; scan = null
                                }, enabled = !busy) { Text("取消") }
                            }
                        }
                    }
                }
                desktopNotice?.let { Text(it, style = VibeLinkType.Caption, color = c.accent) }

                if (desktopLink == null && (ready == null || switching) && state !is LoginState.Checking) loginMethods()
                if (ready != null && !switching) {
                    TextButton(onClick = { switching = true }) { Text("扫码登录另一台电脑") }
                }
                Text("Mika 的手机助手模型与本机对话保存在这台手机上，不作为 Hub 账号的云端数据同步。手机操作权限在使用 Mika 时按需配置。", style = VibeLinkType.Caption, color = c.ink3)
                if (ready != null && onBack != null) TextButton(onClick = onBack, enabled = !busy) { Text("返回") }
            }
        }
    }
    if (phoneLoginOpen) {
        ModalBottomSheet(onDismissRequest = { if (!busy) phoneLoginOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("登录这台手机", style = VibeLinkType.Title, color = c.ink)
                if (BuildConfig.IS_BETA_EDITION) {
                    Text("用内测码登录手机后，返回核对确认码并允许电脑登录。", style = VibeLinkType.Body, color = c.ink2)
                } else {
                    Text("首次使用：在电脑登录页点「首次连接手机」，再用下方扫一扫扫描电脑显示的「手机连接码」。", style = VibeLinkType.Body, color = c.ink2)
                    Text("手机连接码用于登录手机；刚才的电脑授权码会保留，手机登录后再确认。只连接你自己的电脑。", style = VibeLinkType.Caption, color = c.ink3)
                }
                error?.let { Text(it, style = VibeLinkType.Caption, color = c.bad) }
                loginMethods()
                TextButton(onClick = { phoneLoginOpen = false }, enabled = !busy) { Text("返回电脑确认") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

}

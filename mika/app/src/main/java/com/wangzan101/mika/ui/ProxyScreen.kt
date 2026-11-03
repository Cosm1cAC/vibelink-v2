package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.net.ClashVpnService
import com.wangzan101.mika.net.Net
import com.wangzan101.mika.net.ProxyStore
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.theme.MikaIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 网络代理：让 Mika 自己的请求（模型、下载、内置浏览器）走代理，不用再为它装 CMFA。
 * 局域网 / hub 一律直连（见 [Net.isLocal]），所以代理断了也不影响看远程会话。
 */
@Composable
fun ProxyScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(ProxyStore.mode(ctx)) }
    var host by remember { mutableStateOf(ProxyStore.manualHost(ctx)) }
    var port by remember { mutableStateOf(ProxyStore.manualPort(ctx).toString()) }
    var info by remember { mutableStateOf(ProxyStore.describe().ifBlank { "—" }) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // 整机代理：借已经配对的手机助手给系统设 HTTP 代理（不建 VPN，风险低）
    var systemProxy by remember { mutableStateOf<String?>(null) }
    var systemNote by remember { mutableStateOf("") }
    // 全局代理：mihomo 内核接管整机流量（需要系统 VPN 授权）
    var vpnOn by remember { mutableStateOf(ClashVpnService.running) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            ClashVpnService.start(ctx)
            systemNote = "正在开启…"
        } else {
            systemNote = "授权被取消"
        }
    }

    fun readSystemProxy() {
        scope.launch {
            val v = withContext(Dispatchers.IO) {
                runCatching {
                    PhoneServices.of(ctx).shellManager.commandRunner.exec("settings get global http_proxy").trim()
                }.getOrNull()
            }
            systemProxy = v?.takeIf { it.isNotBlank() && it != "null" && it != ":0" }
        }
    }

    fun toggleSystem(on: Boolean) {
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val svc = PhoneServices.of(ctx)
                    val target = ProxyStore.proxy()?.address() as? java.net.InetSocketAddress
                    if (on && target == null) return@runCatching "先在上面选一个出口（自动或手动）"
                    val cmd =
                        if (on) "settings put global http_proxy ${target!!.hostString}:${target.port}"
                        else "settings put global http_proxy :0"
                    svc.shellManager.commandRunner.exec(cmd)
                    if (on) "整机代理已开启：${target!!.hostString}:${target.port}" else "整机代理已关闭"
                }.getOrElse { "失败：${it.message ?: it}（手机助手没连上？）" }
            }
            systemNote = msg
            readSystemProxy()
        }
    }

    LaunchedEffect(Unit) { readSystemProxy() }
    // 服务可能在别处被停掉（网关失联自动关、系统回收），这里跟着真实状态走
    LaunchedEffect(Unit) {
        var last = vpnOn
        while (true) {
            val now = ClashVpnService.running
            if (now != last) {
                systemNote = if (now) "全局代理已开启" else (ClashVpnService.lastError ?: "全局代理已关闭")
                last = now
            }
            vpnOn = now
            delay(1500)
        }
    }

    fun refreshText() {
        info = ProxyStore.describe().ifBlank { "—" }
    }

    fun discover() {
        busy = true
        note = "正在问 hub…"
        scope.launch {
            note = withContext(Dispatchers.IO) { ProxyStore.refreshAuto(ctx) }
            Net.refresh(ctx)
            refreshText()
            busy = false
        }
    }

    fun choose(m: ProxyStore.Mode) {
        mode = m
        ProxyStore.setMode(ctx, m)
        Net.refresh(ctx)
        refreshText()
        if (m == ProxyStore.Mode.Auto) discover()
    }

    PageScaffold("网络代理", onBack) {
        GroupLabel("模式")
        GroupCard {
            ListRow(MikaIcons.Close, "关闭", "全部直连", first = true, onClick = { choose(ProxyStore.Mode.Off) }) {
                if (mode == ProxyStore.Mode.Off) RowValue("当前")
            }
            ListRow(MikaIcons.Globe, "自动", "问 hub 谁在做出口，用它的局域网地址", onClick = { choose(ProxyStore.Mode.Auto) }) {
                if (mode == ProxyStore.Mode.Auto) RowValue("当前")
            }
            ListRow(MikaIcons.Terminal, "手动", "自己填地址和端口", onClick = { choose(ProxyStore.Mode.Manual) }) {
                if (mode == ProxyStore.Mode.Manual) RowValue("当前")
            }
        }

        GroupLabel("当前出口")
        GroupCard {
            ListRow(MikaIcons.Shield, "生效地址", info, first = true) {
                if (busy) RowValue("检测中…")
            }
        }

        if (mode == ProxyStore.Mode.Auto) {
            MikaButton(
                if (busy) "检测中…" else "重新检测",
                { if (!busy) discover() },
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }

        if (mode == ProxyStore.Mode.Manual) {
            GroupLabel("手动地址")
            OutlinedTextField(
                host, { host = it },
                label = { Text("主机（如 192.168.137.156）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit) },
                label = { Text("端口（如 7897）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            MikaButton(
                "保存并应用",
                {
                    ProxyStore.setManual(ctx, host, port.toIntOrNull() ?: 7897)
                    Net.refresh(ctx)
                    refreshText()
                    note = "已保存"
                },
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }

        GroupLabel("全局代理（mihomo 内核）")
        GroupCard {
            ListRow(
                MikaIcons.Terminal,
                if (vpnOn) "运行中" else "未开启",
                if (vpnOn) "全部流量经 HY2 出口" else "接管整机流量，需要系统 VPN 授权",
                first = true,
                onClick = {
                    if (vpnOn) {
                        ClashVpnService.stop(ctx)
                        vpnOn = false
                        systemNote = "全局代理已关闭"
                    } else {
                        val prep = android.net.VpnService.prepare(ctx)
                        if (prep != null) consent.launch(prep)
                        else {
                            ClashVpnService.start(ctx)
                            systemNote = "正在开启…"
                        }
                    }
                },
            ) {
                RowValue(if (vpnOn) "关闭" else "开启")
            }
            ClashVpnService.lastError?.takeIf { !vpnOn }?.let {
                Text(it, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), fontSize = 13.sp)
            }
        }

        GroupLabel("整机代理（其他 App）")
        GroupCard {
            ListRow(
                MikaIcons.Shield,
                if (systemProxy != null) "已开启" else "关闭",
                systemProxy ?: "需要手机助手已配对（设置 › 手机操作）；不建 VPN，只改系统 HTTP 代理",
                first = true,
                onClick = { toggleSystem(systemProxy == null) },
            ) {
                RowValue(if (systemProxy != null) "关闭它" else "开启")
            }
        }
        if (systemNote.isNotBlank()) {
            Text(systemNote, Modifier.padding(horizontal = 4.dp, vertical = 4.dp), fontSize = 13.sp)
        }

        if (note.isNotBlank()) {
            Text(note, Modifier.padding(horizontal = 4.dp, vertical = 4.dp), fontSize = 13.sp)
        }

        FooterNote(
            "只作用于 Mika 自己的请求和内置浏览器。局域网、hub、Tailscale 地址一律直连，" +
                "所以代理挂了也能看远程会话。整机代理（VpnService）是另一套，不在这里。",
        )
    }
}






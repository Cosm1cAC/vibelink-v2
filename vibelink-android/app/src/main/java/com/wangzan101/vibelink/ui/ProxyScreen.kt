package com.wangzan101.vibelink.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.wangzan101.vibelink.cap.phone.PhoneServices
import com.wangzan101.vibelink.net.Net
import com.wangzan101.vibelink.net.ProxyStore
import com.wangzan101.vibelink.ui.components.FooterNote
import com.wangzan101.vibelink.ui.components.GroupCard
import com.wangzan101.vibelink.ui.components.GroupLabel
import com.wangzan101.vibelink.ui.components.ListRow
import com.wangzan101.vibelink.ui.components.VibeLinkButton
import com.wangzan101.vibelink.ui.components.PageScaffold
import com.wangzan101.vibelink.ui.components.RowValue
import com.wangzan101.vibelink.ui.theme.VibeLinkIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 网络代理：让 VibeLink 自己的请求（模型、下载、内置浏览器）走代理，不用再为它装 CMFA。
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
    var seedBusy by remember { mutableStateOf(false) }

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
            ListRow(VibeLinkIcons.Close, "跟随系统 / Clash VPN", "不额外指定 HTTP 代理", first = true, onClick = { choose(ProxyStore.Mode.Off) }) {
                if (mode == ProxyStore.Mode.Off) RowValue("当前")
            }
            ListRow(VibeLinkIcons.Globe, "自动", "问 hub 谁在做出口，用它的局域网地址", onClick = { choose(ProxyStore.Mode.Auto) }) {
                if (mode == ProxyStore.Mode.Auto) RowValue("当前")
            }
            ListRow(VibeLinkIcons.Terminal, "手动", "自己填地址和端口", onClick = { choose(ProxyStore.Mode.Manual) }) {
                if (mode == ProxyStore.Mode.Manual) RowValue("当前")
            }
        }

        GroupLabel("当前出口")
        GroupCard {
            ListRow(VibeLinkIcons.Shield, "生效地址", info, first = true) {
                if (busy) RowValue("检测中…")
            }
        }

        if (mode == ProxyStore.Mode.Auto) {
            VibeLinkButton(
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
            VibeLinkButton(
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

        GroupLabel("整机代理（其他 App）")
        GroupCard {
            ListRow(
                VibeLinkIcons.Shield,
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

        GroupLabel("Clash 完整界面（CMFA）")
        if (!com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION) {
            VibeLinkButton(
                if (seedBusy) "正在准备内置配置…" else "恢复并选用开发版内置配置",
                {
                    if (!seedBusy) scope.launch {
                        seedBusy = true
                        systemNote = try {
                            com.wangzan101.vibelink.clash.ClashSeed.prepare(ctx, restore = true)
                        } catch (_: Exception) { "内置配置未能导入，请检查配置或内核状态后重试" }
                        finally { seedBusy = false }
                    }
                },
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        GroupCard {
            ListRow(
                VibeLinkIcons.Shield,
                "打开 Clash 面板",
                "配置 / 代理组 / 日志 / 规则，CMFA 原版界面",
                first = true,
                onClick = {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(ctx, com.github.kr328.clash.MainActivity::class.java)
                        )
                    }.onFailure { systemNote = "打不开 Clash 面板：${it.message}" }
                },
            )
        }

        FooterNote(
            "只作用于 VibeLink 自己的请求和内置浏览器。局域网、hub、Tailscale 地址一律直连，" +
                "所以代理挂了也能看远程会话。整机 VPN 由上面的 Clash 面板统一接管。",
        )
    }
}






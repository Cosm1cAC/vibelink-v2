package com.wangzan101.vibelink.clash

internal object ExitProbeStatus {
    fun text(probing: Boolean, authorization: Boolean, reachable: Boolean?, tunnelActive: Boolean = true): String = when {
        !tunnelActive -> "当前未启用本应用 VPN，无法检测 VPN 出口；请先开启 VPN 模式。"
        probing -> "正在检测 YouTube 连接…"
        reachable == true -> "YouTube HTTPS 可访问（当前 VPN）"
        authorization -> "日本出口尚未授权：点击下方授权 Tailscale，完成后会自动重测。"
        reachable == false -> "YouTube 连接失败。VPN 已开启，请检查授权或出口；可查看日志后重试。"
        else -> "VPN 已开启，正在建立日本出口连接…"
    }
}

package com.wangzan101.vibelink.clash

internal object ExitProbeStatus {
    fun text(probing: Boolean, authorization: Boolean, reachable: Boolean?): String = when {
        probing -> "正在检测 YouTube 连接…"
        reachable == true -> "日本出口已连通 · YouTube HTTPS 检测通过"
        authorization -> "日本出口尚未授权：点击下方授权 Tailscale，完成后会自动重测。"
        reachable == false -> "YouTube 连接失败。VPN 已开启，请检查授权或出口；可查看日志后重试。"
        else -> "VPN 已开启，正在建立日本出口连接…"
    }
}

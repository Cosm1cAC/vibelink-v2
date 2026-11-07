package com.wangzan101.vibelink.remote

import android.content.Context

/**
 * vibelink hub 的地址和本机的 token。
 *
 * token 只能通过配对拿到：vibelink 桌面端「📱 连接手机」显示二维码，手机在「远程会话 › 扫码连接」里扫，
 * 确认后 hub 给这台手机签一个独立的 token。APK 里不内置任何 token。
 * 二维码里带着电脑的几个地址（热点 / 局域网 / tailnet / 公网），第一个连得上的存为 hub，其余存为备用。
 */
object RemoteStore {
    private const val PREF = "vibelink_remote"
    val DEFAULT_HUB: String
        get() = if (com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION) {
            "https://hub.vibelink.cloud"
        } else {
            "http://192.168.137.1:18995"
        }

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun hub(c: Context): String {
        // 顺手把备用地址交给 RemoteApi：所有调用方都先读 hub 再发请求
        RemoteApi.extraHubs = extraHubs(c)
        return prefs(c).getString("hub", "")?.takeIf { it.isNotBlank() } ?: DEFAULT_HUB
    }

    /** 配对拿到的 token；没配对过为空。 */
    fun token(c: Context): String = prefs(c).getString("token", "").orEmpty()

    /** 已配对的电脑名（确认页上显示过的那个）。 */
    fun hostName(c: Context): String = prefs(c).getString("host", "").orEmpty()

    /** 内测版已激活的邀请码标签（如「内测席位 #01」）。 */
    fun betaLabel(c: Context): String = prefs(c).getString("beta_label", "").orEmpty()

    fun betaInviteCode(c: Context): String = prefs(c).getString("beta_code", "").orEmpty()

    fun saveBetaActivation(c: Context, code: String, hub: String, token: String, label: String) {
        prefs(c).edit()
            .putString("beta_code", code.trim().uppercase())
            .putString("beta_label", label.trim())
            .putString("hub", hub.trim().trimEnd('/'))
            .putString("token", token.trim())
            .putString("host", label.trim())
            .apply()
    }

    fun extraHubs(c: Context): List<String> =
        prefs(c).getString("hubs", "").orEmpty().split('\n').map { it.trim() }.filter { it.isNotBlank() }

    fun isPaired(c: Context): Boolean = token(c).isNotBlank()

    fun save(c: Context, hub: String, token: String) {
        prefs(c).edit().putString("hub", hub.trim().trimEnd('/')).putString("token", token.trim()).apply()
    }

    /** 扫码配对成功：hub = 连得上的那个地址，其余地址留作备用。 */
    fun savePaired(c: Context, hub: String, token: String, link: PairLink, host: String) {
        val extras = link.urls.filter { it != hub }
        prefs(c).edit()
            .putString("hub", hub.trim().trimEnd('/'))
            .putString("token", token.trim())
            .putString("hubs", extras.joinToString("\n"))
            .putString("host", host)
            .apply()
        RemoteApi.extraHubs = extras
    }
}

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

    @Synchronized
    fun hub(c: Context): String {
        val hub = prefs(c).getString("hub", "")?.takeIf { it.isNotBlank() } ?: DEFAULT_HUB
        RemoteApi.configureRoutes(hub, token(c), extraHubs(c))
        return hub
    }

    /** 配对拿到的 token；没配对过为空。 */
    @Synchronized
    fun token(c: Context): String = prefs(c).getString("token", "").orEmpty()

    /** 已配对的电脑名（确认页上显示过的那个）。 */
    fun hostName(c: Context): String = prefs(c).getString("host", "").orEmpty()

    /** 内测版已激活的邀请码标签（如「内测席位 #01」）。 */
    fun betaLabel(c: Context): String = prefs(c).getString("beta_label", "").orEmpty()

    fun betaInviteCode(c: Context): String = prefs(c).getString("beta_code", "").orEmpty()

    @Synchronized
    fun saveBetaActivation(c: Context, code: String, hub: String, token: String, label: String) {
        prefs(c).edit()
            .putString("beta_code", code.trim().uppercase())
            .putString("beta_label", label.trim())
            .putString("hub", hub.trim().trimEnd('/'))
            .putString("token", token.trim())
            .putString("host", label.trim())
            .remove("hubs")
            .apply()
        this.hub(c)
    }

    fun extraHubs(c: Context): List<String> =
        prefs(c).getString("hubs", "").orEmpty().split('\n').map { it.trim() }.filter { it.isNotBlank() }

    fun isPaired(c: Context): Boolean = token(c).isNotBlank()

    @Synchronized
    fun save(c: Context, hub: String, token: String) {
        prefs(c).edit().putString("hub", hub.trim().trimEnd('/')).putString("token", token.trim()).remove("hubs").apply()
        this.hub(c)
    }

    /** 扫码配对成功：hub = 连得上的那个地址，其余地址留作备用。 */
    @Synchronized
    fun savePaired(c: Context, hub: String, token: String, link: PairLink, host: String) {
        val extras = link.urls.filter { it != hub }
        prefs(c).edit()
            .putString("hub", hub.trim().trimEnd('/'))
            .putString("token", token.trim())
            .putString("hubs", extras.joinToString("\n"))
            .putString("host", host)
            .apply()
        this.hub(c)
    }
}

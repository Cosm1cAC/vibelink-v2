package com.wangzan101.mika.remote

import android.content.Context
import com.wangzan101.mika.about.MikaLinks

/** Where the vibelink hub lives and the token used for every call. */
object RemoteStore {
    private const val PREF = "vibelink_remote"
    const val DEFAULT_HUB = "http://192.168.5.2:18995"

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun hub(c: Context): String = prefs(c).getString("hub", "")?.takeIf { it.isNotBlank() } ?: DEFAULT_HUB

    /**
     * 用户没填过就用内置更新地址里的 t=，省得在手机上手打 32 位 token；
     * 想换 hub 再在「设置」里覆盖。
     */
    fun token(c: Context): String {
        val saved = prefs(c).getString("token", "").orEmpty()
        if (saved.isNotBlank()) return saved
        return Regex("[?&]t=([A-Za-z0-9]+)")
            .find(MikaLinks.LATEST_RELEASE_API)
            ?.groupValues?.get(1)
            .orEmpty()
    }

    fun save(c: Context, hub: String, token: String) {
        prefs(c).edit().putString("hub", hub.trim().trimEnd('/')).putString("token", token.trim()).apply()
    }
}


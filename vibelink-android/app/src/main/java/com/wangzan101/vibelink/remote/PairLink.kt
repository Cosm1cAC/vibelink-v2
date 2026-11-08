package com.wangzan101.vibelink.remote

import java.net.URLDecoder

/**
 * vibelink 桌面端二维码里的配对链接：
 * `vibelink://pair?v=1&s=<128 位一次性密钥>&n=<电脑名>&u=<hub 地址>&u=...`
 *
 * 地址按桌面端排好的顺序给（热点 / 局域网 → tailnet → 公网），手机依次尝试。
 * 只认 http / https 地址；密钥必须是 32 位 hex，防止把别的二维码当成配对码。
 */
data class PairLink(val secret: String, val host: String, val urls: List<String>) {
    companion object {
        const val SCHEME = "vibelink"

        fun parse(raw: String?): PairLink? {
            val text = raw?.trim().orEmpty()
            val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return null
            if (!uri.scheme.equals(SCHEME, true) || !uri.host.equals("pair", true) || !uri.path.isNullOrEmpty() || uri.userInfo != null || uri.fragment != null) return null
            val query = text.substringAfter('?', "")
            if (query.isEmpty()) return null
            var secret = ""
            var host = ""
            val urls = mutableListOf<String>()
            for (kv in query.split('&')) {
                val key = kv.substringBefore('=')
                val value = runCatching { URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
                when (key) {
                    "s" -> secret = value.trim()
                    "n" -> host = value.filter { it.isLetterOrDigit() || it in "-_." }.take(40)
                    "u" -> {
                        val u = LoginPolicy.normalizedOrigin(value)
                        if (u != null && u.length < 200 && u !in urls) urls += u
                    }
                }
            }
            if (!Regex("^[0-9a-f]{32}$").matches(secret) || urls.isEmpty()) return null
            return PairLink(secret, host, urls.take(8))
        }
    }
}

/**
 * 系统相机 / 浏览器扫到 `vibelink://pair` 链接拉起 VibeLink 时，先放这里；远程会话页打开后取走并弹确认。
 */
object PairInbox {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<PairLink?>(null)

    fun offer(raw: String?): Boolean {
        val link = PairLink.parse(raw) ?: return false
        pending.value = link
        return true
    }

    fun take(): PairLink? = pending.value.also { pending.value = null }
}

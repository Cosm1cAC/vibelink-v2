package com.wangzan101.vibelink.remote

import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.flow.MutableStateFlow

data class LoginQrLink(val id: String, val scanSecret: String, val urls: List<String>) {
    companion object {
        fun parse(raw: String?): LoginQrLink? = runCatching {
            val uri = URI(raw?.trim().orEmpty())
            if (!uri.scheme.equals("vibelink", true) || !uri.host.equals("login", true) || !uri.path.isNullOrEmpty() || uri.userInfo != null || uri.fragment != null) return null
            val params = uri.rawQuery.orEmpty().split('&').map { part ->
                part.substringBefore('=') to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }
            fun one(key: String) = params.filter { it.first == key }.singleOrNull()?.second
            if (params.any { it.first in setOf("token", "pollSecret") }) return null
            if (one("v") != "1" || one("purpose") != "desktop_login") return null
            val id = one("id") ?: return null
            val secret = one("s") ?: return null
            if (!Regex("[A-Za-z0-9_-]{1,128}").matches(id) || !Regex("[A-Za-z0-9_-]{24,256}").matches(secret)) return null
            val urls = params.filter { it.first == "u" }.mapNotNull { LoginPolicy.normalizedOrigin(it.second) }.distinct().take(8)
            if (urls.isEmpty()) null else LoginQrLink(id, secret, urls)
        }.getOrNull()
    }
}

object LoginQrInbox {
    val pending = MutableStateFlow<LoginQrLink?>(null)
    fun offer(raw: String?): Boolean {
        LoginQrLink.parse(raw)?.let { PairInbox.pending.value = null; pending.value = it; return true }
        val accepted = PairInbox.offer(raw)
        if (accepted) pending.value = null
        return accepted
    }
    fun take(): LoginQrLink? = pending.value.also { pending.value = null }
}

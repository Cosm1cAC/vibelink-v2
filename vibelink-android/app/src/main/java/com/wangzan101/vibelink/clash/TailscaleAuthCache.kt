package com.wangzan101.vibelink.clash

import java.net.URI

/** Short-lived browser authorization capability, memory only. */
class TailscaleAuthCache(private val now: () -> Long, private val ttlMillis: Long = 180_000) {
    private var url: String? = null
    private var owner: String? = null
    private var expires = 0L
    @Synchronized fun accept(message: String, profile: String?, running: Boolean) {
        if (!running || profile == null) { clear(); return }
        if (!message.contains("[Tailscale]")) return
        val candidate = Regex("https://[^\\s<>\"]+").findAll(message).mapNotNull {
            allowedUrl(it.value.trimEnd('.', ',', ')', ']'))
        }.firstOrNull() ?: return
        if (candidate != url || owner != profile) {
            url = candidate; owner = profile; expires = now() + ttlMillis
        }
    }
    @Synchronized fun current(profile: String?, running: Boolean): String? {
        if (!running || profile == null || owner != profile || now() >= expires) { clear(); return null }
        return url
    }
    /** A completed load of the same profile must not discard its once-only URL. */
    @Synchronized fun profileLoaded(profile: String?) {
        if (profile == null || owner != profile) clear()
    }
    @Synchronized fun clear() { url = null; owner = null; expires = 0 }
    companion object {
        fun redact(message: String): String = Regex("https://login\\.tailscale\\.com/a/[^\\s<>\"]+", RegexOption.IGNORE_CASE)
            .replace(message, "[Tailscale authorization link hidden]")

        fun allowedUrl(value: String): String? = runCatching {
            val uri = URI(value)
            value.takeIf { uri.scheme == "https" && uri.host.equals("login.tailscale.com", true)
                && uri.port == -1 && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null
                && Regex("/a/[A-Za-z0-9_-]{6,128}").matches(uri.rawPath ?: "") }
        }.getOrNull()
    }
}

/** Observer changes do not replace the native log subscription or auth cache. */
class TailscaleLogRelay<T>(private val auth: TailscaleAuthCache) {
    @Volatile private var observer: ((T) -> Unit)? = null
    fun setObserver(value: ((T) -> Unit)?) { observer = value }
    fun accept(item: T, message: String, profile: String?, running: Boolean) {
        auth.accept(message, profile, running)
        val current = observer ?: return
        try { current(item) } catch (_: Exception) { if (observer === current) observer = null }
    }
}

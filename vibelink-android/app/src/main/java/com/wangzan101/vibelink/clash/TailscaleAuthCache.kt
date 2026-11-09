package com.wangzan101.vibelink.clash

import java.net.URI

/** Short-lived browser authorization capability, memory only. */
class TailscaleAuthCache(private val now: () -> Long, private val ttlMillis: Long = 180_000,
    private val wallClock: () -> Long = System::currentTimeMillis) {
    data class Load(val generation: Long, val profile: String)
    private var url: String? = null
    private var owner: String? = null
    private var expires = 0L
    private var generation = 0L
    private var loading: Load? = null
    private var committed: Load? = null
    private var committedAt = Long.MAX_VALUE
    // Current native logs have no source UUID and UpdateProxies does not join
    // the old tsnet servers. After a different profile was loaded, anonymity
    // cannot be repaired by merely stopping/restarting the Android service.
    // Only a fresh core process can safely bind its first profile again.
    private var firstProfile: String? = null
    private var ambiguousSource = false

    /** Selected preferences change before native load; only this boundary grants ownership. */
    @Synchronized fun beginLoad(profile: String): Load {
        if (firstProfile == null) firstProfile = profile
        else if (firstProfile != profile) ambiguousSource = true
        if (owner != profile || ambiguousSource) clear()
        committed = null
        committedAt = Long.MAX_VALUE
        return Load(++generation, profile).also { loading = it }
    }
    @Synchronized fun commitLoad(load: Load): Boolean {
        if (loading != load) return false
        committed = load
        loading = null
        committedAt = wallClock()
        return true
    }
    @Synchronized fun failLoad(load: Load) {
        if (loading != load) return
        loading = null
        committed = null
        // A partial native load can leave a live source behind, too.
        ambiguousSource = true
        clear()
    }
    @Synchronized fun stopped() {
        generation++
        loading = null
        committed = null
        committedAt = Long.MAX_VALUE
        clear()
    }
    /** Native logs do not carry a profile or generation. Never relabel one from preferences. */
    @Synchronized fun acceptCaptured(message: String, running: Boolean, emittedAt: Long) {
        val active = committed ?: return
        if (loading != null || ambiguousSource || emittedAt <= committedAt) return
        accept(message, active.profile, running)
    }
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
        if (firstProfile != null && (ambiguousSource || (committed?.profile ?: loading?.profile) != profile)) return null
        if (!running || profile == null || owner != profile || now() >= expires) { clear(); return null }
        return url
    }
    /** Broadcasts acknowledge the real committed UUID; they never select a log owner. */
    @Synchronized fun profileLoaded(profile: String?) {
        if (profile == null || profile != committed?.profile) return
        if (owner != profile) clear()
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
        forward(item)
    }
    fun acceptCaptured(item: T, message: String, running: Boolean, emittedAt: Long) {
        auth.acceptCaptured(message, running, emittedAt)
        forward(item)
    }
    private fun forward(item: T) {
        val current = observer ?: return
        try { current(item) } catch (_: Exception) { if (observer === current) observer = null }
    }
}

/** No arbitrary PID or profile selection is accepted by the core restart capability. */
internal object ManagedAuthorizationRestartPolicy {
    fun allows(beta: Boolean, hasSeed: Boolean, expected: String, active: String?, managed: String?,
        running: Boolean, processName: String?, packageName: String): Boolean =
        !beta && hasSeed && !running && expected.isNotBlank() && expected == active && expected == managed &&
            processName == "$packageName:background"
}

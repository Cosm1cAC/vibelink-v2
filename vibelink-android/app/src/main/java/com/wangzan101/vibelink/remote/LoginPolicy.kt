package com.wangzan101.vibelink.remote

data class ClientSession(
    val userId: String, val label: String, val tenantId: String?, val edition: String,
    val sessionId: String, val expiresAt: Long, val permissions: List<String>,
    val issuer: String = "", val hubId: String = issuer,
) {
    fun usableOffline(now: Long): Boolean = userId.isNotBlank() && issuer.isNotBlank() && expiresAt > now && "client" in permissions && permissions.none { it in setOf("admin", "agent") }
    fun subjectKey(hub: String): String = "${issuer.ifBlank { hub.trimEnd('/') }}|$edition|$userId"
}

internal object LoginPolicy {
    fun normalizedOrigin(value: String): String? = runCatching {
        val uri = java.net.URI(value.trim().trimEnd('/'))
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase() ?: return null
        if (scheme !in setOf("http", "https") || uri.userInfo != null || uri.query != null || uri.fragment != null ||
            !uri.rawPath.isNullOrEmpty() || (uri.port != -1 && uri.port !in 1..65535)) return null
        val defaultPort = (scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443)
        "$scheme://$host" + if (uri.port == -1 || defaultPort) "" else ":${uri.port}"
    }.getOrNull()
    private fun privateHost(origin: String): Boolean {
        val host = java.net.URI(origin).host.lowercase().removePrefix("[").removeSuffix("]")
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".lan") ||
            host.endsWith(".home.arpa") || host.endsWith(".internal") || host.endsWith(".ts.net")) return true
        if (':' in host) return host == "::1" || host.startsWith("fc") || host.startsWith("fd") ||
            listOf("fe8", "fe9", "fea", "feb").any { host.startsWith(it) }
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { Regex("0|[1-9][0-9]{0,2}").matches(it) && it.toInt() <= 255 }) {
            val p = parts.map { it.toInt() }
            return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) ||
                (p[0] == 172 && p[1] in 16..31) || (p[0] == 100 && p[1] in 64..127) || (p[0] == 169 && p[1] == 254)
        }
        return false
    }
    fun allowProtected(tokenPresent: Boolean, validated: Boolean, session: ClientSession?, now: Long): Boolean =
        tokenPresent && validated && session?.usableOffline(now) == true
    fun mayApproveDesktop(currentHubCandidates: List<String>, scannedHub: String,
        currentIssuer: String, scannedIssuer: String, currentEdition: String, scannedEdition: String): Boolean =
        currentIssuer.isNotBlank() && currentIssuer == scannedIssuer && currentEdition == scannedEdition &&
            normalizedOrigin(scannedHub)?.let { it in currentHubCandidates.mapNotNull(::normalizedOrigin) } == true
    fun allowedUrls(urls: List<String>, beta: Boolean): List<String> = urls.mapNotNull(::normalizedOrigin).distinct().filter {
        if (beta) it == "https://hub.vibelink.cloud" else privateHost(it)
    }
    /** Preference can reorder only origins already present in this QR, never add a new recipient. */
    fun scanUrls(urls: List<String>, beta: Boolean, known: List<String>): List<String> {
        val allowed = allowedUrls(urls, beta)
        val preferred = known.mapNotNull(::normalizedOrigin).filter { it in allowed }
        return (preferred + allowed).distinct()
    }
    fun editionMatches(session: ClientSession, beta: Boolean): Boolean = session.edition == if (beta) "beta" else "dev"
    fun transientFailure(httpStatus: Int?, networkFailure: Boolean): Boolean =
        networkFailure || httpStatus in setOf(408, 429) || (httpStatus != null && httpStatus >= 500)
}

internal data class ValidatedLogin(val token: String, val session: ClientSession)

/** A failed /me never runs exchange. New client sessions are not reissued on every launch. */
internal fun validateLoginCandidate(
    token: String, now: Long, read: () -> ClientSession, exchange: () -> ValidatedLogin, guard: () -> Unit = {},
): ValidatedLogin {
    val identity = read()
    guard()
    val login = if (identity.expiresAt == 0L) exchange() else ValidatedLogin(token, identity)
    guard()
    check(login.session.userId == identity.userId && login.session.issuer == identity.issuer &&
        login.session.edition == identity.edition && login.session.tenantId == identity.tenantId && login.session.usableOffline(now)) {
        "Hub 返回了不一致或已过期的登录身份"
    }
    return login
}

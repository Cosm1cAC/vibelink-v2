package com.wangzan101.vibelink.remote

import android.content.Context
import com.wangzan101.vibelink.settings.Preconfigure
import com.wangzan101.vibelink.ui.BotTeamStore
import com.wangzan101.vibelink.ui.GroupWorkStore
import com.wangzan101.vibelink.ui.RemoteSessionCache
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

sealed interface LoginState {
    data object Checking : LoginState
    data class SignedOut(val error: String? = null, val retryLogout: Boolean = false) : LoginState
    data class Ready(val session: ClientSession, val offline: Boolean = false) : LoginState
}

object AuthStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val booted = AtomicBoolean(false)
    private var app: Context? = null
    private var expiry: Job? = null
    private val _state = MutableStateFlow<LoginState>(LoginState.Checking)
    val state = _state.asStateFlow()
    private fun deviceName() = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim().take(80)

    fun boot(context: Context) {
        app = context.applicationContext
        if (booted.compareAndSet(false, true)) retry(context)
    }
    fun retry(context: Context) { scope.launch { validateExisting(context.applicationContext) } }
    private fun guard(context: Context, captured: RemoteStore.Connection) {
        check(RemoteStore.matches(context, captured)) { "登录身份已变化，请重试" }
    }
    private fun clear(context: Context, error: String? = null) {
        _state.value = LoginState.SignedOut(error)
        expiry?.cancel()
        try {
            RemoteStore.clearSession(context)
            Preconfigure.onLogout(context)
        } catch (e: Exception) {
            _state.value = LoginState.SignedOut(e.message ?: "退出状态未能保存，请重试退出", retryLogout = true)
        }
        BotTeamStore.resetAccountMemory()
        RemoteSessionCache.clearMemory()
    }
    fun unauthorized(token: String) {
        val context = app ?: return
        synchronized(this) { if (RemoteStore.token(context) == token && token.isNotBlank()) clear(context, "登录已失效，请重新登录") }
    }
    fun credentialUnavailable(context: Context) {
        if (_state.value is LoginState.Ready && RemoteStore.token(context).isBlank()) clear(context, "登录凭据不可用，请重新登录")
    }
    private fun ready(context: Context, session: ClientSession, offline: Boolean = false) {
        _state.value = LoginState.Ready(session, offline)
        expiry?.cancel()
        val captured = RemoteStore.connection(context)
        expiry = scope.launch {
            delay((session.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
            if (RemoteStore.matches(context, captured)) clear(context, "登录已过期，请重新登录")
        }
        if (!offline) GroupWorkStore.recoverPending(context)
    }
    private fun install(context: Context, result: AuthResult, extras: List<String>, legacyScope: String? = null) {
        check(LoginPolicy.editionMatches(result.session, com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION) &&
            LoginPolicy.allowedUrls(listOf(result.hubUrl), com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION).isNotEmpty()) {
            "此 Hub 属于其他版本，请使用对应的开发版或内测版登录"
        }
        _state.value = LoginState.Checking
        try {
            RemoteStore.saveSession(context, result.hubUrl, result.token, result.session,
                LoginPolicy.allowedUrls(extras, com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION), legacyScope)
            BotTeamStore.resetAccountMemory()
            RemoteSessionCache.clearMemory()
            Preconfigure.onPaired(context)
        } catch (e: Exception) {
            _state.value = LoginState.SignedOut(e.message ?: "登录身份未能保存，请重试")
            throw e
        }
    }
    private suspend fun validateExisting(context: Context) = mutex.withLock {
        _state.value = LoginState.Checking
        val captured = RemoteStore.connection(context)
        val cached = RemoteStore.session(context)
        if (captured.token.isBlank()) { clear(context); return@withLock }
        if (cached != null && cached.expiresAt in 1..System.currentTimeMillis()) { clear(context, "登录已过期，请重新登录"); return@withLock }
        var expectedConnection = captured
        try {
            val trusted = LoginPolicy.allowedUrls(listOf(captured.hub) + RemoteStore.extraHubs(context),
                com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION)
            check(trusted.isNotEmpty()) { "已保存的 Hub 地址不属于当前版本，请重新登录对应的内网或公网 Hub" }
            val preferred = RemoteApi.candidates(captured.hub).mapNotNull(LoginPolicy::normalizedOrigin).filter { it in trusted }
            val located = AuthApi.meTrusted((preferred + trusted).distinct(), captured.token) { guard(context, captured) }
            val verified = validateLoginCandidate(captured.token, System.currentTimeMillis(),
                read = { located.session },
                exchange = { AuthApi.exchange(located.origin, captured.token, deviceName()).let { ValidatedLogin(it.token, it.session) } },
                guard = { guard(context, captured) })
            if (cached != null) check(cached.userId == verified.session.userId && cached.issuer == verified.session.issuer) {
                "已保存的登录身份发生变化，请明确退出后重新登录"
            }
            val migrationScope = RemoteStore.legacyScope(context) ?: if (cached == null) RemoteStore.scopeHash(captured.hub.trimEnd('/') + "|" + captured.token) else null
            val extras = trusted.filter { it != located.origin }
            install(context, AuthResult(verified.token, verified.session, located.origin), extras, migrationScope)
            expectedConnection = RemoteStore.connection(context)
            if (migrationScope != null) {
                RemoteStore.migrateLegacyPreferences(context, listOf("vibelink_bot_team_store", "vibelink_remote_session_cache_v3", "vibelink_agent_cache"))
                GroupWorkStore.migrateLegacyOutbox(context, migrationScope)
                RemoteStore.completeLegacyMigration(context)
            }
            ready(context, verified.session)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (!RemoteStore.matches(context, expectedConnection)) return@withLock
            if (e is AuthHttpFailure && e.code == 401) clear(context, e.message)
            else if (LoginPolicy.transientFailure((e as? AuthHttpFailure)?.code, e is java.io.IOException) &&
                cached?.usableOffline(System.currentTimeMillis()) == true && LoginPolicy.editionMatches(cached, com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION)) {
                ready(context, cached, offline = true)
            } else _state.value = LoginState.SignedOut(e.message ?: "无法验证登录，请检查网络后重试")
        }
    }

    suspend fun loginBeta(context: Context, code: String) = withContext(Dispatchers.IO) { mutex.withLock {
        require(com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION) { "开发版请连接内网 Hub 登录" }
        val captured = RemoteStore.connection(context)
        val result = AuthApi.beta(code.trim(), deviceName())
        guard(context, captured)
        install(context, result, emptyList())
        ready(context, result.session)
    } }
    suspend fun loginCredential(context: Context, hub: String, token: String, extras: List<String> = emptyList()) = withContext(Dispatchers.IO) { mutex.withLock {
        val origin = LoginPolicy.allowedUrls(listOf(hub), com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION).firstOrNull()
            ?: error("请使用当前版本对应的内网或公网 Hub 登录")
        val captured = RemoteStore.connection(context)
        val verified = validateLoginCandidate(token, System.currentTimeMillis(),
            read = { AuthApi.me(origin, token) },
            exchange = { AuthApi.exchange(origin, token, deviceName()).let { ValidatedLogin(it.token, it.session) } },
            guard = { guard(context, captured) })
        val result = AuthResult(verified.token, verified.session, origin)
        install(context, result, LoginPolicy.allowedUrls(extras, com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION))
        ready(context, result.session)
    } }
    suspend fun logout(context: Context) = withContext(Dispatchers.IO) { mutex.withLock {
        val captured = RemoteStore.connection(context)
        try { if (captured.token.isNotBlank()) AuthApi.logout(captured.hub, captured.token) }
        finally { if (RemoteStore.matches(context, captured)) clear(context) }
    } }
    internal suspend fun confirmDesktop(context: Context, link: LoginQrLink, scan: DesktopScan, decision: String) = withContext(Dispatchers.IO) {
        val current = RemoteStore.connection(context)
        val ready = _state.value as? LoginState.Ready
        check(ready != null && LoginPolicy.mayApproveDesktop(RemoteApi.candidates(current.hub), scan.hub,
            ready.session.issuer, scan.issuer, ready.session.edition, scan.edition)) { "请先登录二维码所属 Hub 和对应版本，再确认桌面登录" }
        check(scan.expiresAt > System.currentTimeMillis()) { "二维码已过期，请在电脑上刷新" }
        guard(context, current)
        try { AuthApi.confirm(current.hub, current.token, link, decision); guard(context, current) }
        catch (e: AuthHttpFailure) { if (e.code == 401 && RemoteStore.matches(context, current)) clear(context, e.message); throw e }
    }
}

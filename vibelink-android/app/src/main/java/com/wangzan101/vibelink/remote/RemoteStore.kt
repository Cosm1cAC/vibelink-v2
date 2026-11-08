package com.wangzan101.vibelink.remote

import android.content.Context
import com.wangzan101.vibelink.settings.KeystoreKeyCipher
import org.json.JSONObject
import java.util.Base64

/** Current Hub identity. Session credentials are encrypted; old plaintext tokens are read only for migration. */
object RemoteStore {
    private const val PREF = "vibelink_remote"
    private val cipher by lazy { KeystoreKeyCipher("vibelink-client-sessions") }
    private var revision = 0L
    val DEFAULT_HUB: String get() = if (com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION) "https://hub.vibelink.cloud" else "http://192.168.137.1:18995"
    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    data class Connection(val hub: String, val token: String, val epoch: Long)

    @Synchronized fun hub(c: Context): String {
        val hub = prefs(c).getString("hub", "")?.takeIf { it.isNotBlank() } ?: DEFAULT_HUB
        RemoteApi.configureRoutes(hub, token(c), extraHubs(c))
        return hub
    }
    @Synchronized fun token(c: Context): String {
        val encrypted = prefs(c).getString("session_credential", null)
        return if (encrypted != null) runCatching { String(cipher.decrypt(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8) }.getOrDefault("")
        else prefs(c).getString("token", "").orEmpty()
    }
    @Synchronized fun connection(c: Context) = Connection(hub(c), token(c), revision)
    @Synchronized fun matches(c: Context, captured: Connection): Boolean = connection(c) == captured
    fun session(c: Context): ClientSession? = runCatching { prefs(c).getString("session", null)?.let { SessionJson.parse(JSONObject(it)) } }.getOrNull()
    fun legacyScope(c: Context): String? = prefs(c).getString("legacy_scope", null)
    fun completeLegacyMigration(c: Context) { check(prefs(c).edit().remove("legacy_scope").commit()) }
    fun hostName(c: Context): String = session(c)?.label ?: prefs(c).getString("host", "").orEmpty()
    fun betaLabel(c: Context): String = session(c)?.takeIf { it.edition == "beta" }?.label ?: prefs(c).getString("beta_label", "").orEmpty()
    fun extraHubs(c: Context): List<String> = prefs(c).getString("hubs", "").orEmpty().split('\n').filter { it.isNotBlank() }
    fun isPaired(c: Context): Boolean = session(c)?.usableOffline(System.currentTimeMillis()) == true && token(c).isNotBlank()
    fun accountScope(c: Context): String = session(c)?.let { scopeHash(it.subjectKey(hub(c))) } ?: "unverified"
    fun scopedPreferences(c: Context, name: String) = c.getSharedPreferences(name + "_" + accountScope(c), Context.MODE_PRIVATE)
    internal fun scopeHash(value: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    @Synchronized fun saveSession(c: Context, hub: String, token: String, session: ClientSession, extras: List<String> = emptyList(), legacyScope: String? = null) {
        require(token.isNotBlank() && session.userId.isNotBlank() && session.issuer.isNotBlank()) { "Hub 登录响应不完整，请升级电脑端" }
        val encrypted = Base64.getEncoder().encodeToString(cipher.encrypt(token.toByteArray()))
        check(prefs(c).edit().putString("hub", hub.trimEnd('/')).putString("session_credential", encrypted)
            .putString("session", SessionJson.encode(session).toString()).putString("hubs", extras.joinToString("\n"))
            .putString("legacy_scope", legacyScope)
            .putString("host", session.label).remove("token").remove("beta_code").remove("beta_label").commit()) { "登录身份保存失败" }
        revision++
        this.hub(c)
    }
    @Synchronized fun clearSession(c: Context) {
        try {
            check(prefs(c).edit().remove("session_credential").remove("session").remove("token").remove("beta_code").remove("legacy_scope")
                .remove("beta_label").remove("host").remove("hubs").commit()) { "退出状态未能保存，请释放存储空间后重试退出" }
        } finally {
            revision++
            hub(c)
        }
    }
    /** Compatibility for explicit manual pairing only; validation is still required before entering the app. */
    @Synchronized fun save(c: Context, hub: String, token: String) {
        prefs(c).edit().putString("hub", hub.trimEnd('/')).putString("token", token).remove("session_credential")
            .remove("session").remove("hubs").apply()
        revision++
        this.hub(c)
    }
    @Synchronized fun savePaired(c: Context, hub: String, token: String, link: PairLink, host: String) = save(c, hub, token)

    /** Only call after /me proved the pre-upgrade credential's subject; never on account switching. */
    @Synchronized fun migrateLegacyPreferences(c: Context, names: List<String>) {
        val scope = accountScope(c)
        check(scope != "unverified")
        val marks = c.getSharedPreferences("vibelink_account_migrations", Context.MODE_PRIVATE)
        for (name in names) {
            if (marks.contains(name)) continue
            val target = scopedPreferences(c, name)
            if (target.all.isEmpty()) {
                val edit = target.edit()
                c.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) -> when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
                check(edit.commit())
            }
            check(marks.edit().putString(name, scope).commit())
        }
    }
}

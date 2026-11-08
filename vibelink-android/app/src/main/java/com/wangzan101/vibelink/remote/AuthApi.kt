package com.wangzan101.vibelink.remote

import com.wangzan101.vibelink.net.Net
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class AuthHttpFailure(val code: Int) : IllegalStateException(when (code) {
    401 -> "登录已失效，请重新登录"
    403 -> "当前身份没有此权限"
    404, 405 -> "当前 Hub 尚未支持统一登录，请升级电脑端 Hub"
    400 -> "登录信息无效，请检查内测码或重新生成二维码"
    409 -> "二维码已被使用，请重新生成"
    410 -> "二维码已过期，请重新生成"
    else -> "Hub 暂时无法完成登录（$code）"
})

internal data class AuthResult(val token: String, val session: ClientSession, val hubUrl: String)
internal data class LocatedIdentity(val origin: String, val session: ClientSession)
internal data class DesktopScan(val hub: String, val status: String, val deviceName: String, val edition: String, val verificationCode: String, val expiresAt: Long, val issuer: String, val hubId: String)

internal object AuthApi {
    private val client = Net.client(OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS))
    private val jsonType = "application/json".toMediaType()
    private fun request(hub: String, path: String, token: String = "", body: JSONObject? = null): JSONObject {
        val builder = Request.Builder().url(hub.trimEnd('/') + path)
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        if (body != null) builder.post(body.toString().toRequestBody(jsonType))
        return client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw AuthHttpFailure(response.code)
            JSONObject(response.body?.string().orEmpty())
        }
    }
    private fun result(root: JSONObject, hub: String): AuthResult {
        check(root.optBoolean("ok", false)) { "Hub 未确认登录，请重试" }
        val token = root.optString("token")
        val session = SessionJson.parse(root.optJSONObject("session") ?: error("Hub 登录响应不完整，请升级电脑端"))
        check(token.isNotBlank() && session.usableOffline(System.currentTimeMillis()) && session.issuer.isNotBlank()) { "Hub 登录身份无效，请升级电脑端" }
        // A response cannot silently redirect an existing credential to another origin.
        return AuthResult(token, session, hub.trimEnd('/'))
    }
    fun me(hub: String, token: String): ClientSession {
        val root = request(hub, "/api/auth/me", token)
        check(root.optBoolean("ok", false)) { "Hub 未确认当前登录身份" }
        return SessionJson.parse(root.getJSONObject("session"))
    }
    /** Callers supply only previously stored, edition-filtered origins, never URLs from a new QR. */
    fun meTrusted(origins: List<String>, token: String, guard: () -> Unit = {}): LocatedIdentity {
        var failure: Throwable? = null
        for (origin in origins.distinct()) {
            guard()
            try {
                val identity = me(origin, token)
                guard()
                return LocatedIdentity(origin, identity)
            } catch (e: AuthHttpFailure) {
                if (e.code < 500) throw e
                failure = e
            } catch (e: java.io.IOException) { failure = e }
        }
        throw failure ?: IllegalStateException("没有可用的已配对 Hub 地址，请重新配置连接")
    }
    fun exchange(hub: String, token: String, device: String): AuthResult = result(request(hub, "/api/auth/session", token, JSONObject().put("deviceName", device)), hub)
    fun beta(code: String, device: String): AuthResult {
        val hub = "https://hub.vibelink.cloud"
        return result(request(hub, "/api/auth/beta", body = JSONObject().put("code", code).put("deviceName", device)), hub)
    }
    fun logout(hub: String, token: String) { request(hub, "/api/auth/logout", token, JSONObject()) }
    fun scan(link: LoginQrLink): DesktopScan {
        var failure: Throwable? = null
        for (hub in link.urls) {
            try {
                val root = request(hub, "/api/auth/qr/scan", body = JSONObject().put("id", link.id).put("scanSecret", link.scanSecret))
                return DesktopScan(hub, root.optString("status"), root.optString("deviceName"), root.optString("edition"), root.optString("verificationCode"), root.optLong("expiresAt"), root.optString("issuer"), root.optString("hubId"))
            } catch (e: java.io.IOException) { failure = e }
        }
        throw failure ?: IllegalStateException("无法连接二维码所属 Hub")
    }
    fun confirm(hub: String, token: String, link: LoginQrLink, decision: String) {
        require(decision in setOf("approve", "deny"))
        request(hub, "/api/auth/qr/confirm", token, JSONObject().put("id", link.id).put("scanSecret", link.scanSecret).put("decision", decision))
    }
}

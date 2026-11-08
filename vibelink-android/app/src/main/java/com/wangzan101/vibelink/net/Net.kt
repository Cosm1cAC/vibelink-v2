package com.wangzan101.vibelink.net

import android.content.Context
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.Executor

/**
 * 全局 HTTP 代理（App 内，不建 VPN）：
 *
 * - OkHttp：每次连接都问 [selector]，改完设置立即生效，不用重建 client；
 * - 内置浏览器：用 WebView 的 ProxyController 覆盖；
 * - 局域网 / hub / localhost 一律直连，别把 hub 自己的请求绕进代理。
 *
 * 这样手机上不用再装 CMFA 给 VibeLink 用；系统级全局代理（VpnService）是另一件事。
 */
object Net {
    @Volatile private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
        ProxyStore.reload(context)
        applyWebView(context)
    }

    fun refresh(context: Context) {
        ProxyStore.reload(context)
        applyWebView(context)
    }

    /** 局域网、回环、Tailscale 的 100.64/10、.local 都直连。 */
    fun isLocal(host: String): Boolean {
        if (host.equals("localhost", true) || host == "127.0.0.1" || host.endsWith(".local")) return true
        // hub 的公网入口（Cloudflare 隧道）也直连：热点外代理根本不可达，别把 hub 搭进去
        if (host.endsWith(".vibelink.cloud")) return true
        val p = host.split('.')
        if (p.size != 4) return false
        val a = p[0].toIntOrNull() ?: return false
        val b = p[1].toIntOrNull() ?: return false
        return a == 127 || a == 10 || (a == 192 && b == 168) ||
            (a == 172 && b in 16..31) || (a == 100 && b in 64..127)
    }

    /** hub 的地址永远直连：哪怕它是个公网域名（隧道），也不该被代理绕一圈，代理挂了就看不到会话。 */
    private fun hubHost(): String? = app?.let {
        runCatching { java.net.URI(com.wangzan101.vibelink.remote.RemoteStore.hub(it).trimEnd('/')).host }.getOrNull()
    }

    private val selector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val host = uri.host ?: return listOf(Proxy.NO_PROXY)
            if (isLocal(host) || host == hubHost()) return listOf(Proxy.NO_PROXY)
            return listOf(ProxyStore.proxy() ?: Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
    }

    fun client(builder: OkHttpClient.Builder = OkHttpClient.Builder()): OkHttpClient =
        builder
            .proxySelector(selector)
            .addInterceptor { chain ->
                val req = chain.request()
                val rawUrl = req.url.toString()
                val marker = "/api/llm/"
                val idx = rawUrl.indexOf(marker)
                if (idx < 0) {
                    return@addInterceptor chain.proceed(req)
                }
                val suffix = rawUrl.substring(idx) // e.g. /api/llm/v1/chat/completions
                val ctx = app ?: error("请先登录 VibeLink")
                val connection = com.wangzan101.vibelink.remote.RemoteStore.connection(ctx)
                val currentHub = connection.hub
                val latestToken = connection.token
                check(latestToken.isNotBlank() && ctx.let {
                    com.wangzan101.vibelink.remote.RemoteStore.session(it)?.usableOffline(System.currentTimeMillis())
                } == true) { "请先登录 VibeLink" }
                check(HubModelCredentialPolicy.matches(req.headers.values("Authorization"), req.headers.values("x-mesh-token"), latestToken)) {
                    "登录身份已变化，请重新发送这条消息"
                }
                val route = com.wangzan101.vibelink.remote.RemoteApi.routeSnapshot(currentHub, latestToken)
                val candidates = route.candidates
                var lastErr: IOException? = null
                for ((i, baseHub) in candidates.withIndex()) {
                    check(com.wangzan101.vibelink.remote.RemoteStore.matches(ctx, connection)) { "登录身份已变化，请重新发送这条消息" }
                    com.wangzan101.vibelink.remote.RemoteApi.requireRoute(route)
                    val targetUrl = baseHub.trimEnd('/') + suffix
                    val newReqBuilder = req.newBuilder().url(targetUrl)
                    if (latestToken.isNotBlank()) {
                        newReqBuilder.header("Authorization", "Bearer $latestToken")
                        newReqBuilder.header("x-mesh-token", latestToken)
                    }
                    try {
                        val resp = chain
                            .withConnectTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
                            .proceed(newReqBuilder.build())
                        try {
                            check(com.wangzan101.vibelink.remote.RemoteStore.matches(ctx, connection)) { "登录身份已变化，请重新发送这条消息" }
                            com.wangzan101.vibelink.remote.RemoteApi.rememberRoute(route, baseHub)
                        } catch (e: Exception) {
                            resp.close()
                            throw e
                        }
                        return@addInterceptor resp
                    } catch (e: IOException) {
                        lastErr = e
                        if (i == candidates.lastIndex) throw e
                    }
                }
                throw lastErr ?: IOException("All hub LLM endpoints unreachable")
            }
            .build()

    private fun applyWebView(context: Context) {
        runCatching { applyWebViewInner(context) }
    }

    private fun applyWebViewInner(context: Context) {
        val controller = ProxyController.getInstance()
        val sameThread = Executor { it.run() }
        val proxy = ProxyStore.proxy()
        if (proxy == null) {
            controller.clearProxyOverride(sameThread) {}
            return
        }
        val addr = proxy.address() as? InetSocketAddress ?: return
        val config = ProxyConfig.Builder()
            .addProxyRule("${addr.hostString}:${addr.port}")
            .addBypassRule("localhost")
            .addBypassRule("127.0.0.1")
            .addBypassRule("192.168.0.0/16")
            .addBypassRule("10.0.0.0/8")
            .addBypassRule("172.16.0.0/12")
            .addBypassRule("100.64.0.0/10")
            .build()
        controller.setProxyOverride(config, sameThread) {}
    }
}



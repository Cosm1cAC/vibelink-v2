package com.wangzan101.vibelink.net

import android.content.Context
import com.wangzan101.vibelink.remote.RemoteStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 手机端的代理设置：关闭 / 自动（从 vibelink hub 的设备列表里找出口）/ 手动。
 *
 * 「自动」不写死笔记本 IP：hub 里那台做出口的机器上报了 `proxy.enabled + proxy.server`，
 * 取它的局域网 IP（跳过 Tailscale 的 100.x）配端口即可。PC 上报的 server 甚至直接就是
 * `192.168.137.156:7897`。
 */
object ProxyStore {
    private const val PREF = "vibelink_proxy"

    enum class Mode { Off, Auto, Manual }

    @Volatile private var cached: Proxy? = null
    @Volatile private var cachedDesc: String = ""

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun mode(c: Context): Mode =
        if (com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION) Mode.Off
        else runCatching { Mode.valueOf(prefs(c).getString("mode", Mode.Off.name)!!) }.getOrDefault(Mode.Off)

    fun manualHost(c: Context): String = prefs(c).getString("host", "").orEmpty()
    fun manualPort(c: Context): Int = prefs(c).getInt("port", 7897)

    fun autoTarget(c: Context): Pair<String, Int>? {
        val h = prefs(c).getString("autoHost", "").orEmpty()
        val p = prefs(c).getInt("autoPort", 0)
        return if (h.isBlank() || p <= 0) null else h to p
    }

    fun setMode(c: Context, m: Mode) {
        prefs(c).edit().putString("mode", m.name).apply()
        reload(c)
    }

    fun setManual(c: Context, host: String, port: Int) {
        prefs(c).edit().putString("host", host.trim()).putInt("port", port).apply()
        reload(c)
    }

    /** 当前生效的代理；[Net] 每次连接前读这里（内存值，不碰磁盘）。 */
    fun proxy(): Proxy? = cached

    /** 给界面看的一行，比如 `192.168.137.156:7897`。 */
    fun describe(): String = cachedDesc

    fun reload(c: Context) {
        val p = when (mode(c)) {
            Mode.Off -> null
            Mode.Manual -> manualHost(c)
                .takeIf { it.isNotBlank() }
                ?.let { Proxy(Proxy.Type.HTTP, InetSocketAddress(it, manualPort(c))) }
            Mode.Auto -> autoTarget(c)?.let { (h, port) ->
                Proxy(Proxy.Type.HTTP, InetSocketAddress(h, port))
            }
        }
        cached = p
        cachedDesc = (p?.address() as? InetSocketAddress)?.let { "${it.hostString}:${it.port}" }.orEmpty()
    }

    private const val TAILSCALE_HUB = "http://100.98.206.126:18995"

    /**
     * 从 hub 的设备列表里找出口，写进 autoHost/autoPort。必须直连（不能用代理去查代理）。
     *
     * 先用默认 hub（热点内网）探路：通了就用出口那台机器的**局域网 IP**；
     * 不通再试 Tailscale 的 hub，通了就用它的 **100.x 地址**。这样出门只会开 Tailscale，
     * 代理地址自动换成能用的那个。
     */
    fun refreshAuto(c: Context): String {
        if (com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION) return "内测版直连公网"
        val token = RemoteStore.token(c)
        if (token.isBlank()) return "还没有 hub token"
        val probes = listOf(
            RemoteStore.hub(c).trimEnd('/') to true,
            TAILSCALE_HUB to false,
        )
        for ((base, lan) in probes) {
            val body = fetchDevices(base, token) ?: continue
            val found = parseExit(body, lan) ?: continue
            prefs(c).edit().putString("autoHost", found.first).putInt("autoPort", found.second).apply()
            reload(c)
            return "已找到 ${found.first}:${found.second}（${if (lan) "内网" else "Tailscale"}）"
        }
        return "连不上 hub（内网和 Tailscale 都不通）"
    }

    private fun fetchDevices(base: String, token: String): String? = runCatching {
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
        val url = "$base/api/devices?t=${URLEncoder.encode(token, "UTF-8")}"
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()

    private fun parseExit(body: String, lan: Boolean): Pair<String, Int>? {
        val devices = runCatching { JSONObject(body).optJSONArray("devices") }.getOrNull() ?: return null
        for (i in 0 until devices.length()) {
            val d = devices.optJSONObject(i) ?: continue
            if (!d.optBoolean("online")) continue
            val proxy = d.optJSONObject("proxy") ?: continue
            if (!proxy.optBoolean("enabled")) continue
            val (host, port) = splitHostPort(proxy.optString("server")) ?: continue
            val ip = when {
                // 走 Tailscale：出口那台上报的可能是 127.0.0.1 或者局域网 IP，
                // 两种都不能用，直接取它的 100.x 地址配同一个端口
                !lan -> pickIp(d, lan = false) ?: continue
                host == "127.0.0.1" || host == "localhost" -> pickIp(d, lan = true) ?: continue
                else -> host
            }
            return ip to port
        }
        return null
    }

    private fun splitHostPort(s: String): Pair<String, Int>? {
        val host = s.substringBeforeLast(':', "")
        val port = s.substringAfterLast(':', "").toIntOrNull() ?: return null
        return if (host.isBlank()) null else host to port
    }

    /** devices[].ips 里挑地址：lan=true 取 192.168/10/172.16-31，lan=false 取 Tailscale 的 100.64/10。 */
    private fun pickIp(device: JSONObject, lan: Boolean): String? {
        val ips = device.optJSONArray("ips") ?: return null
        var fallback: String? = null
        for (i in 0 until ips.length()) {
            val ip = ips.optString(i)
            val a = ip.substringBefore('.').toIntOrNull() ?: continue
            val b = ip.split('.').getOrNull(1)?.toIntOrNull() ?: 0
            if (!lan) {
                if (a == 100 && b in 64..127) return ip
                continue
            }
            if (a == 192 && b == 168) return ip
            if (a == 10 && fallback == null) fallback = ip
            if (a == 172 && b in 16..31 && fallback == null) fallback = ip
        }
        return fallback
    }
}

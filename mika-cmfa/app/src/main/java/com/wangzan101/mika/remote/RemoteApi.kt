package com.wangzan101.mika.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import com.wangzan101.mika.net.Net
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Thin client for the vibelink hub session plane. */
object RemoteApi {
    private val client = Net.client(OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS))
    private val jsonType = "application/json".toMediaType()

    data class Device(val id: String, val hostname: String, val online: Boolean)
    data class Session(
        val id: String,
        val title: String,
        val model: String,
        val updated: Long,
        val agent: String = "opencode",
        val device: String = "",
        val cwd: String = "",
        /** opencode 的 /api/session/active 里正在跑的会话（老 agent 不带这个字段，默认 false）。 */
        val running: Boolean = false,
    )
    data class AgentGroup(val id: String, val name: String, val note: String, val sessions: List<Session>)
    data class Message(
        val role: String,
        val text: String,
        val reasoning: String,
        val at: Long,
        val images: List<String> = emptyList(),
        /** opencode 的消息 id；本地乐观回显的消息为空。 */
        val id: String = "",
        /** 消息开始生成的时间（思考计时用）。 */
        val started: Long = 0L,
    )

    /** 不在热点时：先走 Tailscale（快），再退公网（慢）。 */
    private const val TAILSCALE_HUB = "http://100.98.206.126:18995"
    private const val PUBLIC_HUB = "https://hub.vibelink.cloud"

    private fun candidates(hub: String): List<String> {
        val h = hub.trimEnd('/')
        return if (h == RemoteStore.DEFAULT_HUB) {
            listOf(h, TAILSCALE_HUB, PUBLIC_HUB).distinct()
        } else {
            listOf(h)
        }
    }

    private fun <T> withFallback(hub: String, block: (String) -> T): T {
        val list = candidates(hub)
        var last: Throwable? = null
        for ((i, h) in list.withIndex()) {
            try {
                return block(h)
            } catch (e: java.io.IOException) {
                last = e
                if (i == list.lastIndex) throw e
            }
        }
        throw last ?: IllegalStateException("no hub")
    }

    private fun url(hub: String, path: String, token: String): String {
        val sep = if (path.contains("?")) "&" else "?"
        return hub.trimEnd('/') + path + sep + "t=" + URLEncoder.encode(token, "UTF-8")
    }

    private fun get(hub: String, token: String, path: String): JSONObject = withFallback(hub) { h ->
        val req = Request.Builder().url(url(h, path, token)).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${body.take(160)}")
            JSONObject(body)
        }
    }

    private fun post(hub: String, token: String, path: String, payload: JSONObject): JSONObject = withFallback(hub) { h ->
        val req = Request.Builder()
            .url(url(h, path, token))
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${body.take(160)}")
            JSONObject(body)
        }
    }

    fun devices(hub: String, token: String): List<Device> {
        val arr = get(hub, token, "/api/devices").optJSONArray("devices") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Device(o.optString("id"), o.optString("hostname"), o.optBoolean("online"))
        }
    }

    fun sessions(hub: String, token: String, device: String): List<Session> {
        val root = get(hub, token, "/api/sessions?device=$device")
        // hub returns the opencode envelope {"data":[...]} (or {"error":...})
        val arr = root.optJSONArray("data") ?: root.optJSONArray("sessions") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val model = o.optJSONObject("model")?.optString("id").orEmpty()
            val time = o.optJSONObject("time")?.optLong("updated") ?: 0L
            Session(o.optString("id"), o.optString("title"), model, time)
        }
    }

    /** 一次拿一台设备上所有 agent 的会话（hub 汇总 opencode / Codex / Claude / Antigravity）。 */
    fun allAgents(hub: String, token: String, device: String): List<AgentGroup> {
        val root = get(hub, token, "/api/sessions/all?device=$device")
        val arr = root.optJSONArray("agents") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val aid = o.optString("id")
            val ss = o.optJSONArray("sessions")
            val list = if (ss == null) emptyList() else (0 until ss.length()).mapNotNull { k ->
                val s = ss.optJSONObject(k) ?: return@mapNotNull null
                Session(
                    id = s.optString("id"),
                    title = s.optString("title"),
                    model = s.optString("model"),
                    updated = s.optLong("updated"),
                    agent = aid,
                    device = device,
                    cwd = s.optString("cwd"),
                    running = s.optBoolean("running"),
                )
            }
            AgentGroup(aid, o.optString("name").ifBlank { aid }, o.optString("note"), list)
        }
    }

    /** 递归找 content 里的图片：opencode 把工具输出（比如读取截图）放在 tool -> content[] 里。 */
    private fun collectImages(node: JSONObject?, out: MutableList<String>, depth: Int = 0) {
        if (node == null || depth > 5) return
        val uri = node.optString("uri").ifBlank { node.optString("url") }
        if (uri.startsWith("data:image")) out.add(uri)
        node.optJSONArray("content")?.let { arr ->
            for (k in 0 until arr.length()) collectImages(arr.optJSONObject(k), out, depth + 1)
        }
        node.optJSONObject("state")?.let { collectImages(it, out, depth + 1) }
    }
    /** 一页消息：messages 是「旧 → 新」，next 是更早一页的 cursor（没有更早时为 null）。 */
    data class MessagesPage(val messages: List<Message>, val next: String?)

    fun messagesPage(
        hub: String,
        token: String,
        device: String,
        agent: String,
        session: String,
        cursor: String? = null,
    ): MessagesPage {
        val base = "/api/sessions/$device/$agent/$session/messages"
        val path = if (cursor.isNullOrBlank()) {
            "$base?limit=120&order=desc"
        } else {
            "$base?cursor=" + URLEncoder.encode(cursor, "UTF-8")
        }
        val root = get(hub, token, path)
        val arr = root.optJSONArray("data") ?: return MessagesPage(emptyList(), null)
        val list = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val imgs = mutableListOf<String>()
            collectImages(o, imgs)
            val m = when (o.optString("type")) {
                "user" -> {
                    val created = o.optJSONObject("time")?.optLong("created") ?: 0L
                    Message("user", o.optString("text").ifBlank { o.optJSONObject("payload")?.optString("text").orEmpty() }, "", created, imgs, o.optString("id"), created)
                }
                "assistant" -> {
                    val sb = StringBuilder()
                    val rs = StringBuilder()
                    val content = o.optJSONArray("content")
                    if (content != null) {
                        for (k in 0 until content.length()) {
                            val part = content.optJSONObject(k) ?: continue
                            when (part.optString("type")) {
                                "text" -> sb.append(part.optString("text"))
                                "reasoning" -> rs.append(part.optString("text"))
                            }
                        }
                    }
                    val created = o.optJSONObject("time")?.optLong("created") ?: 0L
                    Message("assistant", sb.toString(), rs.toString(), o.optJSONObject("time")?.optLong("completed") ?: 0L, imgs, o.optString("id"), created)
                }
                else -> null
            }
            // 只有思考、没有正文也没有图片的气泡没有信息量，直接不显示
            m?.takeIf { it.text.isNotBlank() || it.images.isNotEmpty() }
        }
        // opencode 返回的是「新 → 旧」，界面要「旧 → 新」
        val next = root.optJSONObject("cursor")?.optString("next")?.takeIf { it.isNotBlank() }
        return MessagesPage(list.reversed(), next)
    }

    fun messages(hub: String, token: String, device: String, agent: String, session: String): List<Message> =
        messagesPage(hub, token, device, agent, session).messages

    fun prompt(hub: String, token: String, device: String, session: String, text: String) {
        post(hub, token, "/api/sessions/$device/$session/prompt", JSONObject().put("text", text))
    }

    fun interrupt(hub: String, token: String, device: String, session: String) {
        post(hub, token, "/api/sessions/$device/$session/interrupt", JSONObject())
    }
}









package com.wangzan101.vibelink.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import com.wangzan101.vibelink.net.Net
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Thin client for the vibelink hub session plane. */
object RemoteApi {
    private val client = Net.client(OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(45, TimeUnit.SECONDS))
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
    data class ToolCall(
        val name: String,
        val title: String,
        val file: String = "",
        val command: String = "",
        val diff: String = "",
    )

    data class FileRef(
        val path: String,
        val name: String,
        val mime: String = "",
        val size: Long = 0L,
    )

    data class WorkspaceEntry(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val mtime: Long,
        val children: List<WorkspaceEntry> = emptyList(),
    )

    data class WorkspaceTree(
        val cwd: String,
        val parent: String,
        val entries: List<WorkspaceEntry>,
    )

    data class WorkspaceFile(
        val path: String,
        val name: String,
        val size: Long,
        val mime: String,
        val isText: Boolean,
        val text: String = "",
        val base64: String = "",
        val truncated: Boolean = false,
    )

    data class GitChangedFile(
        val status: String,
        val path: String,
        val absPath: String,
    )

    data class WorkspaceGit(
        val cwd: String,
        val branch: String,
        val files: List<GitChangedFile>,
        val diff: String,
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
        val tools: List<ToolCall> = emptyList(),
        val files: List<FileRef> = emptyList(),
    )

    /** 不在热点时：先走局域网 / Tailscale（快），再退公网（慢）。 */
    private const val LAN_HUB = "http://192.168.5.2:18995"
    private const val TAILSCALE_HUB = "http://100.98.206.126:18995"
    private const val PUBLIC_HUB = "https://hub.vibelink.cloud"

    /** 扫码配对时桌面端给的其他地址（局域网 / tailnet / 公网），由 [RemoteStore] 读写。 */
    @Volatile
    var extraHubs: List<String> = emptyList()

    /** 最近一次成功连通的 Hub 地址：优先直连它，避免主配置地址（如未开热点时的 192.168.137.1）每次都白白等待超时。 */
    @Volatile
    var activeHub: String = ""

    /**
     * 先试最近连通的地址与配置地址：
     * - 公网内测版 (IS_BETA_EDITION = true)：仅使用公网 Hub 与用户自己配对的地址，绝不暴露开发者个人内网/Tailscale IP。
     * - 本地开发版 (IS_BETA_EDITION = false)：走热点/局域网/Tailscale，不退回公网内测 Hub。
     */
    fun candidates(hub: String): List<String> {
        val raw = if (com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION) {
            listOf(activeHub, hub.trimEnd('/')) + extraHubs + listOf(PUBLIC_HUB)
        } else {
            listOf(activeHub, hub.trimEnd('/')) + extraHubs + listOf(LAN_HUB, TAILSCALE_HUB)
        }
        return raw
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()
    }

    /** 内测用户使用邀请码（如 VLK-BETA-01）一键激活免费 DeepSeek 模型与内测席位。 */
    fun activateBetaInvite(code: String, deviceName: String): JSONObject {
        val payload = JSONObject()
            .put("code", code.trim())
            .put("device", deviceName)
        return pairPost(PUBLIC_HUB, "/api/beta/activate", payload)
    }

    fun betaQuota(hub: String, token: String): JSONObject = get(hub, token, "/api/beta/quota")

    private fun <T> withFallback(hub: String, block: (String) -> T): T {
        val list = candidates(hub)
        var last: Throwable? = null
        for ((i, h) in list.withIndex()) {
            try {
                val res = block(h)
                activeHub = h
                return res
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

    /** 扫码后的报到结果：哪个地址连得上、电脑名。 */
    data class PairScan(val hub: String, val host: String)

    /** 配对接口用的短超时客户端：二维码里可能有连不上的地址（别的网段），别每个都卡 6 秒。 */
    private val pairClient = Net.client(OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS))

    private fun pairPost(hub: String, path: String, payload: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(hub.trimEnd('/') + path)
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        pairClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (resp.code == 404) error("这台电脑上的 vibelink-hub 太旧，不支持扫码配对，请先升级")
            if (!resp.isSuccessful) {
                val reason = json?.optString("error").orEmpty()
                error(if (reason.contains("invalid or expired")) "二维码已失效，请在电脑上刷新后重扫" else reason.ifBlank { "HTTP ${resp.code}" })
            }
            return json ?: error("hub 返回了无法解析的内容")
        }
    }

    /**
     * 第一步（扫到码，用户还没点「连接」）：按二维码里的顺序找一个连得上的地址，向 hub 报到。
     * 只校验密钥、不消耗它，桌面端会显示「已扫码，请在手机上确认」。
     */
    fun pairScan(link: PairLink, deviceName: String): PairScan {
        var last: Throwable? = null
        for (h in link.urls) {
            try {
                val r = pairPost(h, "/api/pair/scan", JSONObject().put("code", link.secret).put("name", deviceName))
                return PairScan(h, r.optString("host").ifBlank { link.host })
            } catch (e: java.io.IOException) {
                last = e // 这个地址连不上，换下一个
            }
        }
        throw java.io.IOException("连不上这台电脑（试了 ${link.urls.size} 个地址）：手机和电脑需要在同一网络或同一 tailnet", last)
    }

    /** 第二步（用户点了「连接」）：用一次性密钥换本机专用的 client token。 */
    fun pairConfirm(hub: String, link: PairLink, deviceName: String): String {
        val r = pairPost(hub, "/api/pair", JSONObject().put("code", link.secret).put("name", deviceName))
        return r.optString("token").takeIf { it.isNotBlank() } ?: error("hub 没有返回 token")
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
    private fun extractMentionedFiles(text: String, out: MutableList<FileRef>) {
        // 识别 saved at `...` 或 Uploaded Workspace Files 中的绝对路径
        val regex = Regex("""`([A-Za-z]:\\[^`\r\n]+|/[^`\r\n]+\.[A-Za-z0-9]{1,8})`""")
        for (m in regex.findAll(text)) {
            val p = m.groupValues[1].trim()
            if (p.length > 4 && out.none { it.path == p }) {
                val name = p.substringAfterLast('\\').substringAfterLast('/')
                out.add(FileRef(path = p, name = name))
            }
        }
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
        if (agent == "deepseek") {
            val messages = root.optJSONArray("messages") ?: return MessagesPage(emptyList(), null)
            return MessagesPage((0 until messages.length()).mapNotNull { i ->
                val o = messages.optJSONObject(i) ?: return@mapNotNull null
                Message(role = o.optString("role"), text = o.optString("text"), reasoning = o.optString("reasoning"), at = o.optLong("at"), id = o.optString("id"), started = o.optLong("started"))
            }, null)
        }
        val arr = root.optJSONArray("data") ?: return MessagesPage(emptyList(), null)
        val list = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val imgs = mutableListOf<String>()
            val tools = mutableListOf<ToolCall>()
            val files = mutableListOf<FileRef>()
            collectImages(o, imgs)
            val m = when (o.optString("type")) {
                "user" -> {
                    val created = o.optJSONObject("time")?.optLong("created") ?: 0L
                    val rawText = o.optString("text").ifBlank { o.optJSONObject("payload")?.optString("text").orEmpty() }
                    extractMentionedFiles(rawText, files)
                    val cleanText = rawText
                        .substringBefore("\n\n[Uploaded Attachments in Workspace]:")
                        .substringBefore("\n\n[Uploaded Workspace Files]:")
                        .trim()
                    Message("user", cleanText.ifBlank { rawText }, "", created, imgs, o.optString("id"), created, tools, files)
                }
                "assistant" -> {
                    val sb = StringBuilder()
                    val rs = StringBuilder()
                    val content = o.optJSONArray("content")
                    if (content != null) {
                        for (k in 0 until content.length()) {
                            val part = content.optJSONObject(k) ?: continue
                            when (part.optString("type")) {
                                "text" -> {
                                    val t = part.optString("text")
                                    // 过滤掉内部占位 `[调用工具: ...]` 当已有结构化 tool 块时避免重复
                                    if (!t.trim().startsWith("`[") || !t.trim().endsWith("]`")) {
                                        if (sb.isNotEmpty() && t.isNotBlank()) sb.append("\n")
                                        sb.append(t)
                                    }
                                }
                                "reasoning" -> {
                                    val r = part.optString("text").trim()
                                    if (rs.isNotEmpty() && r.isNotBlank()) rs.append("\n\n")
                                    rs.append(r)
                                }
                                "tool", "tool_invocation" -> {
                                    val name = part.optString("name").ifBlank { part.optString("tool").ifBlank { "Tool" } }
                                    val title = part.optString("title").ifBlank { name }
                                    val file = part.optString("file")
                                    val cmd = part.optString("command")
                                    val diff = part.optString("diff")
                                    tools.add(ToolCall(name = name, title = title, file = file, command = cmd, diff = diff))
                                    val stepNote = "🔧 正在调用工具：$title"
                                    if (!rs.contains(stepNote)) {
                                        if (rs.isNotEmpty()) rs.append("\n")
                                        rs.append(stepNote)
                                    }
                                }
                                "file" -> {
                                    val fpath = part.optString("path")
                                    val fname = part.optString("name").ifBlank { fpath.substringAfterLast('\\').substringAfterLast('/') }
                                    if (fpath.isNotBlank() && files.none { it.path == fpath }) {
                                        files.add(FileRef(path = fpath, name = fname, mime = part.optString("mime")))
                                    }
                                }
                            }
                        }
                    }
                    val finalText = sb.toString().trim()
                    extractMentionedFiles(finalText, files)
                    val created = o.optJSONObject("time")?.optLong("created") ?: 0L
                    Message(
                        role = "assistant",
                        text = finalText,
                        reasoning = rs.toString().trim(),
                        at = o.optJSONObject("time")?.optLong("completed") ?: 0L,
                        images = imgs,
                        id = o.optString("id"),
                        started = created,
                        tools = tools,
                        files = files,
                    )
                }
                else -> null
            }
            m?.takeIf {
                it.text.isNotBlank() || it.reasoning.isNotBlank() || it.images.isNotEmpty() || it.tools.isNotEmpty() || it.files.isNotEmpty()
            }
        }
        // opencode 返回的是「新 → 旧」，界面要「旧 → 新」
        val next = root.optJSONObject("cursor")?.optString("next")?.takeIf { it.isNotBlank() }
        return MessagesPage(mergeAssistantTurns(list.reversed()), next)
    }

    /**
     * 将同一轮用户提问下的连续多条 assistant 步骤（思考、工具调用、阶段提示、最终回答）合并为单条 assistant 消息，
     * 使得手机端像 DeepSeek 一样将整轮思考与工具调用全部收纳进顶部折叠区，仅直接展示最终回答。
     */
    private fun normTs(ts: Long): Long =
        if (ts in 1L..9_999_999_999L) ts * 1000L else ts

    private fun mergeAssistantTurns(chronological: List<Message>): List<Message> {
        if (chronological.isEmpty()) return chronological
        val out = mutableListOf<Message>()
        for (rawMsg in chronological) {
            val msg = rawMsg.copy(
                at = normTs(rawMsg.at),
                started = normTs(rawMsg.started),
            )
            val prev = out.lastOrNull()
            if (msg.role == "assistant" && prev != null && prev.role == "assistant") {
                val combinedReasoning = buildString {
                    if (prev.reasoning.isNotBlank()) append(prev.reasoning)
                    if (prev.text.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n")
                        append(prev.text)
                    }
                    if (msg.reasoning.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n")
                        append(msg.reasoning)
                    }
                }
                val finalText = if (msg.at == 0L) "" else msg.text
                val mergedFiles = (prev.files + msg.files).distinctBy { it.path }
                out[out.lastIndex] = prev.copy(
                    id = prev.id.ifBlank { msg.id },
                    text = finalText,
                    reasoning = combinedReasoning,
                    started = if (prev.started > 0L) prev.started else msg.started,
                    at = msg.at,
                    images = (prev.images + msg.images).distinct(),
                    tools = prev.tools + msg.tools,
                    files = mergedFiles,
                )
            } else if (msg.role == "assistant" && prev != null && prev.role == "user" && prev.at > 0L) {
                val effectiveStarted = if (
                    (msg.started == 0L || (msg.at > 0L && msg.started >= msg.at)) &&
                    (msg.at == 0L || msg.at - prev.at in 500L..3_600_000L)
                ) {
                    prev.at
                } else {
                    msg.started
                }
                out.add(msg.copy(started = effectiveStarted))
            } else {
                out.add(msg)
            }
        }
        return out
    }

    fun messages(hub: String, token: String, device: String, agent: String, session: String): List<Message> =
        messagesPage(hub, token, device, agent, session).messages

    fun prompt(
        hub: String,
        token: String,
        device: String,
        session: String,
        text: String,
        files: List<JSONObject> = emptyList(),
        agent: String = "opencode",
        cwd: String = "",
        botId: String = "",
        thinkingEffort: String = "",
        isolationMode: String = "",
    ): JSONObject {
        val body = JSONObject().put("text", text).put("agent", agent)
        if (cwd.isNotBlank()) body.put("cwd", cwd)
        if (botId.isNotBlank()) body.put("botId", botId)
        if (thinkingEffort.isNotBlank()) body.put("thinkingEffort", thinkingEffort)
        if (isolationMode.isNotBlank()) body.put("isolationMode", isolationMode)
        if (files.isNotEmpty()) body.put("files", org.json.JSONArray(files))
        return post(hub, token, "/api/sessions/$device/$session/prompt", body)
    }

    fun interrupt(hub: String, token: String, device: String, session: String, agent: String = "opencode") {
        val body = JSONObject().put("agent", agent)
        post(hub, token, "/api/sessions/$device/$session/interrupt", body)
    }

    fun createSession(
        hub: String,
        token: String,
        device: String,
        agent: String,
        cwd: String,
        text: String,
        files: List<JSONObject> = emptyList(),
        botId: String = "",
        thinkingEffort: String = "",
        isolationMode: String = "",
    ): Session {
        val body = JSONObject()
            .put("agent", agent)
            .put("cwd", cwd)
            .put("text", text)
        if (botId.isNotBlank()) body.put("botId", botId)
        if (thinkingEffort.isNotBlank()) body.put("thinkingEffort", thinkingEffort)
        if (isolationMode.isNotBlank()) body.put("isolationMode", isolationMode)
        if (files.isNotEmpty()) body.put("files", org.json.JSONArray(files))
        val res = post(hub, token, "/api/sessions/$device/create", body)
        val id = res.optString("id").ifBlank { error("创建会话失败: 未返回会话 ID") }
        return Session(
            id = id,
            title = res.optString("title").ifBlank { text.take(40).ifBlank { "新会话" } },
            model = res.optString("model"),
            updated = System.currentTimeMillis(),
            agent = res.optString("agent").ifBlank { agent },
            device = res.optString("device").ifBlank { device },
            cwd = res.optString("cwd").ifBlank { cwd },
            running = res.optBoolean("running", text.isNotBlank() || files.isNotEmpty()),
        )
    }

    private fun parseEntry(o: JSONObject): WorkspaceEntry {
        val childArr = o.optJSONArray("children")
        val children = if (childArr == null) emptyList() else (0 until childArr.length()).mapNotNull { k ->
            childArr.optJSONObject(k)?.let { parseEntry(it) }
        }
        return WorkspaceEntry(
            name = o.optString("name"),
            path = o.optString("path"),
            isDir = o.optBoolean("isDir"),
            size = o.optLong("size"),
            mtime = o.optLong("mtime"),
            children = children,
        )
    }

    fun workspaceTree(hub: String, token: String, device: String, cwd: String = "", depth: Int = 2): WorkspaceTree {
        val qCwd = URLEncoder.encode(cwd, "UTF-8")
        val root = get(hub, token, "/api/workspace/$device/tree?cwd=$qCwd&depth=$depth")
        val arr = root.optJSONArray("entries")
        val entries = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { parseEntry(it) }
        }
        return WorkspaceTree(
            cwd = root.optString("cwd").ifBlank { cwd },
            parent = root.optString("parent"),
            entries = entries,
        )
    }

    fun workspaceFile(hub: String, token: String, device: String, filePath: String): WorkspaceFile {
        val qPath = URLEncoder.encode(filePath, "UTF-8")
        val root = get(hub, token, "/api/workspace/$device/file?path=$qPath")
        return WorkspaceFile(
            path = root.optString("path").ifBlank { filePath },
            name = root.optString("name").ifBlank { filePath.substringAfterLast('\\').substringAfterLast('/') },
            size = root.optLong("size"),
            mime = root.optString("mime", "application/octet-stream"),
            isText = root.optBoolean("isText", true),
            text = root.optString("text"),
            base64 = root.optString("base64"),
            truncated = root.optBoolean("truncated", false),
        )
    }

    fun workspaceUpload(
        hub: String,
        token: String,
        device: String,
        cwd: String,
        files: List<JSONObject>,
        subdir: Boolean = false,
    ): List<FileRef> {
        val body = JSONObject()
            .put("cwd", cwd)
            .put("subdir", subdir)
            .put("files", org.json.JSONArray(files))
        val root = post(hub, token, "/api/workspace/$device/upload", body)
        val arr = root.optJSONArray("saved") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            FileRef(
                path = o.optString("path"),
                name = o.optString("name"),
                mime = o.optString("mime"),
                size = o.optLong("size"),
            )
        }
    }

    fun workspaceGit(hub: String, token: String, device: String, cwd: String): WorkspaceGit {
        val qCwd = URLEncoder.encode(cwd, "UTF-8")
        val root = get(hub, token, "/api/workspace/$device/git?cwd=$qCwd")
        val arr = root.optJSONArray("files")
        val files = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            GitChangedFile(
                status = o.optString("status"),
                path = o.optString("path"),
                absPath = o.optString("absPath"),
            )
        }
        return WorkspaceGit(
            cwd = root.optString("cwd").ifBlank { cwd },
            branch = root.optString("branch"),
            files = files,
            diff = root.optString("diff"),
        )
    }

    data class DesktopFrame(
        val width: Int,
        val height: Int,
        val imgWidth: Int,
        val imgHeight: Int,
        val cursorX: Int,
        val cursorY: Int,
        val jpegBase64: String,
        val at: Long,
        val unchanged: Boolean = false,
        val frameHash: String = "",
        val leaseActive: Boolean = false,
        val leaseHolder: String = "",
    )

    data class ScheduledTask(
        val id: String,
        val name: String,
        val agent: String,
        val cwd: String,
        val prompt: String,
        val cron: String,
        val enabled: Boolean,
        val lastRunAt: Long,
        val lastStatus: String,
        val botId: String = "",
        val nextRunAt: Long = 0L,
    )

    data class SkillPlugin(
        val name: String,
        val scope: String,
        val path: String,
        val description: String,
        val enabled: Boolean,
    )

    data class McpServerPlugin(
        val id: String,
        val name: String,
        val transport: String,
        val url: String,
        val description: String,
        val enabled: Boolean,
        val builtin: Boolean = false,
    )

    data class EngineStatus(
        val id: String,
        val name: String,
        val bin: String,
        val ready: Boolean,
    )

    data class PluginsInfo(
        val skills: List<SkillPlugin>,
        val engines: List<EngineStatus>,
        val mcpServers: List<McpServerPlugin> = emptyList(),
    )

    data class TerminalLineItem(
        val seq: Long,
        val stream: String,
        val text: String,
        val at: Long,
    )

    data class TerminalSessionInfo(
        val id: String,
        val title: String,
        val botId: String,
        val cwd: String,
        val shell: String,
        val lines: List<TerminalLineItem>,
        val lastExitCode: Int?,
        val updatedAt: Long,
    )

    data class CommandActivityItem(
        val id: String,
        val sessionId: String,
        val botId: String,
        val cwd: String,
        val command: String,
        val outputPreview: String,
        val exitCode: Int,
        val durationMs: Long,
        val at: Long,
    )

    data class TerminalListResult(
        val sessions: List<TerminalSessionInfo>,
        val activities: List<CommandActivityItem>,
    )

    data class WorktreeItem(
        val botId: String,
        val slug: String,
        val branch: String,
        val baseRepo: String,
        val worktreePath: String,
        val isGitWorktree: Boolean,
        val dirtyFiles: Int,
        val headCommit: String,
    )

    data class ApprovalRequestItem(
        val id: String,
        val botId: String,
        val botName: String,
        val toolName: String,
        val commandOrAction: String,
        val riskLevel: String,
        val reason: String,
        val status: String,
        val createdAt: Long,
        val decidedAt: Long,
        val decidedBy: String,
    )

    data class ApprovalsInfo(
        val mode: String, // "ask" | "auto" | "never"
        val pendingCount: Int,
        val requests: List<ApprovalRequestItem>,
    )

    data class VoiceTtsResult(
        val ok: Boolean,
        val speechText: String,
        val wasSummarized: Boolean,
        val audioBase64: String,
        val mime: String,
        val provider: String,
        val voiceName: String,
        val fallbackClientTts: Boolean,
    )

    data class VoiceCallTurnResult(
        val ok: Boolean,
        val sessionId: String,
        val replyText: String,
        val speechText: String,
        val wasSummarized: Boolean,
        val audioBase64: String,
        val fallbackClientTts: Boolean,
    )

    fun desktopFrame(
        hub: String,
        token: String,
        device: String,
        width: Int = 1280,
        lastHash: String = "",
    ): DesktopFrame {
        val hashQuery = if (lastHash.isNotBlank()) "&lastHash=" + URLEncoder.encode(lastHash, "UTF-8") else ""
        val root = get(hub, token, "/api/desktop/$device/frame?width=$width$hashQuery")
        val leaseObj = root.optJSONObject("lease")
        return DesktopFrame(
            width = root.optInt("width", 1920),
            height = root.optInt("height", 1080),
            imgWidth = root.optInt("imgWidth", 1280),
            imgHeight = root.optInt("imgHeight", 720),
            cursorX = root.optInt("cursorX", 0),
            cursorY = root.optInt("cursorY", 0),
            jpegBase64 = root.optString("jpegBase64"),
            at = root.optLong("at"),
            unchanged = root.optBoolean("unchanged", false),
            frameHash = root.optString("frameHash"),
            leaseActive = leaseObj?.optBoolean("active", false) ?: false,
            leaseHolder = leaseObj?.optString("holder").orEmpty(),
        )
    }

    fun desktopInput(hub: String, token: String, device: String, payload: JSONObject) {
        post(hub, token, "/api/desktop/$device/input", payload)
    }

    fun desktopLease(
        hub: String,
        token: String,
        device: String,
        action: String = "status",
        holder: String = "mobile-user",
        ttlMs: Long = 60_000L,
    ): JSONObject {
        val body = JSONObject()
            .put("action", action)
            .put("holder", holder)
            .put("ttlMs", ttlMs)
        return post(hub, token, "/api/desktop/$device/lease", body)
    }

    private fun parseTasks(arr: org.json.JSONArray?): List<ScheduledTask> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ScheduledTask(
                id = o.optString("id"),
                name = o.optString("name"),
                agent = o.optString("agent", "antigravity"),
                cwd = o.optString("cwd"),
                prompt = o.optString("prompt"),
                cron = o.optString("cron", "每 6 小时"),
                enabled = o.optBoolean("enabled", true),
                lastRunAt = o.optLong("lastRunAt", 0L),
                lastStatus = o.optString("lastStatus", "待命"),
                botId = o.optString("botId"),
                nextRunAt = o.optLong("nextRunAt", 0L),
            )
        }
    }

    fun schedulerList(hub: String, token: String, device: String): List<ScheduledTask> {
        val root = get(hub, token, "/api/scheduler/$device")
        return parseTasks(root.optJSONArray("tasks"))
    }

    fun schedulerAction(hub: String, token: String, device: String, payload: JSONObject): List<ScheduledTask> {
        val root = post(hub, token, "/api/scheduler/$device", payload)
        return parseTasks(root.optJSONArray("tasks"))
    }

    private fun parsePluginsRoot(root: JSONObject): PluginsInfo {
        val sArr = root.optJSONArray("skills")
        val skills = if (sArr == null) emptyList() else (0 until sArr.length()).mapNotNull { i ->
            val o = sArr.optJSONObject(i) ?: return@mapNotNull null
            SkillPlugin(
                name = o.optString("name"),
                scope = o.optString("scope"),
                path = o.optString("path"),
                description = o.optString("description"),
                enabled = o.optBoolean("enabled", true),
            )
        }
        val mArr = root.optJSONArray("mcpServers")
        val mcpServers = if (mArr == null) emptyList() else (0 until mArr.length()).mapNotNull { i ->
            val o = mArr.optJSONObject(i) ?: return@mapNotNull null
            McpServerPlugin(
                id = o.optString("id"),
                name = o.optString("name"),
                transport = o.optString("transport", "https"),
                url = o.optString("url"),
                description = o.optString("description"),
                enabled = o.optBoolean("enabled", true),
                builtin = o.optBoolean("builtin", false),
            )
        }
        val eArr = root.optJSONArray("engines")
        val engines = if (eArr == null) emptyList() else (0 until eArr.length()).mapNotNull { i ->
            val o = eArr.optJSONObject(i) ?: return@mapNotNull null
            EngineStatus(
                id = o.optString("id"),
                name = o.optString("name"),
                bin = o.optString("bin"),
                ready = o.optBoolean("ready", true),
            )
        }
        return PluginsInfo(skills = skills, engines = engines, mcpServers = mcpServers)
    }

    fun pluginsList(hub: String, token: String, device: String): PluginsInfo =
        parsePluginsRoot(get(hub, token, "/api/plugins/$device"))

    fun pluginsAction(hub: String, token: String, device: String, payload: JSONObject): PluginsInfo =
        parsePluginsRoot(post(hub, token, "/api/plugins/$device", payload))

    // ---------------- Phase P0: Hub Bots & Fleet Sync + Grok Bot Recipes ----------------

    fun botsList(hub: String, token: String): JSONObject = get(hub, token, "/api/bots")

    fun botsTemplates(hub: String, token: String): JSONObject = get(hub, token, "/api/bots/templates")

    fun botsApplyPreset(hub: String, token: String, preset: String = "grok_420"): JSONObject =
        post(hub, token, "/api/bots/apply-preset", JSONObject().put("preset", preset))

    fun botExportRecipe(hub: String, token: String, botId: String): JSONObject =
        get(hub, token, "/api/bots/" + URLEncoder.encode(botId, "UTF-8") + "/recipe")

    fun botImportRecipe(hub: String, token: String, payload: JSONObject): JSONObject =
        post(hub, token, "/api/bots/import-recipe", payload)

    fun botUpsert(hub: String, token: String, payload: JSONObject): JSONObject =
        post(hub, token, "/api/bots", payload)

    fun botDelete(hub: String, token: String, botId: String): JSONObject =
        post(hub, token, "/api/bots/" + URLEncoder.encode(botId, "UTF-8") + "/delete", JSONObject())

    fun botLearnSkill(hub: String, token: String, botId: String, payload: JSONObject): JSONObject =
        post(hub, token, "/api/bots/" + URLEncoder.encode(botId, "UTF-8") + "/skills/learn", payload)

    fun botMemoryAdd(
        hub: String,
        token: String,
        botId: String,
        fact: String,
        category: String = "general",
        topic: String = "",
        aliases: List<String> = emptyList(),
    ): JSONObject {
        val body = JSONObject()
            .put("fact", fact)
            .put("category", category)
            .put("source", "user")
            .put("topic", topic)
            .put("aliases", org.json.JSONArray(aliases))
        return post(hub, token, "/api/bots/" + URLEncoder.encode(botId, "UTF-8") + "/memories", body)
    }

    fun botMemoryDelete(hub: String, token: String, botId: String, memId: String): JSONObject {
        val b = URLEncoder.encode(botId, "UTF-8")
        val m = URLEncoder.encode(memId, "UTF-8")
        return post(hub, token, "/api/bots/$b/memories/$m/delete", JSONObject())
    }

    fun botGroupUpsert(hub: String, token: String, payload: JSONObject): JSONObject =
        post(hub, token, "/api/bot-groups", payload)

    fun botGroupMessages(hub: String, token: String, groupId: String): JSONObject =
        get(hub, token, "/api/bot-groups/" + URLEncoder.encode(groupId, "UTF-8") + "/messages")

    fun botGroupMessageAppend(hub: String, token: String, groupId: String, payload: JSONObject): JSONObject =
        post(hub, token, "/api/bot-groups/" + URLEncoder.encode(groupId, "UTF-8") + "/messages", payload)

    fun fleetAction(hub: String, token: String, payload: JSONObject): JSONObject =
        post(hub, token, "/api/fleet/action", payload)

    // ---------------- Phase P1: Interactive Terminal & Per-Bot Git Worktree ----------------

    private fun parseTerminalSession(o: JSONObject): TerminalSessionInfo {
        val lArr = o.optJSONArray("lines")
        val lines = if (lArr == null) emptyList() else (0 until lArr.length()).mapNotNull { i ->
            val l = lArr.optJSONObject(i) ?: return@mapNotNull null
            TerminalLineItem(
                seq = l.optLong("seq"),
                stream = l.optString("stream", "stdout"),
                text = l.optString("text"),
                at = l.optLong("at"),
            )
        }
        return TerminalSessionInfo(
            id = o.optString("id", "term-main"),
            title = o.optString("title", "工作区终端"),
            botId = o.optString("botId"),
            cwd = o.optString("cwd"),
            shell = o.optString("shell", "powershell"),
            lines = lines,
            lastExitCode = if (o.has("lastExitCode") && !o.isNull("lastExitCode")) o.optInt("lastExitCode") else null,
            updatedAt = o.optLong("updatedAt"),
        )
    }

    private fun parseCommandActivities(arr: org.json.JSONArray?): List<CommandActivityItem> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONObject(i) ?: return@mapNotNull null
            CommandActivityItem(
                id = a.optString("id"),
                sessionId = a.optString("sessionId"),
                botId = a.optString("botId"),
                cwd = a.optString("cwd"),
                command = a.optString("command"),
                outputPreview = a.optString("outputPreview"),
                exitCode = a.optInt("exitCode", 0),
                durationMs = a.optLong("durationMs", 0L),
                at = a.optLong("at"),
            )
        }
    }

    fun terminalList(hub: String, token: String, device: String): TerminalListResult {
        val root = get(hub, token, "/api/terminal/$device")
        val sArr = root.optJSONArray("sessions")
        val sessions = if (sArr == null) emptyList() else (0 until sArr.length()).mapNotNull { i ->
            sArr.optJSONObject(i)?.let { parseTerminalSession(it) }
        }
        val activities = parseCommandActivities(root.optJSONArray("activities"))
        return TerminalListResult(sessions = sessions, activities = activities)
    }

    fun terminalAction(hub: String, token: String, device: String, payload: JSONObject): JSONObject =
        post(hub, token, "/api/terminal/$device", payload)

    fun worktreeList(hub: String, token: String, device: String, cwd: String = ""): List<WorktreeItem> {
        val qCwd = URLEncoder.encode(cwd, "UTF-8")
        val root = get(hub, token, "/api/worktree/$device?cwd=$qCwd")
        val arr = root.optJSONArray("worktrees") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            WorktreeItem(
                botId = o.optString("botId"),
                slug = o.optString("slug"),
                branch = o.optString("branch"),
                baseRepo = o.optString("baseRepo"),
                worktreePath = o.optString("worktreePath"),
                isGitWorktree = o.optBoolean("isGitWorktree", true),
                dirtyFiles = o.optInt("dirtyFiles", 0),
                headCommit = o.optString("headCommit"),
            )
        }
    }

    fun worktreeAction(hub: String, token: String, device: String, payload: JSONObject): JSONObject =
        post(hub, token, "/api/worktree/$device", payload)

    // ---------------- Phase P2: Voice TTS & Real-Time Bot Voice Call ----------------

    fun voiceTts(
        hub: String,
        token: String,
        text: String,
        botId: String = "",
        summarize: Boolean = true,
    ): VoiceTtsResult {
        val body = JSONObject()
            .put("text", text)
            .put("botId", botId)
            .put("summarize", summarize)
        val root = post(hub, token, "/api/voice/tts", body)
        return VoiceTtsResult(
            ok = root.optBoolean("ok", true),
            speechText = root.optString("speechText").ifBlank { text },
            wasSummarized = root.optBoolean("wasSummarized", false),
            audioBase64 = root.optString("audioBase64"),
            mime = root.optString("mime", "audio/wav"),
            provider = root.optString("provider", "system"),
            voiceName = root.optString("voiceName", "system-default"),
            fallbackClientTts = root.optBoolean("fallbackClientTts", false),
        )
    }

    fun voiceCallTurn(
        hub: String,
        token: String,
        botId: String,
        text: String,
        device: String = "",
        sessionId: String = "",
    ): VoiceCallTurnResult {
        val body = JSONObject()
            .put("botId", botId)
            .put("text", text)
            .put("device", device)
            .put("sessionId", sessionId)
        val root = post(hub, token, "/api/voice/call-turn", body)
        val ttsObj = root.optJSONObject("tts")
        return VoiceCallTurnResult(
            ok = root.optBoolean("ok", true),
            sessionId = root.optString("sessionId").ifBlank { sessionId },
            replyText = root.optString("replyText"),
            speechText = ttsObj?.optString("speechText").orEmpty().ifBlank { root.optString("replyText") },
            wasSummarized = ttsObj?.optBoolean("wasSummarized", false) ?: false,
            audioBase64 = ttsObj?.optString("audioBase64").orEmpty(),
            fallbackClientTts = ttsObj?.optBoolean("fallbackClientTts", true) ?: true,
        )
    }

    // ---------------- Phase P3: Three-Tier Approval Gate ----------------

    private fun parseApprovalsRoot(root: JSONObject): ApprovalsInfo {
        val policyObj = root.optJSONObject("policy")
        val mode = policyObj?.optString("mode", "ask").orEmpty().ifBlank { "ask" }
        val rArr = root.optJSONArray("requests")
        val requests = if (rArr == null) emptyList() else (0 until rArr.length()).mapNotNull { i ->
            val o = rArr.optJSONObject(i) ?: return@mapNotNull null
            ApprovalRequestItem(
                id = o.optString("id"),
                botId = o.optString("botId"),
                botName = o.optString("botName"),
                toolName = o.optString("toolName"),
                commandOrAction = o.optString("commandOrAction"),
                riskLevel = o.optString("riskLevel", "medium"),
                reason = o.optString("reason"),
                status = o.optString("status", "pending"),
                createdAt = o.optLong("createdAt"),
                decidedAt = o.optLong("decidedAt"),
                decidedBy = o.optString("decidedBy"),
            )
        }
        return ApprovalsInfo(
            mode = mode,
            pendingCount = root.optInt("pendingCount", requests.count { it.status == "pending" }),
            requests = requests,
        )
    }

    fun approvalsList(hub: String, token: String): ApprovalsInfo =
        parseApprovalsRoot(get(hub, token, "/api/approvals"))

    fun approvalsPolicyUpdate(hub: String, token: String, mode: String): ApprovalsInfo {
        val body = JSONObject().put("mode", mode)
        post(hub, token, "/api/approvals/policy", body)
        return approvalsList(hub, token)
    }

    fun approvalDecide(
        hub: String,
        token: String,
        id: String,
        approved: Boolean,
        note: String = "",
    ): JSONObject {
        val body = JSONObject()
            .put("approved", approved)
            .put("decidedBy", "mobile-user")
            .put("note", note)
        return post(hub, token, "/api/approvals/" + URLEncoder.encode(id, "UTF-8") + "/decide", body)
    }

    fun cindyJoinLink(hub: String, token: String): JSONObject = get(hub, token, "/api/cindy/join-link")
}









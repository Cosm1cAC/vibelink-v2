package com.wangzan101.vibelink.ui

import android.content.Context
import com.wangzan101.vibelink.chat.ConversationStore
import com.wangzan101.vibelink.remote.RemoteApi
import com.wangzan101.vibelink.remote.RemoteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** 消息列表里的一个聊天对象：手机端 VibeLink，或者电脑端的某个 agent（opencode / codex / …）。 */
enum class AgentKind { Phone, Desktop }

data class AgentContact(
    val id: String,
    val name: String,
    val kind: AgentKind,
    val online: Boolean,
    val deviceCount: Int,
    val sessionCount: Int,
    /** 列表里的最后一条：VibeLink 是最近的本地对话标题，远程是最新的会话标题。 */
    val lastText: String,
    val lastAt: Long,
    /** 这个 agent 有会话正在跑（opencode 才有；老 agent 恒为 false）。 */
    val running: Boolean = false,
    /** 上次打开之后又有多少条会话更新过。 */
    val unread: Int = 0,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    /** 最新会话用的模型（opencode 才有，形如 provider/model）。 */
    val model: String? = null,
)

const val VIBELINK_AGENT_ID = "vibelink"

fun agentDisplayName(id: String): String = when (id) {
    VIBELINK_AGENT_ID -> "Mika"
    "opencode" -> "OpenCode（历史）"
    "opencode2" -> "OpenCode2"
    "gemini" -> "Gemini CLI"
    "deepseek" -> "DeepSeek"
    "codex" -> "Codex"
    "claude" -> "Claude Code"
    "antigravity" -> "Antigravity"
    else -> id
}

/**
 * 远程会话与消息的两级本地缓存（L1 内存 + L2 磁盘 SharedPreferences）：
 * - 解决公网/跨网下点进会话白屏等待问题，实现 0ms 首帧秒开 + 后台静默刷新 (Stale-While-Revalidate)
 */
object RemoteSessionCache {
    private const val PREF_NAME = "vibelink_remote_session_cache_v3"
    private const val KEY_SESSIONS_PREFIX = "sessions_"
    private const val KEY_MSGS_PREFIX = "msgs_"
    private const val KEY_CURSOR_PREFIX = "cursor_"
    private const val KEY_MSG_LRU = "msg_lru_ids"

    private val memSessions = ConcurrentHashMap<String, List<RemoteApi.Session>>()
    private val memMessages = ConcurrentHashMap<String, List<RemoteApi.Message>>()
    private val memNextCursor = ConcurrentHashMap<String, String>()

    private fun cacheKey(context: Context, key: String) = RemoteStore.accountScope(context) + ":" + key
    fun clearMemory() { memSessions.clear(); memMessages.clear(); memNextCursor.clear() }

    private fun prefs(context: Context) =
        RemoteStore.scopedPreferences(context, PREF_NAME)

    fun getCachedSessions(context: Context, engineId: String): List<RemoteApi.Session> {
        memSessions[cacheKey(context, engineId)]?.takeIf { it.isNotEmpty() }?.let { return it }
        val raw = prefs(context).getString(KEY_SESSIONS_PREFIX + engineId, null) ?: return emptyList()
        val parsed = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                RemoteApi.Session(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    model = o.optString("model"),
                    updated = o.optLong("updated"),
                    agent = o.optString("agent", engineId),
                    device = o.optString("device", "pc"),
                    cwd = o.optString("cwd"),
                    running = o.optBoolean("running", false),
                )
            }
        }.getOrDefault(emptyList())
        if (parsed.isNotEmpty()) {
            memSessions[cacheKey(context, engineId)] = parsed
        }
        return parsed
    }

    fun putCachedSessions(context: Context, engineId: String, sessions: List<RemoteApi.Session>) {
        if (sessions.isEmpty()) return
        val trimmed = sessions.take(60)
        memSessions[cacheKey(context, engineId)] = trimmed
        runCatching {
            val arr = JSONArray()
            trimmed.forEach { s ->
                arr.put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("title", s.title)
                        put("model", s.model)
                        put("updated", s.updated)
                        put("agent", s.agent)
                        put("device", s.device)
                        put("cwd", s.cwd)
                        put("running", s.running)
                    },
                )
            }
            prefs(context).edit().putString(KEY_SESSIONS_PREFIX + engineId, arr.toString()).apply()
        }
    }

    fun getCachedMessages(context: Context, sessionId: String): Pair<List<RemoteApi.Message>, String?> {
        if (sessionId.isBlank()) return emptyList<RemoteApi.Message>() to null
        memMessages[cacheKey(context, sessionId)]?.takeIf { it.isNotEmpty() }?.let { cached ->
            return cached to memNextCursor[cacheKey(context, sessionId)]?.takeIf { it.isNotBlank() }
        }
        val p = prefs(context)
        val raw = p.getString(KEY_MSGS_PREFIX + sessionId, null) ?: return emptyList<RemoteApi.Message>() to null
        val cursor = p.getString(KEY_CURSOR_PREFIX + sessionId, null)?.takeIf { it.isNotBlank() }
        val parsed = runCatching {
            val arr = JSONArray(raw)
            val nowMs = System.currentTimeMillis()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val msgId = o.optString("id")
                // 过滤掉曾因发送超时残留的本地乐观占位气泡
                if (msgId.startsWith("local-assistant-")) return@mapNotNull null
                val toolsArr = o.optJSONArray("tools")
                val tools = if (toolsArr == null) emptyList() else (0 until toolsArr.length()).mapNotNull { ti ->
                    val to = toolsArr.optJSONObject(ti) ?: return@mapNotNull null
                    RemoteApi.ToolCall(
                        name = to.optString("name"),
                        title = to.optString("title"),
                        file = to.optString("file"),
                        command = to.optString("command"),
                        diff = to.optString("diff"),
                    )
                }
                val filesArr = o.optJSONArray("files")
                val files = if (filesArr == null) emptyList() else (0 until filesArr.length()).mapNotNull { fi ->
                    val fo = filesArr.optJSONObject(fi) ?: return@mapNotNull null
                    RemoteApi.FileRef(
                        path = fo.optString("path"),
                        name = fo.optString("name"),
                        mime = fo.optString("mime"),
                        size = fo.optLong("size"),
                    )
                }
                val rawAt = o.optLong("at")
                val started = o.optLong("started")
                val safeAt = if (rawAt == 0L && (started <= 0L || nowMs - started > 60_000L)) {
                    started.coerceAtLeast(1L)
                } else {
                    rawAt
                }
                RemoteApi.Message(
                    role = o.optString("role"),
                    text = o.optString("text"),
                    reasoning = o.optString("reasoning"),
                    at = safeAt,
                    id = msgId,
                    started = started,
                    tools = tools,
                    files = files,
                )
            }
        }.getOrDefault(emptyList())
        if (parsed.isNotEmpty()) {
            memMessages[cacheKey(context, sessionId)] = parsed
            if (cursor != null) memNextCursor[cacheKey(context, sessionId)] = cursor
        }
        return parsed to cursor
    }

    fun putCachedMessages(
        context: Context,
        sessionId: String,
        messages: List<RemoteApi.Message>,
        nextCursor: String? = null,
    ) {
        if (sessionId.isBlank() || messages.isEmpty()) return
        val clean = messages.filterNot { it.id.startsWith("local-assistant-") }
        if (clean.isEmpty()) return
        val trimmed = clean.takeLast(60)
        memMessages[cacheKey(context, sessionId)] = trimmed
        if (nextCursor != null) {
            memNextCursor[cacheKey(context, sessionId)] = nextCursor
        }
        runCatching {
            val p = prefs(context)
            val arr = JSONArray()
            trimmed.forEach { m ->
                arr.put(
                    JSONObject().apply {
                        put("role", m.role)
                        put("text", m.text.take(12_000))
                        put("reasoning", m.reasoning.take(6_000))
                        put("at", m.at)
                        put("id", m.id)
                        put("started", m.started)
                        if (m.tools.isNotEmpty()) {
                            val tArr = JSONArray()
                            m.tools.take(20).forEach { t ->
                                tArr.put(
                                    JSONObject().apply {
                                        put("name", t.name)
                                        put("title", t.title)
                                        put("file", t.file)
                                        put("command", t.command.take(600))
                                        put("diff", t.diff.take(1500))
                                    },
                                )
                            }
                            put("tools", tArr)
                        }
                        if (m.files.isNotEmpty()) {
                            val fArr = JSONArray()
                            m.files.take(20).forEach { f ->
                                fArr.put(
                                    JSONObject().apply {
                                        put("path", f.path)
                                        put("name", f.name)
                                        put("mime", f.mime)
                                        put("size", f.size)
                                    },
                                )
                            }
                            put("files", fArr)
                        }
                    },
                )
            }
            val lruRaw = p.getString(KEY_MSG_LRU, "[]") ?: "[]"
            val lruList = runCatching {
                val a = JSONArray(lruRaw)
                (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
            }.getOrDefault(emptyList())
            val updatedLru = (listOf(sessionId) + lruList.filterNot { it == sessionId })
            val keep = updatedLru.take(36)
            val evicted = updatedLru.drop(36)
            val editor = p.edit()
                .putString(KEY_MSGS_PREFIX + sessionId, arr.toString())
                .putString(KEY_MSG_LRU, JSONArray(keep).toString())
            if (nextCursor != null) {
                editor.putString(KEY_CURSOR_PREFIX + sessionId, nextCursor)
            }
            evicted.forEach { oldId ->
                editor.remove(KEY_MSGS_PREFIX + oldId)
                editor.remove(KEY_CURSOR_PREFIX + oldId)
            }
            editor.apply()
        }
    }

    /**
     * 后台静默预热最常访问的会话消息（各 Bot 绑定的当前会话 + 各引擎最新会话），确保点击任意联系人或会话均 0ms 秒开。
     */
    suspend fun prewarmRecentSessions(context: Context) = withContext(Dispatchers.IO) {
        val hub = RemoteStore.hub(context)
        val token = RemoteStore.token(context)
        if (token.isBlank()) return@withContext
        val allSessions = BotModelPolicy.engines
            .flatMap { eng -> getCachedSessions(context, eng).take(3) }
        val botSessionIds = BotTeamStore.listBots(context, includeArchived = false)
            .mapNotNull { it.activeSessionId.takeIf { s -> s.isNotBlank() } }
            .toSet()
        val targets = (allSessions.filter { it.id in botSessionIds } + allSessions.take(4))
            .distinctBy { it.id }
            .take(6)
        for (s in targets) {
            if (memMessages.containsKey(cacheKey(context, s.id))) continue
            runCatching {
                val page = RemoteApi.messagesPage(hub, token, s.device.ifBlank { "pc" }, s.agent, s.id)
                if (page.messages.isNotEmpty()) {
                    putCachedMessages(context, s.id, page.messages, page.next)
                }
            }
        }
    }
}

/** 只读本地：VibeLink 这一条永远立刻可见，不用等 hub。 */
suspend fun loadVibeLinkContact(context: Context): AgentContact = withContext(Dispatchers.IO) {
    val list = ConversationStore.shared(context).list()
    val latest = list.maxByOrNull { it.updatedAt }
    val muted = AgentPrefs.muted(context, VIBELINK_AGENT_ID)
    val seen = AgentPrefs.ensureSeen(context, VIBELINK_AGENT_ID)
    AgentContact(
        id = VIBELINK_AGENT_ID,
        name = BotTeamStore.getBot(context, VIBELINK_AGENT_ID)?.name?.takeIf { it.isNotBlank() } ?: "Mika",
        kind = AgentKind.Phone,
        online = true,
        deviceCount = 1,
        sessionCount = list.size,
        lastText = latest?.title?.ifBlank { null } ?: "开始第一次对话",
        lastAt = latest?.updatedAt ?: 0L,
        unread = if (muted) 0 else list.count { it.updatedAt > seen },
        pinned = VIBELINK_AGENT_ID in AgentPrefs.pins(context),
        muted = muted,
    )
}

/** 只读 hub 上的远程 agent；连不上就是空列表，同时把每个引擎的会话列表缓存进 RemoteSessionCache。 */
suspend fun loadRemoteContacts(context: Context): List<AgentContact> = coroutineScope {
    val hub = RemoteStore.hub(context)
    val token = RemoteStore.token(context)
    if (token.isBlank()) return@coroutineScope emptyList()
    withContext(Dispatchers.IO) {
        val devices = runCatching { RemoteApi.devices(hub, token) }.getOrDefault(emptyList())
        val online = devices.filter { it.online }
        val groupsPerDevice = online.map { device ->
            async(Dispatchers.IO) {
                runCatching { RemoteApi.allAgents(hub, token, device.id) }.getOrNull()
                    ?: runCatching {
                        RemoteApi.sessions(hub, token, device.id)
                            .map { it.copy(device = device.id) }
                            .let { listOf(RemoteApi.AgentGroup("opencode", "opencode", "", it)) }
                    }.getOrNull()
                    .orEmpty()
            }
        }.awaitAll()

        val merged = LinkedHashMap<String, MutableList<RemoteApi.Session>>()
        val names = LinkedHashMap<String, String>()
        for (groups in groupsPerDevice) {
            for (g in groups) {
                merged.getOrPut(g.id) { mutableListOf() }.addAll(g.sessions)
                if (g.name.isNotBlank()) names.putIfAbsent(g.id, g.name)
            }
        }
        merged.map { (id, sessions) ->
            val sorted = sessions.sortedByDescending { it.updated }
            // 同步更新该引擎的会话列表缓存，进入聊天页或资料页时 0ms 直接读取
            RemoteSessionCache.putCachedSessions(context, id, sorted)
            val latest = sorted.firstOrNull()
            val muted = AgentPrefs.muted(context, id)
            val seen = AgentPrefs.ensureSeen(context, id)
            AgentContact(
                id = id,
                name = names[id] ?: id,
                kind = AgentKind.Desktop,
                online = true,
                deviceCount = sorted.map { it.device }.filter { it.isNotBlank() }.distinct().size,
                sessionCount = sorted.size,
                lastText = latest?.title?.ifBlank { null } ?: "暂无会话",
                lastAt = latest?.updated ?: 0L,
                running = sorted.any { it.running },
                unread = if (muted) 0 else sorted.count { it.updated > seen },
                pinned = id in AgentPrefs.pins(context),
                muted = muted,
                model = latest?.model?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/** 把本地对话和 hub 上的远程 agent 合成一份联系人列表，按最近活动排序。 */
suspend fun loadAgentContacts(context: Context): List<AgentContact> =
    (listOf(loadVibeLinkContact(context)) + loadRemoteContacts(context))
        .sortedWith(compareByDescending<AgentContact> { it.lastAt })

private const val AGENT_CACHE = "vibelink_agent_cache"
private const val AGENT_CACHE_KEY = "agents"

/** 上一次成功拿到的远程 agent。离线时列表里仍然保留，只是 online=false。 */
fun readAgentCache(context: Context): List<AgentContact> {
    val raw = RemoteStore.scopedPreferences(context, AGENT_CACHE)
        .getString(AGENT_CACHE_KEY, null) ?: return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            AgentContact(
                id = id,
                name = o.optString("name"),
                kind = AgentKind.Desktop,
                online = false,
                deviceCount = o.optInt("devices"),
                sessionCount = o.optInt("sessions"),
                lastText = o.optString("lastText"),
                lastAt = o.optLong("lastAt"),
                pinned = id in AgentPrefs.pins(context),
                muted = AgentPrefs.muted(context, id),
                model = o.optString("model").takeIf { it.isNotBlank() },
            )
        }
    }.getOrDefault(emptyList())
}

fun writeAgentCache(context: Context, list: List<AgentContact>) {
    val arr = JSONArray()
    list.filter { it.kind == AgentKind.Desktop }.forEach { c ->
        arr.put(
            JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("devices", c.deviceCount)
                put("sessions", c.sessionCount)
                put("lastText", c.lastText)
                put("lastAt", c.lastAt)
                put("model", c.model ?: "")
            },
        )
    }
    RemoteStore.scopedPreferences(context, AGENT_CACHE).edit()
        .putString(AGENT_CACHE_KEY, arr.toString()).apply()
}

/** 某个远程 agent 在线的会话，按最近更新排序；网络成功时自动回写缓存，网络失败时回退到本地缓存。 */
suspend fun loadAgentSessions(context: Context, agentId: String): List<RemoteApi.Session> =
    withContext(Dispatchers.IO) {
        val hub = RemoteStore.hub(context)
        val token = RemoteStore.token(context)
        if (token.isBlank()) return@withContext RemoteSessionCache.getCachedSessions(context, agentId)
        val devices = runCatching { RemoteApi.devices(hub, token) }.getOrDefault(emptyList())
        val groups = devices.filter { it.online }.map { device ->
            async(Dispatchers.IO) {
                runCatching { RemoteApi.allAgents(hub, token, device.id) }.getOrNull()
                    ?: runCatching {
                        RemoteApi.sessions(hub, token, device.id)
                            .map { it.copy(device = device.id) }
                            .let { listOf(RemoteApi.AgentGroup("opencode", "opencode", "", it)) }
                    }.getOrNull()
                    .orEmpty()
            }
        }.awaitAll()
        val fresh = groups.flatten()
            .filter { it.id == agentId }
            .flatMap { it.sessions }
            .sortedByDescending { it.updated }
        if (fresh.isNotEmpty()) {
            RemoteSessionCache.putCachedSessions(context, agentId, fresh)
            fresh
        } else {
            RemoteSessionCache.getCachedSessions(context, agentId)
        }
    }

package com.wangzan101.mika.ui

import android.content.Context
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.remote.RemoteApi
import com.wangzan101.mika.remote.RemoteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** 消息列表里的一个聊天对象：手机端 Mika，或者电脑端的某个 agent（opencode / codex / …）。 */
enum class AgentKind { Phone, Desktop }

data class AgentContact(
    val id: String,
    val name: String,
    val kind: AgentKind,
    val online: Boolean,
    val deviceCount: Int,
    val sessionCount: Int,
    /** 列表里的最后一条：Mika 是最近的本地对话标题，远程是最新的会话标题。 */
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

const val MIKA_AGENT_ID = "mika"

fun agentDisplayName(id: String): String = when (id) {
    MIKA_AGENT_ID -> "Mika"
    "opencode" -> "opencode"
    "codex" -> "codex"
    "claude" -> "Claude Code"
    "antigravity" -> "Antigravity"
    else -> id
}

/** 只读本地：Mika 这一条永远立刻可见，不用等 hub。 */
suspend fun loadMikaContact(context: Context): AgentContact = withContext(Dispatchers.IO) {
    val list = ConversationStore.shared(context).list()
    val latest = list.maxByOrNull { it.updatedAt }
    val muted = AgentPrefs.muted(context, MIKA_AGENT_ID)
    val seen = AgentPrefs.ensureSeen(context, MIKA_AGENT_ID)
    AgentContact(
        id = MIKA_AGENT_ID,
        name = "Mika",
        kind = AgentKind.Phone,
        online = true,
        deviceCount = 1,
        sessionCount = list.size,
        lastText = latest?.title?.ifBlank { null } ?: "开始第一次对话",
        lastAt = latest?.updatedAt ?: 0L,
        unread = if (muted) 0 else list.count { it.updatedAt > seen },
        pinned = MIKA_AGENT_ID in AgentPrefs.pins(context),
        muted = muted,
    )
}

/** 只读 hub 上的远程 agent；连不上就是空列表。 */
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
    (listOf(loadMikaContact(context)) + loadRemoteContacts(context))
        .sortedWith(compareByDescending<AgentContact> { it.lastAt })

private const val AGENT_CACHE = "mika_agent_cache"
private const val AGENT_CACHE_KEY = "agents"

/** 上一次成功拿到的远程 agent。离线时列表里仍然保留，只是 online=false。 */
fun readAgentCache(context: Context): List<AgentContact> {
    val raw = context.getSharedPreferences(AGENT_CACHE, Context.MODE_PRIVATE)
        .getString(AGENT_CACHE_KEY, null) ?: return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(raw)
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
    val arr = org.json.JSONArray()
    list.filter { it.kind == AgentKind.Desktop }.forEach { c ->
        arr.put(
            org.json.JSONObject().apply {
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
    context.getSharedPreferences(AGENT_CACHE, Context.MODE_PRIVATE).edit()
        .putString(AGENT_CACHE_KEY, arr.toString()).apply()
}

/** 某个远程 agent 在线的会话，按最近更新排序。 */suspend fun loadAgentSessions(context: Context, agentId: String): List<RemoteApi.Session> =
    withContext(Dispatchers.IO) {
        val hub = RemoteStore.hub(context)
        val token = RemoteStore.token(context)
        if (token.isBlank()) return@withContext emptyList()
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
        groups.flatten()
            .filter { it.id == agentId }
            .flatMap { it.sessions }
            .sortedByDescending { it.updated }
    }

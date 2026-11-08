package com.wangzan101.vibelink.ui

import android.content.Context
import com.wangzan101.vibelink.remote.GroupWork
import com.wangzan101.vibelink.remote.GroupWorkSubmission
import com.wangzan101.vibelink.remote.RemoteApi
import com.wangzan101.vibelink.remote.RemoteStore
import com.wangzan101.vibelink.remote.groupWorkFailure
import com.wangzan101.vibelink.remote.deliverGroupWorkRequests
import com.wangzan101.vibelink.remote.requireGroupWorkAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Only request delivery lives here. Model execution and its lifetime belong to the Hub. */
object GroupWorkStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _work = MutableStateFlow<Map<String, List<GroupWork>>>(emptyMap())
    val work = _work.asStateFlow()
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors = _errors.asStateFlow()
    private val _pending = MutableStateFlow<Map<String, Int>>(emptyMap())
    val pending = _pending.asStateFlow()
    private data class Account(val id: String, val hub: String, val token: String, val prefs: android.content.SharedPreferences)
    private fun capture(context: Context): Account {
        val hub = RemoteStore.hub(context).trimEnd('/')
        val token = RemoteStore.token(context)
        val id = java.security.MessageDigest.getInstance("SHA-256")
        .digest((hub + "|" + token).toByteArray())
        .take(12).joinToString("") { "%02x".format(it) }
        return Account(id, hub, token, context.applicationContext.getSharedPreferences("hub_group_work_" + id, Context.MODE_PRIVATE))
    }
    fun key(context: Context, groupId: String): String = capture(context).id + ":" + groupId
    private fun guard(context: Context, account: Account) = requireGroupWorkAccount(account.id, capture(context).id)

    fun recoverPending(context: Context) {
        val app = context.applicationContext
        val account = capture(app)
        scope.launch {
            val groups = account.prefs.all.filter { (key, value) -> key.startsWith("pending_") && value != "{}" }
                .keys.map { it.removePrefix("pending_") }
            groups.forEach { if (capture(app).id == account.id) refresh(app, it) }
        }
    }

    fun enqueue(context: Context, groupId: String, submission: GroupWorkSubmission, files: List<RemoteAttachment>) {
        val app = context.applicationContext
        val account = capture(app)
        check(account.hub.isNotBlank() && account.token.isNotBlank()) { "请先连接 Hub 并完成配对，草稿已保留" }
        val stateKey = account.id + ":" + groupId
        val filePayload = JSONArray(files.map { it.toJson() })
        require(files.size <= 8 && filePayload.toString().toByteArray().size <= 12 * 1024 * 1024) {
            "每次最多 8 个附件，编码后的总大小不得超过 12MB；请减少附件后重试"
        }
        val text = submission.text.ifBlank { "请查看并处理附件：" + files.joinToString("、") { it.name } }
        require(text.length <= 24000) { "任务内容不得超过 24000 字，请缩短后重试" }
        val payload = JSONObject().put("requestId", submission.requestId).put("text", text)
            .put("mode", submission.mode).put("botIds", JSONArray(submission.botIds))
            .put("delegationMode", submission.delegationMode).put("skillIds", JSONArray(submission.skillIds))
            .put("files", filePayload)
        submission.replyToWorkId?.let { payload.put("replyToWorkId", it) }
        synchronized(this) {
            guard(app, account)
            val outbox = JSONObject(account.prefs.getString("pending_$groupId", "{}") ?: "{}")
            outbox.put(submission.requestId, payload)
            // Commit before clearing the composer; process death must retain the exact request ID.
            check(account.prefs.edit().putString("pending_$groupId", outbox.toString()).commit()) { "无法保存待发送任务，请保留草稿后重试" }
            _pending.value = _pending.value + (stateKey to outbox.length())
        }
        scope.launch { if (capture(app).id == account.id) refresh(app, groupId) }
    }

    suspend fun refresh(context: Context, groupId: String) {
        val app = context.applicationContext
        val account = capture(app)
        val stateKey = account.id + ":" + groupId
        withContext(Dispatchers.IO) { mutex.withLock {
        try {
            guard(app, account)
            val hub = account.hub
            val token = account.token
            check(hub.isNotBlank() && token.isNotBlank()) { "请先连接 Hub 并完成配对" }
            val snapshot = synchronized(this) { JSONObject(account.prefs.getString("pending_$groupId", "{}") ?: "{}") }
            _pending.value = _pending.value + (stateKey to snapshot.length())
            // Probe support before synchronizing or delivering; no legacy inference fallback.
            var records = RemoteApi.groupWorkList(hub, token, groupId)
            guard(app, account)
            if (snapshot.length() > 0) {
                BotTeamStore.prepareGroupWork(app, groupId, hub, token) { guard(app, account) }
                guard(app, account)
                deliverGroupWorkRequests(snapshot.keys().asSequence().map { it to snapshot.getJSONObject(it) }.toList(),
                    send = { _, payload -> guard(app, account); RemoteApi.groupWorkSubmit(hub, token, groupId, payload); guard(app, account) },
                    acknowledge = { id ->
                    synchronized(this) {
                        guard(app, account)
                        val remaining = JSONObject(account.prefs.getString("pending_$groupId", "{}") ?: "{}")
                        remaining.remove(id)
                        check(account.prefs.edit().putString("pending_$groupId", remaining.toString()).commit())
                        _pending.value = _pending.value + (stateKey to remaining.length())
                    }
                })
                records = RemoteApi.groupWorkList(hub, token, groupId)
                guard(app, account)
            }
            val messages = RemoteApi.botGroupMessages(hub, token, groupId)
            guard(app, account)
            _work.value = _work.value + (stateKey to records)
            guard(app, account)
            BotTeamStore.mergeHubGroupMessages(app, groupId, messages) { guard(app, account) }
            _errors.value = _errors.value - stateKey
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (capture(app).id == account.id) _errors.value = _errors.value + (stateKey to groupWorkFailure(e))
        }
    } } }

    fun action(context: Context, groupId: String, workId: String, action: String) {
        val app = context.applicationContext
        val account = capture(app)
        scope.launch {
            try {
                guard(app, account)
                RemoteApi.groupWorkAction(account.hub, account.token, groupId, workId, action)
                guard(app, account)
                refresh(app, groupId)
            } catch (e: Exception) {
                if (capture(app).id == account.id) _errors.value = _errors.value + (account.id + ":" + groupId to groupWorkFailure(e))
            }
        }
    }
}

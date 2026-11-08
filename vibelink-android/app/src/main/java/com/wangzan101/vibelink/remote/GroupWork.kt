package com.wangzan101.vibelink.remote

/** Hub owns task execution. These records describe server state only. */
data class GroupWorkStep(
    val id: String, val botId: String, val kind: String, val status: String,
    val sessionId: String = "", val output: String = "", val error: String = "",
)

data class GroupWorkInput(val id: String, val text: String, val createdAt: Long, val status: String = "", val detail: String = "")

data class GroupWork(
    val id: String, val groupId: String, val status: String, val phase: String,
    val title: String, val text: String, val ownerBotId: String,
    val createdAt: Long, val updatedAt: Long, val error: String = "",
    val steps: List<GroupWorkStep> = emptyList(), val inputs: List<GroupWorkInput> = emptyList(),
    val result: String = "",
    val retryable: Boolean = false,
) {
    val active: Boolean get() = status !in setOf("completed", "failed", "cancelled")
    val statusLabel: String get() = when (status) {
        "queued" -> "排队中"
        "routing" -> "拆解中"
        "running" -> "执行中"
        "needs_input" -> "等待你的回复"
        "review" -> "验收中"
        "completed" -> "已完成"
        "failed" -> "失败"
        "cancelled" -> "已取消"
        else -> "未知状态（请更新应用）"
    }
}

/** Persist this ID before sending; ambiguous network failures must reuse it. */
data class GroupWorkSubmission(
    val requestId: String, val text: String, val mode: String = "auto",
    val botIds: List<String> = emptyList(), val replyToWorkId: String? = null,
    val delegationMode: String = "work", val skillIds: List<String> = emptyList(),
) {
    init {
        require(requestId.isNotBlank())
        require(mode in setOf("auto", "parallel", "relay"))
        require(delegationMode in setOf("work", "consult"))
        require(replyToWorkId == null || replyToWorkId.isNotBlank())
    }
}

internal fun groupWorkFailure(error: Throwable): String {
    val detail = error.message.orEmpty()
    return if (detail.contains("HTTP 404") || detail.contains("HTTP 405") || detail.contains("work API")) {
        "当前 Hub 尚未支持持久任务，请升级电脑端 Hub/Agent 后重试。"
    } else detail.ifBlank { "暂时无法连接 Hub，请稍后重试。" }
}

/** A lost response leaves the persisted ID pending. Never acknowledge before acceptance. */
internal fun <T> deliverGroupWorkRequests(
    requests: List<Pair<String, T>>, send: (String, T) -> Unit, acknowledge: (String) -> Unit,
) {
    requests.forEach { (id, payload) ->
        send(id, payload)
        acknowledge(id)
    }
}

internal fun requireGroupWorkAccount(expected: String, current: String) {
    check(expected == current) { "Hub 账号已切换，原请求保留在原账号中" }
}

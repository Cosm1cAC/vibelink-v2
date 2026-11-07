package com.wangzan101.vibelink.ui

import com.wangzan101.vibelink.remote.RemoteApi

/** A polling timeout is still pending; only an explicit answer completes the turn. */
data class BotTurnResult(val sessionId: String, val text: String, val pending: Boolean = false)

internal object BotTurnMessages {
    const val BACKGROUND_NOTICE = "（仍在后台持续执行中，可点击下方「穿透底层会话」查看进度）"
    private const val LEGACY_BACKGROUND_SUFFIX = "*(仍在后台持续执行中，可点击下方「穿透底层会话」查看完整过程)*"

    fun completedReply(message: RemoteApi.Message): String? =
        if (message.at > 0L) message.text.ifBlank { message.reasoning }.trim().takeIf { it.isNotEmpty() } else null

    fun finish(sessionId: String, message: RemoteApi.Message?): BotTurnResult {
        val reply = message?.let(::completedReply)
        return if (reply != null) BotTurnResult(sessionId, reply)
        else BotTurnResult(sessionId, BACKGROUND_NOTICE, pending = true)
    }

    fun update(current: TeamGroupMessage, remote: RemoteApi.Message, sessionId: String): TeamGroupMessage {
        val reply = completedReply(remote)
        return current.copy(
            text = reply ?: remote.text,
            reasoning = remote.reasoning.ifBlank { current.reasoning },
            startedAt = remote.started.takeIf { it > 0L } ?: current.startedAt,
            tools = remote.tools.ifEmpty { current.tools },
            linkedSessionId = sessionId,
            at = if (reply != null) maxOf(current.at, remote.at) else current.at,
            status = when {
                reply == null -> "thinking"
                BotTeamStore.extractNeedsInput(reply) != null -> "needs_input"
                else -> "done"
            },
        )
    }

    fun separateLegacyContent(message: TeamGroupMessage): TeamGroupMessage = message
}

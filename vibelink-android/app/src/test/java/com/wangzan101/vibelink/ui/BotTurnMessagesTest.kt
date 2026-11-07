package com.wangzan101.vibelink.ui

import com.wangzan101.vibelink.remote.RemoteApi
import org.junit.Assert.*
import org.junit.Test

class BotTurnMessagesTest {
    private val reasoning = "🔧 正在调用工具：read_file: screenshot.jpg\nThe screenshot reveals a significant UI element."
    private val tool = RemoteApi.ToolCall("read_file", "screenshot.jpg")
    private val pending = TeamGroupMessage(
        id = "msg_test", senderId = "bot_dev", senderName = "GPT",
        text = "", reasoning = reasoning, startedAt = 100L, at = 100L,
        tools = listOf(tool), linkedSessionId = "session", status = "thinking",
    )
    private fun remote(text: String = "", at: Long = 200L) = RemoteApi.Message(
        role = "assistant", text = text, reasoning = reasoning, at = at,
        id = "assistant", started = 100L, tools = listOf(tool),
    )

    @Test fun completedToolStepDoesNotPromoteReasoningToAnswer() {
        assertNull(BotTurnMessages.completedReply(remote()))
        assertNull(BotTurnMessages.completedReply(remote(" \n ")))
        val updated = BotTurnMessages.update(pending, remote(), "session")
        assertEquals("", updated.text)
        assertEquals(reasoning, updated.reasoning)
        assertEquals(listOf(tool), updated.tools)
        assertEquals("thinking", updated.status)
    }

    @Test fun timeoutKeepsProgressOutOfTheReplyAndRemainsPending() {
        for (message in listOf(null, remote(), remote(at = 0L))) {
            val result = BotTurnMessages.finish("session", message)
            assertTrue(result.pending)
            assertEquals(BotTurnMessages.BACKGROUND_NOTICE, result.text)
            assertFalse(result.text.contains("screenshot"))
            assertEquals("session", result.sessionId)
        }
    }

    @Test fun finalAnswerReplacesPendingStateAndKeepsReasoningSeparate() {
        val progress = BotTurnMessages.update(pending, remote(at = 0L), "session")
        val done = BotTurnMessages.update(progress, remote("按钮已默认隐藏。"), "session")
        assertEquals("按钮已默认隐藏。", done.text)
        assertEquals(reasoning, done.reasoning)
        assertEquals("done", done.status)
        assertEquals(100L, done.startedAt)
        assertEquals(200L, done.at)
        val result = BotTurnMessages.finish("session", remote(done.text))
        assertFalse(result.pending)
        assertEquals(done.text, result.text)
    }

    @Test fun streamingAnswerIsVisibleButDoesNotFinishTheTurn() {
        val message = remote("正在生成正式回答", at = 0L)
        assertNull(BotTurnMessages.completedReply(message))
        val updated = BotTurnMessages.update(pending, message, "session")
        assertEquals(message.text, updated.text)
        assertEquals("thinking", updated.status)
    }

    @Test fun onlyAnswerCanRequestUserInput() {
        val reasoningOnly = remote().copy(reasoning = "NEEDS INPUT: 这是思考中的草稿")
        assertEquals("thinking", BotTurnMessages.update(pending, reasoningOnly, "session").status)
        val reply = remote("已准备好修改。\nNEEDS INPUT: 是否发布？")
        assertEquals("needs_input", BotTurnMessages.update(pending, reply, "session").status)
    }

    @Test fun legacyCopiedReasoningIsRepairedOnReload() {
        val legacy = pending.copy(text = " $reasoning\n", status = "done")
        val repaired = BotTurnMessages.separateLegacyContent(legacy)
        assertEquals("", repaired.text)
        assertEquals(reasoning, repaired.reasoning)
        assertEquals("thinking", repaired.status)
        assertEquals(repaired, BotTurnMessages.separateLegacyContent(repaired))
    }

    @Test fun legacyTimeoutSummaryMovesBackToThinkingArea() {
        val progress = "🔧 已调用 13 步工具 · read_file\n💭 screenshot analysis"
        val legacy = pending.copy(
            text = "$progress\n\n*(仍在后台持续执行中，可点击下方「穿透底层会话」查看完整过程)*",
            reasoning = "", status = "done",
        )
        val repaired = BotTurnMessages.separateLegacyContent(legacy)
        assertEquals("", repaired.text)
        assertEquals(progress, repaired.reasoning)
        assertEquals("thinking", repaired.status)
    }

    @Test fun legacyRepairPreservesRealAnswersAndUserMessages() {
        val answer = pending.copy(text = "请点击 read_file 查看结果。", status = "done")
        val user = pending.copy(senderId = "user", text = reasoning, status = "done")
        assertEquals(answer, BotTurnMessages.separateLegacyContent(answer))
        assertEquals(user, BotTurnMessages.separateLegacyContent(user))
    }
}

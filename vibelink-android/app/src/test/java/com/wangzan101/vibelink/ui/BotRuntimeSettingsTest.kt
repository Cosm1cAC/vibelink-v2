package com.wangzan101.vibelink.ui

import org.junit.Assert.*
import org.junit.Test

class BotRuntimeSettingsTest {
    private fun bot() = BotFriend(
        id = "bot_sre", name = "运维官", templateId = "tpl_deepseek_sre", jobTitle = "运维", department = "基础设施",
        lifecycle = BotLifecycle.MANAGEMENT, engine = "codex", model = BotModelPolicy.DEEPSEEK_MODEL,
        rolePrompt = "保留原职责", cwd = "D:\\workspace", disabledTools = listOf("shell"),
        activeSessionId = "own-session", sessionIds = listOf("own-session", "older-session"),
        lastText = "原有摘要", lastAt = 123,
    )

    @Test fun modelChangeOnlyUpdatesModelAndDetachesIncompatibleAgentConversations() {
        val before = bot()
        val after = BotRuntimeSettings.selectModel(before, "", beta = false)
        assertEquals(before.copy(model = "", activeSessionId = "", sessionIds = emptyList()), after)
        assertEquals("codex", after.engine)
        assertEquals(before.copy(activeSessionId = "", sessionIds = emptyList()),
            BotRuntimeSettings.selectModel(after, BotModelPolicy.DEEPSEEK_MODEL, beta = false))
    }

    @Test fun selectingCurrentModelOrOnlySupportedAgentPreservesBoundSessions() {
        val before = bot()
        assertEquals(before, BotRuntimeSettings.selectModel(before, before.model, beta = false))
        assertEquals(before, BotRuntimeSettings.selectAgent(before, "codex"))
    }

    @Test fun betaOnlyAllowsDeepseekAndDevOffersOnlyCurrentlyAvailableModels() {
        assertEquals(listOf(BotModelPolicy.DEEPSEEK_MODEL), BotModelPolicy.availableModels(beta = true))
        assertEquals(listOf("", BotModelPolicy.DEEPSEEK_MODEL), BotModelPolicy.availableModels(beta = false))
        assertThrows(IllegalArgumentException::class.java) { BotRuntimeSettings.selectModel(bot(), "", beta = true) }
        for (model in listOf("claude", "gemini", "unknown")) {
            assertThrows(IllegalArgumentException::class.java) { BotRuntimeSettings.selectModel(bot(), model, beta = false) }
        }
    }

    @Test fun aModelIsNeverAcceptedAsAnAgent() {
        for (agent in listOf("deepseek", "gpt", "claude", "gemini", "opencode")) {
            assertThrows(IllegalArgumentException::class.java) { BotRuntimeSettings.selectAgent(bot(), agent) }
        }
        assertEquals(listOf("codex"), BotModelPolicy.engines)
    }

    @Test fun persistenceGuardClearsSessionsForRuntimeChangesButKeepsCompatibleProfileEdits() {
        val before = bot()
        val profileEdit = before.copy(name = "新名字", rolePrompt = "新职责")
        assertEquals(profileEdit, BotRuntimeSettings.detachIncompatibleSessions(before, profileEdit))
        val runtimeEdit = before.copy(model = "", activeSessionId = "should-not-survive")
        assertEquals(runtimeEdit.copy(activeSessionId = "", sessionIds = emptyList()),
            BotRuntimeSettings.detachIncompatibleSessions(before, runtimeEdit))
        assertEquals(before.copy(activeSessionId = "", sessionIds = emptyList()),
            BotRuntimeSettings.selectAgent(before.copy(engine = "legacy"), "codex"))
    }

    @Test fun pendingSessionCreationCannotBindAfterModelAgentOrWorkspaceChange() {
        val before = bot()
        assertTrue(BotRuntimeSettings.sameSessionRuntime(before, before.copy(name = "新名字")))
        for (changed in listOf(before.copy(model = ""), before.copy(engine = "other"),
            before.copy(device = "other-pc"), before.copy(cwd = "D:\\other"))) {
            assertFalse(BotRuntimeSettings.sameSessionRuntime(before, changed))
        }
    }
}

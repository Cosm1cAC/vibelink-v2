package com.wangzan101.vibelink.ui

import org.junit.Assert.*
import org.junit.Test

class BotRuntimeSettingsTest {
    private fun bot() = BotFriend(id = "bot_sre", name = "自定义运维官", templateId = "tpl_deepseek_sre",
        jobTitle = "运维", department = "基础设施", lifecycle = BotLifecycle.MANAGEMENT,
        engine = "opencode2", model = BotModelPolicy.DEEPSEEK_MODEL, rolePrompt = "用户原职责",
        cwd = "D:\\workspace", disabledTools = listOf("shell"), activeSessionId = "own-session",
        sessionIds = listOf("own-session", "older-session"), lastText = "原有摘要", lastAt = 123)

    @Test fun modelChangeSelectsCompatibleCliAndArchivesInsteadOfReusingSessions() {
        val before = bot()
        val after = BotRuntimeSettings.selectModel(before, "", beta = false)
        assertEquals("codex", after.engine)
        assertEquals("", after.activeSessionId)
        assertTrue(after.sessionIds.isEmpty())
        assertEquals(listOf(BotSessionLink("opencode2", before.model, "own-session"),
            BotSessionLink("opencode2", before.model, "older-session")), after.legacySessions)
        assertEquals(before.copy(engine = "codex", model = "", activeSessionId = "", sessionIds = emptyList(),
            legacySessions = after.legacySessions), after)
    }
    @Test fun sameCombinationPreservesBindingsAndProfileEditsPreserveAllHistory() {
        val before = bot()
        assertEquals(before, BotRuntimeSettings.selectModel(before, before.model, beta = false))
        assertEquals(before, BotRuntimeSettings.selectAgent(before, "opencode2", beta = false))
        val edit = before.copy(name = "用户新名字", rolePrompt = "新职责")
        assertEquals(edit, BotRuntimeSettings.detachIncompatibleSessions(before, edit))
    }
    @Test fun cliSelectionExplicitlyChoosesItsCompatibleDefaultModel() {
        for ((agent, model) in listOf("codex" to "", "opencode2" to BotModelPolicy.DEEPSEEK_MODEL,
            "claude" to "claude", "gemini" to "gemini")) {
            val chosen = BotRuntimeSettings.selectAgent(bot(), agent, beta = false)
            assertEquals(agent, chosen.engine)
            assertEquals(model, chosen.model)
            BotModelPolicy.requireBinding(chosen.engine, chosen.model, beta = false)
        }
    }
    @Test fun betaEnforcesDeepseekOpenCode2WhileDevSupportsAllOfficialCliFamilies() {
        assertEquals(listOf("opencode2"), BotModelPolicy.availableEngines(true))
        assertEquals(listOf("codex", "opencode2", "claude", "gemini"), BotModelPolicy.availableEngines(false))
        assertEquals(listOf("", BotModelPolicy.DEEPSEEK_MODEL, "claude", "gemini"), BotModelPolicy.availableModels(false))
        assertEquals(bot(), BotRuntimeSettings.selectAgent(bot(), "opencode2", beta = true))
        for (agent in listOf("codex", "claude", "gemini")) {
            assertThrows(IllegalArgumentException::class.java) { BotRuntimeSettings.selectAgent(bot(), agent, beta = true) }
        }
    }
    @Test fun incompatiblePairsAndModelNamesAsRunnersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BotModelPolicy.requireBinding("codex", BotModelPolicy.DEEPSEEK_MODEL, false) }
        assertThrows(IllegalArgumentException::class.java) { BotModelPolicy.requireBinding("claude", "", false) }
        for (agent in listOf("deepseek", "gpt", "opencode", "unknown")) {
            assertThrows(IllegalArgumentException::class.java) { BotRuntimeSettings.selectAgent(bot(), agent, false) }
        }
    }
    @Test fun pendingSessionCannotBindAfterAnyRuntimeComponentChanges() {
        val before = bot()
        assertTrue(BotRuntimeSettings.sameSessionRuntime(before, before.copy(name = "新名字")))
        for (changed in listOf(before.copy(model = ""), before.copy(engine = "codex"),
            before.copy(device = "other-pc"), before.copy(cwd = "D:\\other"))) {
            assertFalse(BotRuntimeSettings.sameSessionRuntime(before, changed))
        }
    }
}

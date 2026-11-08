package com.wangzan101.vibelink.ui

import org.junit.Assert.*
import org.junit.Test

class BotProfilePresentationTest {
    @Test fun deepseekBotWithNoConversationDoesNotBecomeGptBecauseAgentIsCodex() {
        assertEquals("DeepSeek", BotProfilePresentation.modelName("codex", "auto/deepseek-v4.1-flash", null))
    }

    @Test fun unrelatedLatestGptSessionCannotOverrideDeepseekBotBinding() {
        assertEquals("DeepSeek", BotProfilePresentation.modelName("codex", "auto/deepseek-v4.1-flash", "gpt-5.4"))
    }

    @Test fun explicitlySelectedGptSurvivesOldDeepseekSession() {
        assertEquals("GPT", BotProfilePresentation.modelName("codex", "", "auto/deepseek-v4.1-flash"))
    }

    @Test fun agentWithoutBindingOrSessionDoesNotImplyAnyModel() {
        assertEquals("尚未绑定模型", BotProfilePresentation.modelName("codex", null, null))
    }

    @Test fun profileWithNoBoundSessionsNeverShowsAnotherBotsHistory() {
        assertEquals(emptyList<String>(), BotProfilePresentation.sessions(listOf("other-gpt"), emptySet()) { it })
    }

    @Test fun botProfileOnlyShowsItsBoundAgentSessions() {
        assertEquals(listOf("own-deepseek"), BotProfilePresentation.sessions(listOf("other-gpt", "own-deepseek"), setOf("own-deepseek")) { it })
        assertEquals(listOf("other-gpt", "own-deepseek"), BotProfilePresentation.sessions(listOf("other-gpt", "own-deepseek"), null) { it })
    }
}

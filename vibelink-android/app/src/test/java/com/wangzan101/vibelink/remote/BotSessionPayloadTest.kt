package com.wangzan101.vibelink.remote

import org.junit.Assert.*
import org.junit.Test

class BotSessionPayloadTest {
    @Test fun deepseekBotSessionCarriesAgentModelAndBotBindingTogether() {
        val payload = RemoteApi.createSessionPayload("opencode2", "workspace", "inspect", botId = "ops",
            model = "auto/deepseek-v4.1-flash")
        assertEquals("opencode2", payload.getString("agent"))
        assertEquals("auto/deepseek-v4.1-flash", payload.getString("model"))
        assertEquals("ops", payload.getString("botId"))
        assertEquals("inspect", payload.getString("text"))
    }

    @Test fun subscriptionCliDefaultsAreSentWithoutInventingPinnedModelVersions() {
        for (agent in listOf("claude", "gemini")) {
            val payload = RemoteApi.createSessionPayload(agent, "workspace", "inspect", botId = "bot", model = agent)
            assertEquals(agent, payload.getString("agent"))
            assertEquals(agent, payload.getString("model"))
        }
    }

    @Test fun explicitlySelectedSubscriptionDefaultIsSentAsPresentEmptyModelForBoundBot() {
        val payload = RemoteApi.createSessionPayload("codex", "workspace", "", botId = "chief", model = "")
        assertTrue(payload.has("model"))
        assertEquals("", payload.getString("model"))
        assertEquals("chief", payload.getString("botId"))
    }
}

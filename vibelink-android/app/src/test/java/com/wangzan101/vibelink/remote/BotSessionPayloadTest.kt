package com.wangzan101.vibelink.remote

import org.junit.Assert.*
import org.junit.Test

class BotSessionPayloadTest {
    @Test fun deepseekBotSessionCarriesAgentModelAndBotBindingTogether() {
        val payload = RemoteApi.createSessionPayload("codex", "workspace", "inspect", botId = "ops",
            model = "auto/deepseek-v4.1-flash")
        assertEquals("codex", payload.getString("agent"))
        assertEquals("auto/deepseek-v4.1-flash", payload.getString("model"))
        assertEquals("ops", payload.getString("botId"))
        assertEquals("inspect", payload.getString("text"))
    }

    @Test fun explicitlySelectedSubscriptionDefaultIsSentAsPresentEmptyModelForBoundBot() {
        val payload = RemoteApi.createSessionPayload("codex", "workspace", "", botId = "chief", model = "")
        assertTrue(payload.has("model"))
        assertEquals("", payload.getString("model"))
        assertEquals("chief", payload.getString("botId"))
    }
}

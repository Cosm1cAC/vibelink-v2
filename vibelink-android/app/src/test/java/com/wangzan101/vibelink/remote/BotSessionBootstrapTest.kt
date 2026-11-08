package com.wangzan101.vibelink.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BotSessionBootstrapTest {
    @Test fun model_change_is_published_before_agent_session_is_created() {
        var serverModel = "gpt"
        val selectedModel = "auto/deepseek-v4.1-flash"
        val session = BotSessionBootstrap.create(
            synchronize = { serverModel = selectedModel },
            start = {
                check(serverModel == selectedModel) { "Bot model binding differs from requested model" }
                "codex-session"
            },
        )
        assertEquals("codex-session", session)
    }

    @Test fun failed_sync_does_not_create_a_session_using_the_old_model() {
        var started = false
        val failure = assertThrows(IllegalStateException::class.java) {
            BotSessionBootstrap.create(
                synchronize = { error("Hub rejected model binding") },
                start = { started = true },
            )
        }
        assertFalse(started)
        assertEquals("Hub rejected model binding", failure.message)
    }
}

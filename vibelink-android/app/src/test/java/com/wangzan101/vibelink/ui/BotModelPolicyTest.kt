package com.wangzan101.vibelink.ui

import org.junit.Assert.*
import org.junit.Test

class BotModelPolicyTest {
    @Test fun editionPolicyAssignsDispatcherAndWorkersIndependentlyOfRecipes() {
        for (template in BotTeamStore.TEMPLATES) {
            assertEquals("deepseek", BotModelPolicy.defaultEngine(template.id, beta = true))
            assertEquals(if (template.id == "tpl_grok_cos") "codex" else "deepseek", BotModelPolicy.defaultEngine(template.id, beta = false))
            assertFalse(template.defaultName in setOf("GPT", "Claude", "Gemini", "GLM", "DeepSeek"))
        }
        assertFalse(BotTemplate::class.java.declaredFields.any { it.name.contains("engine", true) || it.name.contains("model", true) })
    }

    @Test fun unsupportedEngineNeverSilentlyFallsBackToSubscriptionRuntime() {
        for (engine in listOf("antigravity", "claude", "opencode")) {
            assertThrows(IllegalArgumentException::class.java) { BotModelPolicy.requireEngine(engine) }
        }
    }
}

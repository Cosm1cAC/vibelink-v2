package com.wangzan101.vibelink.ui

import org.junit.Assert.*
import org.junit.Test

class BotModelPolicyTest {
    @Test fun editionPolicyAssignsDispatcherAndWorkersIndependentlyOfRecipes() {
        for (template in BotTeamStore.TEMPLATES) {
            assertEquals("opencode2", BotModelPolicy.defaultEngine(template.id, beta = true))
            assertEquals(BotModelPolicy.DEEPSEEK_MODEL, BotModelPolicy.defaultModel(template.id, beta = true))
            assertEquals(if (template.id == "tpl_grok_cos") "codex" else "opencode2", BotModelPolicy.defaultEngine(template.id, beta = false))
            assertEquals(if (template.id == "tpl_grok_cos") "" else BotModelPolicy.DEEPSEEK_MODEL, BotModelPolicy.defaultModel(template.id, beta = false))
            assertFalse(template.defaultName in setOf("GPT", "Claude", "Gemini", "GLM", "DeepSeek"))
        }
        assertFalse(BotTemplate::class.java.declaredFields.any { it.name.contains("engine", true) || it.name.contains("model", true) })
    }

    @Test fun unsupportedEngineNeverSilentlyFallsBackToSubscriptionRuntime() {
        for (engine in listOf("antigravity", "deepseek", "opencode")) {
            assertThrows(IllegalArgumentException::class.java) { BotModelPolicy.requireEngine(engine, beta = false) }
        }
    }

    @Test fun subscriptionModelFamiliesUseTheirOwnOfficialCliWithoutPinnedModelVersions() {
        for ((model, agent) in listOf("" to "codex", "gpt-test" to "codex", "claude" to "claude",
            "claude-test" to "claude", "gemini" to "gemini", "gemini-test" to "gemini",
            BotModelPolicy.DEEPSEEK_MODEL to "opencode2")) {
            assertEquals(agent, BotModelPolicy.engineForModel(model))
            BotModelPolicy.requireBinding(agent, model, beta = false)
        }
    }

    @Test fun newBotUsesTheSelectedRoleDefaultUntilUserChoosesAModel() {
        assertEquals(BotModelPolicy.DEEPSEEK_MODEL, BotModelPolicy.modelAfterTemplateChange("", false, "tpl_deepseek_sre", beta = false))
        assertEquals("", BotModelPolicy.modelAfterTemplateChange(BotModelPolicy.DEEPSEEK_MODEL, false, "tpl_grok_cos", beta = false))
    }

    @Test fun editingRolePreservesUserModelSelectionAndBetaRemainsDeepseek() {
        assertEquals("", BotModelPolicy.modelAfterTemplateChange("", true, "tpl_deepseek_sre", beta = false))
        assertEquals(BotModelPolicy.DEEPSEEK_MODEL, BotModelPolicy.modelAfterTemplateChange("", true, "tpl_deepseek_sre", beta = true))
    }
}

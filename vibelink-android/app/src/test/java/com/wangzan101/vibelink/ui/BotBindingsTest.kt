package com.wangzan101.vibelink.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BotBindingsTest {
    private val ops get() = BotTeamStore.TEMPLATES.first { it.defaultName == "运维官" }

    @Test fun roleSeedsUsePolicyAndDoNotMistakeCodexForGpt() {
        BotTeamStore.TEMPLATES.forEach { template ->
            val bot = BotTeamStore.buildBotFromTemplate(template)
            assertEquals("codex", bot.engine)
            assertEquals(BotModelPolicy.defaultModel(template.id), bot.model)
        }
    }

    @Test fun persistenceHubJsonAndRecipeExportPreserveModelNameAndAppearanceIndependently() {
        for (chosen in listOf(BotModelPolicy.DEEPSEEK_MODEL, "")) {
            val original = BotTeamStore.buildBotFromTemplate(ops).copy(
                name = "我的运维助手", model = chosen, avatar = "preset:gpt",
            )
            val payload = BotTeamStore.botToJson(original)
            val reopened = BotTeamStore.parseBotJson(JSONObject(payload.toString()))
            val imported = BotTeamStore.parseBotJson(JSONObject(BotTeamStore.exportBotRecipeJson(original)).getJSONObject("recipe"))
            val expected = BotModelPolicy.normalizeModel(chosen, ops.id)
            for (restored in listOf(reopened, imported)) {
                assertEquals("codex", restored.engine)
                assertEquals(expected, restored.model)
                assertEquals(original.name, restored.name)
                assertEquals("preset:gpt", restored.avatar)
            }
        }
    }

    @Test fun oldMissingModelGetsRoleDefaultWhileAnExplicitGptSelectionIsRetainedInDev() {
        val payload = BotTeamStore.botToJson(BotTeamStore.buildBotFromTemplate(ops))
        payload.remove("model")
        assertEquals(BotModelPolicy.DEEPSEEK_MODEL, BotTeamStore.parseBotJson(payload).model)
        payload.put("model", "")
        assertEquals(BotModelPolicy.normalizeModel("", ops.id), BotTeamStore.parseBotJson(payload).model)
    }
}

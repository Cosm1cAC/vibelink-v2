package com.wangzan101.vibelink.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BotBindingsTest {
    private val ops get() = BotTeamStore.TEMPLATES.first { it.defaultName == "运维官" }

    @Test fun roleSeedsUsePolicyAndDoNotMistakeCodexForGpt() {
        BotTeamStore.TEMPLATES.forEach { template ->
            val bot = BotTeamStore.buildBotFromTemplate(template)
            assertEquals(BotModelPolicy.engineForModel(bot.model), bot.engine)
            assertEquals(BotModelPolicy.defaultModel(template.id), bot.model)
        }
    }

    @Test fun persistenceHubJsonAndRecipeExportPreserveModelNameAndAppearanceIndependently() {
        for (chosen in listOf(BotModelPolicy.DEEPSEEK_MODEL, "")) {
            val original = BotTeamStore.buildBotFromTemplate(ops).copy(
                name = "我的运维助手", engine = BotModelPolicy.engineForModel(chosen), model = chosen, avatar = "preset:gpt",
            )
            val payload = BotTeamStore.botToJson(original)
            val reopened = BotTeamStore.parseBotJson(JSONObject(payload.toString()))
            val imported = BotTeamStore.parseBotJson(JSONObject(BotTeamStore.exportBotRecipeJson(original)).getJSONObject("recipe"))
            val expected = BotModelPolicy.normalizeModel(chosen, ops.id)
            for (restored in listOf(reopened, imported)) {
                assertEquals(BotModelPolicy.engineForModel(expected), restored.engine)
                assertEquals(expected, restored.model)
                assertEquals(original.name, restored.name)
                assertEquals("preset:gpt", restored.avatar)
            }
        }
    }

    @Test fun oldDeepseekCodexMigratesRuntimeOnlyAndRetainsOriginalConversationReferences() {
        val original = BotTeamStore.buildBotFromTemplate(ops).copy(engine = "codex", model = BotModelPolicy.DEEPSEEK_MODEL,
            name = "用户名字", rolePrompt = "用户自定义角色", activeSessionId = "legacy-session", sessionIds = listOf("legacy-session"))
        val migrated = BotTeamStore.parseBotJson(BotTeamStore.botToJson(original))
        assertEquals("opencode2", migrated.engine)
        assertEquals("", migrated.activeSessionId)
        assertTrue(migrated.sessionIds.isEmpty())
        assertEquals(listOf(BotSessionLink("codex", original.model, "legacy-session")), migrated.legacySessions)
        assertEquals(original.name, migrated.name)
        assertEquals(original.rolePrompt, migrated.rolePrompt)
        val reopened = BotTeamStore.parseBotJson(BotTeamStore.botToJson(migrated))
        assertEquals(migrated, reopened)
    }

    @Test fun explicitSubscriptionModelsSurviveReopenWithoutChangingUserRole() {
        for (model in listOf("", "gpt-custom", "claude", "claude-custom", "gemini", "gemini-custom")) {
            val original = BotTeamStore.buildBotFromTemplate(ops).copy(model = model,
                engine = BotModelPolicy.engineForModel(model), rolePrompt = "用户角色", name = "用户名字")
            val reopened = BotTeamStore.parseBotJson(BotTeamStore.botToJson(original))
            val expected = BotModelPolicy.normalizeModel(model, ops.id)
            assertEquals(expected, reopened.model)
            assertEquals(BotModelPolicy.engineForModel(expected), reopened.engine)
            assertEquals(original.rolePrompt, reopened.rolePrompt)
            assertEquals(original.name, reopened.name)
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

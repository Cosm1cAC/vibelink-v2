package com.wangzan101.vibelink.ui

import com.wangzan101.vibelink.BuildConfig

/** Roles, models and agent runtimes are distinct concepts. */
object BotModelPolicy {
    const val DEEPSEEK_MODEL = "auto/deepseek-v4.1-flash"
    val engines: List<String> get() = listOf("codex")
    val models: List<String> get() = if (BuildConfig.IS_BETA_EDITION) listOf(DEEPSEEK_MODEL) else listOf("", DEEPSEEK_MODEL)
    fun defaultEngine(templateId: String, beta: Boolean = BuildConfig.IS_BETA_EDITION): String = "codex"
    fun defaultModel(templateId: String, beta: Boolean = BuildConfig.IS_BETA_EDITION): String =
        if (!beta && templateId == "tpl_grok_cos") "" else DEEPSEEK_MODEL
    fun modelAfterTemplateChange(model: String, explicitlySelected: Boolean, templateId: String, beta: Boolean = BuildConfig.IS_BETA_EDITION): String =
        if (beta) DEEPSEEK_MODEL else if (explicitlySelected) model else defaultModel(templateId, beta)
    fun normalize(engine: String, templateId: String): String = "codex"
    fun normalizeModel(model: String, templateId: String, hasModel: Boolean = true): String =
        when {
            BuildConfig.IS_BETA_EDITION -> DEEPSEEK_MODEL
            !hasModel -> defaultModel(templateId)
            model.isEmpty() || model.startsWith("gpt-") -> model
            else -> DEEPSEEK_MODEL
        }
    fun requireEngine(engine: String): String {
        require(engine == "codex") { "Bot 会话必须绑定到官方 Codex agent" }; return engine
    }
    fun modelLabel(model: String): String = if (model == DEEPSEEK_MODEL) "DeepSeek · Magpie 路由组" else "GPT · 订阅"
    fun label(engine: String): String = "Codex · 官方 CLI"
}

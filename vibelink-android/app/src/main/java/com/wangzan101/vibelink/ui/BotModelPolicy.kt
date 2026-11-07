package com.wangzan101.vibelink.ui

import com.wangzan101.vibelink.BuildConfig

/** Runtime choices are independent of role recipes. */
object BotModelPolicy {
    val engines: List<String>
        get() = if (BuildConfig.IS_BETA_EDITION) listOf("deepseek") else listOf("codex", "deepseek")

    fun defaultEngine(templateId: String, beta: Boolean = BuildConfig.IS_BETA_EDITION): String =
        if (!beta && templateId == "tpl_grok_cos") "codex" else "deepseek"

    fun normalize(engine: String, templateId: String): String =
        engine.takeIf { it in engines } ?: defaultEngine(templateId)

    fun requireEngine(engine: String): String {
        require(engine in engines) { "当前版本不支持该模型，请选择 GPT / DeepSeek" }
        return engine
    }

    fun label(engine: String): String = when (engine) {
        "codex" -> "GPT · 官方 Codex CLI"
        "deepseek" -> "DeepSeek · API"
        else -> engine
    }
}

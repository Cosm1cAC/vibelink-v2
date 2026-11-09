package com.wangzan101.vibelink.ui

import com.wangzan101.vibelink.BuildConfig

/** Roles, models and agent runtimes are distinct concepts. */
object BotModelPolicy {
    const val DEEPSEEK_MODEL = "auto/deepseek-v4.1-flash"
    const val CLAUDE_MODEL = "claude"
    const val GEMINI_MODEL = "gemini"
    val engines: List<String> get() = availableEngines(BuildConfig.IS_BETA_EDITION)
    fun availableEngines(beta: Boolean): List<String> = if (beta) listOf("opencode2") else listOf("codex", "opencode2", "claude", "gemini")
    val models: List<String> get() = availableModels(BuildConfig.IS_BETA_EDITION)
    fun availableModels(beta: Boolean): List<String> = if (beta) listOf(DEEPSEEK_MODEL) else listOf("", DEEPSEEK_MODEL, CLAUDE_MODEL, GEMINI_MODEL)
    fun defaultEngine(templateId: String, beta: Boolean = BuildConfig.IS_BETA_EDITION): String = engineForModel(defaultModel(templateId, beta))
    fun engineForModel(model: String): String = when {
        model == DEEPSEEK_MODEL || model.startsWith("deepseek", true) -> "opencode2"
        model == CLAUDE_MODEL || model.startsWith("claude-", true) -> "claude"
        model == GEMINI_MODEL || model.startsWith("gemini-", true) -> "gemini"
        model.isEmpty() || model.startsWith("gpt-", true) -> "codex"
        else -> error("不支持的模型标识")
    }
    fun modelForEngine(engine: String): String = when (engine) {
        "codex" -> ""; "opencode2" -> DEEPSEEK_MODEL; "claude" -> CLAUDE_MODEL; "gemini" -> GEMINI_MODEL
        else -> error("不支持的 CLI 运行器")
    }
    fun defaultModel(templateId: String, beta: Boolean = BuildConfig.IS_BETA_EDITION): String =
        if (!beta && templateId == "tpl_grok_cos") "" else DEEPSEEK_MODEL
    fun modelAfterTemplateChange(model: String, explicitlySelected: Boolean, templateId: String, beta: Boolean = BuildConfig.IS_BETA_EDITION): String =
        if (beta) DEEPSEEK_MODEL else if (explicitlySelected) model else defaultModel(templateId, beta)
    fun normalize(engine: String, templateId: String): String =
        if (engine in engines) engine else defaultEngine(templateId)
    fun normalizeModel(model: String, templateId: String, hasModel: Boolean = true): String =
        when {
            BuildConfig.IS_BETA_EDITION -> DEEPSEEK_MODEL
            !hasModel -> defaultModel(templateId)
            model.isEmpty() || model.startsWith("gpt-") || model == CLAUDE_MODEL || model.startsWith("claude-") || model == GEMINI_MODEL || model.startsWith("gemini-") -> model
            else -> DEEPSEEK_MODEL
        }
    fun requireEngine(engine: String, beta: Boolean = BuildConfig.IS_BETA_EDITION): String {
        require(engine in availableEngines(beta)) { "当前版本不支持此 CLI 运行器" }; return engine
    }
    fun requireBinding(engine: String, model: String, beta: Boolean = BuildConfig.IS_BETA_EDITION) {
        requireEngine(engine, beta)
        require((!beta || model == DEEPSEEK_MODEL) && engineForModel(model) == engine) { "模型与 CLI 运行器不兼容" }
    }
    fun modelLabel(model: String): String = when (engineForModel(model)) {
        "opencode2" -> "DeepSeek · Magpie 路由组"; "claude" -> "Claude · 官方 CLI 默认模型"
        "gemini" -> "Gemini · 官方 CLI 默认模型"; else -> "GPT · 订阅"
    }
    fun label(engine: String): String = when (engine) {
        "opencode2" -> "OpenCode2 · DeepSeek"; "claude" -> "Claude Code · 官方 CLI"
        "gemini" -> "Gemini · 官方 CLI"; "codex" -> "Codex · 官方 CLI"; else -> engine
    }
}

package com.wangzan101.vibelink.ui

/** Values rendered on a Bot's profile, kept separate from its role and display name. */
internal object BotProfilePresentation {
    fun modelName(agent: String, configuredModel: String?, sessionModel: String?): String {
        val model = configuredModel ?: sessionModel?.takeIf { it.isNotBlank() }
        if (model != null) return when {
            model.isBlank() || model.startsWith("gpt-", ignoreCase = true) -> "GPT"
            model.contains("deepseek", ignoreCase = true) -> "DeepSeek"
            model.startsWith("claude", ignoreCase = true) -> "Claude"
            model.startsWith("gemini", ignoreCase = true) -> "Gemini"
            else -> model
        }
        return if (agent == "vibelink") "按手机助手配置" else "尚未绑定模型"
    }

    fun <T> sessions(all: List<T>, boundIds: Set<String>?, id: (T) -> String): List<T> =
        if (boundIds == null) all else all.filter { id(it) in boundIds }
}

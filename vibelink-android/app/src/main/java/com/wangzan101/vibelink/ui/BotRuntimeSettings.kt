package com.wangzan101.vibelink.ui

/** A runtime edit changes no role settings and never reuses an incompatible conversation. */
internal object BotRuntimeSettings {
    fun sameSessionRuntime(expected: BotFriend, current: BotFriend): Boolean =
        expected.engine == current.engine && expected.model == current.model &&
            expected.device == current.device && expected.cwd == current.cwd

    fun detachIncompatibleSessions(previous: BotFriend?, next: BotFriend): BotFriend =
        if (previous != null && (previous.engine != next.engine || previous.model != next.model))
            next.copy(activeSessionId = "", sessionIds = emptyList()) else next

    fun selectAgent(bot: BotFriend, agent: String): BotFriend =
        detachIncompatibleSessions(bot, bot.copy(engine = BotModelPolicy.requireEngine(agent)))

    fun selectModel(bot: BotFriend, model: String, beta: Boolean): BotFriend {
        require(model in BotModelPolicy.availableModels(beta)) { "当前版本不支持此运行模型" }
        return detachIncompatibleSessions(bot, bot.copy(model = model))
    }
}

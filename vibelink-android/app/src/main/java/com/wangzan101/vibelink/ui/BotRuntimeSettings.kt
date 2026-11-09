package com.wangzan101.vibelink.ui

/** A runtime edit changes no role settings and never reuses an incompatible conversation. */
internal object BotRuntimeSettings {
    fun sameSessionRuntime(expected: BotFriend, current: BotFriend): Boolean =
        expected.engine == current.engine && expected.model == current.model &&
            expected.device == current.device && expected.cwd == current.cwd

    fun detachIncompatibleSessions(previous: BotFriend?, next: BotFriend): BotFriend =
        if (previous != null && (previous.engine != next.engine || previous.model != next.model))
            next.copy(activeSessionId = "", sessionIds = emptyList(), legacySessions =
                (next.legacySessions + previous.legacySessions + (previous.sessionIds + previous.activeSessionId)
                    .filter { it.isNotBlank() }.map { BotSessionLink(previous.engine, previous.model, it) }).distinct()) else next

    fun selectAgent(bot: BotFriend, agent: String, beta: Boolean = com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION): BotFriend {
        BotModelPolicy.requireEngine(agent, beta)
        return selectModel(bot, if (BotModelPolicy.engineForModel(bot.model) == agent) bot.model else BotModelPolicy.modelForEngine(agent), beta)
    }

    fun selectModel(bot: BotFriend, model: String, beta: Boolean): BotFriend {
        val engine = BotModelPolicy.engineForModel(model)
        BotModelPolicy.requireBinding(engine, model, beta)
        return detachIncompatibleSessions(bot, bot.copy(model = model, engine = engine))
    }
}

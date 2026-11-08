package com.wangzan101.vibelink.remote

/** Connection-scoped fallback hints. Old requests cannot repopulate the next account's route. */
internal class HubRoutes {
    data class Snapshot(val epoch: Long, val hub: String, val token: String, val candidates: List<String>)
    private var epoch = 0L
    private var hub = ""
    private var token = ""
    private var extras = emptyList<String>()
    private var defaults = emptyList<String>()
    private var active = ""

    @Synchronized fun configure(hub: String, token: String, extras: List<String>, defaults: List<String>) {
        val normalizedHub = hub.trim().trimEnd('/')
        val normalizedExtras = extras.map { it.trim().trimEnd('/') }.filter { it.isNotBlank() }.distinct()
        if (this.hub != normalizedHub || this.token != token || this.extras != normalizedExtras || this.defaults != defaults) {
            epoch++
            this.hub = normalizedHub
            this.token = token
            this.extras = normalizedExtras
            this.defaults = defaults
            active = ""
        }
    }

    @Synchronized fun capture(configuredHub: String, credential: String): Snapshot {
        val normalized = configuredHub.trim().trimEnd('/')
        check(normalized == hub && credential == token) { "Hub 账号已切换，请重试" }
        return Snapshot(epoch, hub, token, (listOf(active, hub) + extras + defaults).filter { it.isNotBlank() }.distinct())
    }

    @Synchronized fun candidates(configuredHub: String): List<String> {
        val normalized = configuredHub.trim().trimEnd('/')
        return if (normalized == hub) (listOf(active, hub) + extras + defaults).filter { it.isNotBlank() }.distinct()
        else listOf(normalized).filter { it.isNotBlank() }
    }

    @Synchronized fun requireCurrent(snapshot: Snapshot) {
        check(snapshot.epoch == epoch && snapshot.hub == hub && snapshot.token == token) { "Hub 账号已切换，请重试" }
    }

    @Synchronized fun remember(snapshot: Snapshot, successfulHub: String) {
        requireCurrent(snapshot)
        check(successfulHub in snapshot.candidates)
        active = successfulHub
    }
}

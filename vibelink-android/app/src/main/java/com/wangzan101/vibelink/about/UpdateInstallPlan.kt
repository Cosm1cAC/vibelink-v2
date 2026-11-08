package com.wangzan101.vibelink.about

/** Installation continues with the cached APK after returning from system settings. */
class UpdateInstallPlan(initialPhase: Phase = Phase.Ready) {
    enum class Phase { Ready, Permission, Submitting, Waiting, Failed }
    enum class Action { PermissionSettings, Submit, None }
    var phase = initialPhase
        private set

    fun start(allowed: Boolean): Action {
        if (phase == Phase.Submitting || phase == Phase.Waiting) return Action.None
        phase = if (allowed) Phase.Submitting else Phase.Permission
        return if (allowed) Action.Submit else Action.PermissionSettings
    }

    fun returnedFromSettings(allowed: Boolean): Action {
        if (phase != Phase.Permission) return Action.None
        if (!allowed) return Action.None
        return start(true)
    }

    fun submitted() { phase = Phase.Waiting }
    fun failed() { phase = Phase.Failed }
}

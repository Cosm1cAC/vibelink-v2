package com.wangzan101.vibelink.about

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateInstallPlanTest {
    @Test fun recreated_permission_page_continues_when_access_was_granted() {
        val plan = UpdateInstallPlan(UpdateInstallPlan.Phase.Permission)
        assertEquals(UpdateInstallPlan.Action.Submit, plan.returnedFromSettings(true))
    }

    @Test fun permission_return_continues_installing_without_another_download() {
        val plan = UpdateInstallPlan()
        assertEquals(UpdateInstallPlan.Action.PermissionSettings, plan.start(false))
        assertEquals(UpdateInstallPlan.Action.Submit, plan.returnedFromSettings(true))
        assertEquals(UpdateInstallPlan.Action.None, plan.start(true))
    }

    @Test fun denied_permission_does_not_reopen_settings_in_a_loop() {
        val plan = UpdateInstallPlan()
        plan.start(false)
        assertEquals(UpdateInstallPlan.Action.None, plan.returnedFromSettings(false))
        assertEquals(UpdateInstallPlan.Phase.Permission, plan.phase)
    }

    @Test fun a_failed_or_cancelled_install_can_retry_the_existing_apk() {
        val plan = UpdateInstallPlan()
        plan.start(true)
        plan.submitted()
        assertEquals(UpdateInstallPlan.Action.None, plan.start(true))
        plan.failed()
        assertEquals(UpdateInstallPlan.Action.Submit, plan.start(true))
    }
}

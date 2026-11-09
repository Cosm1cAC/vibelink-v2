package com.wangzan101.vibelink.remote

import org.junit.Assert.*
import org.junit.Test

class DesktopApprovalActionTest {
    private val hub = "http://192.168.5.2:18995"
    private val scan = DesktopScan(hub, "scanned", "PC", "dev", "ABCD-1234", 9000, "hub_owner", "hub_owner")
    private val phone = ClientSession("owner", "Workspace", null, "dev", "phone-session", 9000, listOf("client"), "hub_owner")
    private fun next(session: ClientSession?, request: DesktopScan = scan, checking: Boolean = false, now: Long = 1000) =
        desktopApprovalAction(session, checking, listOf(hub), request, now)

    @Test fun signedOutPhoneHasAnExplicitLoginActionInsteadOfAnUnusableApproveButton() {
        assertEquals(DesktopApprovalAction.LoginPhone, next(null))
    }
    @Test fun phoneEnrollmentResumesSameDesktopRequestAndLogoutRequiresLoginAgain() {
        assertEquals(DesktopApprovalAction.LoginPhone, next(null))
        assertEquals(DesktopApprovalAction.Confirm, next(phone))
        assertEquals(DesktopApprovalAction.LoginPhone, next(null))
        assertEquals("ABCD-1234", scan.verificationCode)
    }
    @Test fun anotherIssuerEditionOrUntrustedOriginRequiresExplicitAccountSwitch() {
        assertEquals(DesktopApprovalAction.SwitchHub, next(phone.copy(issuer = "hub_other")))
        assertEquals(DesktopApprovalAction.SwitchHub, next(phone.copy(edition = "beta")))
        assertEquals(DesktopApprovalAction.SwitchHub, next(phone, scan.copy(hub = "http://192.168.5.99:18995")))
    }
    @Test fun expiredOrConsumedRequestRequiresNewQrEvenWhenPhoneIsSignedOut() {
        assertEquals(DesktopApprovalAction.RenewQr, next(null, scan.copy(expiresAt = 1000)))
        assertEquals(DesktopApprovalAction.RenewQr, next(phone, scan.copy(status = "consumed")))
        assertEquals(DesktopApprovalAction.RenewQr, next(phone, now = 9000))
    }
    @Test fun restoredIdentityMustFinishValidationBeforeAnyLoginDecision() {
        assertEquals(DesktopApprovalAction.Checking, next(null, checking = true))
        assertEquals(DesktopApprovalAction.Checking, next(phone, checking = true))
    }
    @Test fun expiredPhoneOrPrivilegeBearingSessionCannotApproveDesktop() {
        assertEquals(DesktopApprovalAction.LoginPhone, next(phone.copy(expiresAt = 1000)))
        assertEquals(DesktopApprovalAction.LoginPhone, next(phone.copy(permissions = listOf("admin"))))
    }
}

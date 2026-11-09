package com.wangzan101.vibelink.remote

/** The next safe, actionable step; an unauthenticated scan never grants an account. */
internal enum class DesktopApprovalAction { Checking, LoginPhone, SwitchHub, RenewQr, Confirm }

internal fun desktopApprovalAction(session: ClientSession?, checking: Boolean, knownHubs: List<String>, scan: DesktopScan, now: Long): DesktopApprovalAction {
    if (scan.expiresAt <= now || scan.status !in setOf("pending", "scanned")) return DesktopApprovalAction.RenewQr
    if (checking) return DesktopApprovalAction.Checking
    if (session?.usableOffline(now) != true) return DesktopApprovalAction.LoginPhone
    return if (LoginPolicy.mayApproveDesktop(knownHubs, scan.hub, session.issuer, scan.issuer, session.edition, scan.edition))
        DesktopApprovalAction.Confirm else DesktopApprovalAction.SwitchHub
}

package com.wangzan101.vibelink.clash
import org.junit.Assert.*
import org.junit.Test
class ExitProbeStatusTest {
    @Test fun vpnRunningAloneDoesNotClaimConnectivity() {
        assertFalse(ExitProbeStatus.text(false, false, null).contains("HTTPS 可访问"))
        assertTrue(ExitProbeStatus.text(false, false, false).contains("连接失败"))
    }
    @Test fun pendingAuthorizationExplainsWhyFailedProbeNeedsLogin() {
        assertTrue(ExitProbeStatus.text(false, true, false).contains("尚未授权"))
    }
    @Test fun successfulProbeWinsOverCachedAuthorizationUrl() {
        assertTrue(ExitProbeStatus.text(false, true, true).contains("HTTPS 可访问"))
        assertTrue(ExitProbeStatus.text(true, true, true).contains("正在检测"))
    }
    @Test fun plainProxyServiceCannotClaimTheVpnPathIsHealthy() {
        assertTrue(ExitProbeStatus.text(false, false, true, false).contains("未启用本应用 VPN"))
        assertFalse(ExitProbeStatus.text(false, false, true, false).contains("HTTPS 可访问"))
    }
}

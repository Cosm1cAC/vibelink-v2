package com.wangzan101.vibelink.remote

import org.junit.Assert.*
import org.junit.Test

class LoginQrLinkTest {
    private val secret = "0123456789abcdef0123456789abcdef"
    private val raw = "vibelink://login?v=1&purpose=desktop_login&id=request_1&s=$secret&u=https://hub.example"
    @Test fun parsesDesktopLoginAsApprovalRequestInsteadOfPhonePairing() {
        val parsed = LoginQrLink.parse(raw)!!
        assertEquals("request_1", parsed.id)
        assertEquals(listOf("https://hub.example"), parsed.urls)
        assertNull(PairLink.parse(raw))
    }
    @Test fun rejectsOtherPurposesAndLookalikeSchemes() {
        assertNull(LoginQrLink.parse(raw.replace("desktop_login", "mobile_login")))
        assertNull(LoginQrLink.parse(raw.replace("vibelink://login", "vibelink://login.attacker")))
        assertNull(PairLink.parse("vibelink://pair.attacker?s=$secret&u=http://host"))
    }
    @Test fun cannotEmbedSessionOrPollCredentialsInScannablePayload() {
        assertNull(LoginQrLink.parse("$raw&token=forbidden"))
        assertNull(LoginQrLink.parse("$raw&pollSecret=forbidden"))
        assertNull(LoginQrLink.parse("$raw&id=another"))
    }
    @Test fun dropsUnsafeOriginsAndNeverAcceptsCredentialInAnOrigin() {
        assertNull(LoginQrLink.parse(raw.replace("https://hub.example", "https://name:password@hub.example")))
        assertNull(LoginQrLink.parse(raw.replace("https://hub.example", "file:///tmp/hub")))
    }
    @Test fun unifiedInboxRetainsOnlyOneExplicitPurpose() {
        assertTrue(LoginQrInbox.offer(raw))
        assertNull(PairInbox.pending.value)
        assertTrue(LoginQrInbox.offer("vibelink://pair?s=$secret&u=http://host"))
        assertNull(LoginQrInbox.pending.value)
        assertNotNull(PairInbox.take())
    }
}

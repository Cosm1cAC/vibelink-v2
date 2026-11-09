package com.wangzan101.vibelink.remote

import org.junit.Assert.*
import org.junit.Test

class LoginPolicyTest {
    private val session = ClientSession("seat-a", "A", "tenant-a", "beta", "client-1", 5000, listOf("client"), "hub_test")
    @Test fun aSavedTokenDoesNotBypassLoginValidation() {
        assertFalse(LoginPolicy.allowProtected(true, false, null, 1000))
    }
    @Test fun revokedAndExpiredSessionsCannotOpenTaskPages() {
        assertFalse(LoginPolicy.allowProtected(false, false, session, 1000))
        assertFalse(LoginPolicy.allowProtected(true, true, session, 6000))
    }
    @Test fun verifiedUnexpiredSessionCanEnterAndReadOfflineCache() {
        assertTrue(LoginPolicy.allowProtected(true, true, session, 1000))
        assertTrue(session.usableOffline(1000))
    }
    @Test fun sessionRotationDoesNotChangeSubjectButAnotherAccountDoes() {
        assertEquals(session.subjectKey("https://hub"), session.copy(sessionId = "client-2").subjectKey("https://hub/"))
        assertNotEquals(session.subjectKey("https://hub"), session.copy(userId = "seat-b").subjectKey("https://hub"))
    }
    @Test fun desktopApprovalNeverSendsCurrentBearerToAnotherHub() {
        assertFalse(LoginPolicy.mayApproveDesktop(listOf("https://trusted"), "https://evil", "hub-a", "hub-a", "beta", "beta"))
        assertTrue(LoginPolicy.mayApproveDesktop(listOf("https://trusted", "http://paired-lan"), "http://paired-lan/", "hub-a", "hub-a", "dev", "dev"))
    }

    @Test fun sameIssuerSurvivesOriginChangesButOtherHubAndEditionAreIsolated() {
        assertEquals(session.subjectKey("http://lan"), session.subjectKey("https://public"))
        assertNotEquals(session.subjectKey("http://lan"), session.copy(issuer = "hub_other").subjectKey("http://lan"))
        assertNotEquals(session.subjectKey("http://lan"), session.copy(edition = "dev").subjectKey("http://lan"))
    }

    @Test fun betaNeverAdoptsDevOwnerOrPrivatePairingAddresses() {
        assertFalse(LoginPolicy.editionMatches(session.copy(edition = "dev"), beta = true))
        assertEquals(listOf("https://hub.vibelink.cloud"), LoginPolicy.allowedUrls(listOf("http://192.168.1.2", "https://hub.vibelink.cloud"), true))
        assertFalse(session.copy(permissions = listOf("client", "admin")).usableOffline(1000))
    }

    @Test fun legacyCredentialIsVerifiedBeforeExactlyOneExchange() {
        val order = mutableListOf<String>()
        val result = validateLoginCandidate("old", 1000, read = { order += "me"; session.copy(expiresAt = 0) },
            exchange = { order += "exchange"; ValidatedLogin("new", session) })
        assertEquals(listOf("me", "exchange"), order)
        assertEquals("new", result.token)
        assertEquals(session.userId, result.session.userId)
    }
    @Test fun existingClientSessionIsNotExchangedOnEveryStart() {
        val result = validateLoginCandidate("client", 1000, read = { session }, exchange = { error("must not exchange") })
        assertEquals("client", result.token)
    }
    @Test fun rejectedCredentialCannotFallBackToAnOwnerOrExchange() {
        var exchanged = false
        assertThrows(IllegalStateException::class.java) {
            validateLoginCandidate("revoked", 1000, read = { error("401") }, exchange = { exchanged = true; ValidatedLogin("owner", session) })
        }
        assertFalse(exchanged)
        assertFalse(LoginPolicy.transientFailure(401, false))
        assertTrue(LoginPolicy.transientFailure(503, false))
    }
    @Test fun exchangeCannotSilentlyChangeTheLegacySubject() {
        assertThrows(IllegalStateException::class.java) {
            validateLoginCandidate("old", 1000, read = { session.copy(expiresAt = 0) },
                exchange = { ValidatedLogin("new", session.copy(userId = "other")) })
        }
    }

    @Test fun sameOriginCannotOverrideDifferentHubIssuerOrEdition() {
        assertFalse(LoginPolicy.mayApproveDesktop(listOf("https://same"), "https://same", "hub-old", "hub-new", "dev", "dev"))
        assertFalse(LoginPolicy.mayApproveDesktop(listOf("https://same"), "https://same", "hub-a", "hub-a", "dev", "beta"))
        assertFalse(LoginPolicy.mayApproveDesktop(listOf("https://same"), "https://same", "hub-a", "", "dev", "dev"))
    }

    @Test fun developerOriginsStayPrivateAndRejectUrlCredentialSmuggling() {
        val private = listOf("http://127.0.0.1:18995", "http://192.168.5.2:18995", "http://100.98.1.2:18995", "http://localhost", "http://[::1]:18995", "https://worker.tail.ts.net")
        assertEquals(private, LoginPolicy.allowedUrls(private, false))
        assertTrue(LoginPolicy.allowedUrls(listOf("https://example.com", "https://ai", "http://8.8.8.8", "http://user:secret@127.0.0.1", "http://127.0.0.1?token=x", "http://127.0.0.1/#secret", "http://127.0.0.1/path"), false).isEmpty())
        assertEquals(listOf("https://hub.vibelink.cloud"), LoginPolicy.allowedUrls(listOf("https://hub.vibelink.cloud:443/", "https://hub.vibelink.cloud.evil", "http://hub.vibelink.cloud"), true))
    }

    @Test fun scanPrefersKnownQrOriginWithoutAddingOtherRecipients() {
        val lan = "http://192.168.5.2:18995"
        val virtual = "http://172.17.16.1:18995"
        val unknown = "http://192.168.5.99:18995"
        assertEquals(listOf(lan, virtual), LoginPolicy.scanUrls(listOf(virtual, lan), false, listOf(lan, unknown)))
        assertEquals(listOf("https://hub.vibelink.cloud"), LoginPolicy.scanUrls(listOf(lan, "https://hub.vibelink.cloud"), true, listOf(lan)))
    }
}

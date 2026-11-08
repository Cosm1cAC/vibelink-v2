package com.wangzan101.vibelink.ui

import com.wangzan101.vibelink.remote.ClientSession
import org.junit.Assert.*
import org.junit.Test

class AuthGatePresentationTest {
    private val ready = ClientSession("user-a", "A", null, "dev", "client-a", 5000, listOf("client"), "hub_a")
    @Test fun readySnapshotCannotKeepPagesOpenAfterCredentialIsClearedOrCannotBeDecrypted() {
        assertFalse(AuthGatePresentation.canShowProtected(ready, ready, credentialPresent = false, now = 1000))
    }
    @Test fun oldReadyCannotPresentAnotherAccountsNewlyStoredCredential() {
        val different = ready.copy(userId = "user-b", sessionId = "client-b")
        assertFalse(AuthGatePresentation.canShowProtected(ready, different, credentialPresent = true, now = 1000))
        assertFalse(AuthGatePresentation.canShowProtected(ready, null, credentialPresent = true, now = 1000))
    }
    @Test fun unchangedVerifiedIdentityCanReadCacheButExpiredIdentityCannot() {
        assertTrue(AuthGatePresentation.canShowProtected(ready, ready, credentialPresent = true, now = 1000))
        assertFalse(AuthGatePresentation.canShowProtected(ready, ready, credentialPresent = true, now = 6000))
    }
}

package com.wangzan101.vibelink.net

import org.junit.Assert.*
import org.junit.Test

class HubModelCredentialPolicyTest {
    @Test fun sdkRequestQueuedByACannotAcquireBsCredentialAfterAccountSwitch() {
        val queuedByA = listOf("Bearer account-a-fixture")
        assertFalse(HubModelCredentialPolicy.matches(queuedByA, emptyList(), "account-b-fixture"))
        assertTrue(HubModelCredentialPolicy.matches(queuedByA, emptyList(), "account-a-fixture"))
    }
    @Test fun unauthenticatedOldCallerDoesNotReceiveCurrentAccountsPermission() {
        assertFalse(HubModelCredentialPolicy.matches(emptyList(), emptyList(), "account-b-fixture"))
        assertFalse(HubModelCredentialPolicy.matches(listOf("Bearer account-b-fixture"), emptyList(), ""))
    }
    @Test fun conflictingOrDuplicateCredentialsCannotSelectAnotherAccount() {
        assertFalse(HubModelCredentialPolicy.matches(listOf("Bearer account-b-fixture"), listOf("account-a-fixture"), "account-b-fixture"))
        assertFalse(HubModelCredentialPolicy.matches(listOf("Bearer account-a-fixture", "Bearer account-b-fixture"), emptyList(), "account-b-fixture"))
        assertTrue(HubModelCredentialPolicy.matches(emptyList(), listOf("account-b-fixture"), "account-b-fixture"))
    }
}

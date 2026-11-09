package com.wangzan101.vibelink.remote

import org.junit.Assert.*
import org.junit.Test

class PairAttemptFenceTest {
    private val a = PairLink("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "A", listOf("http://192.168.5.2:18995"))
    private val b = PairLink("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "B", listOf("http://192.168.5.3:18995"))
    @Test fun oldScanCompletingAfterNewScanCannotSupplyItsHubForNewSecret() {
        val fence = PairAttemptFence()
        val old = fence.begin(a)
        val current = fence.begin(b)
        var displayed: PairLink? = null
        if (fence.accepts(current)) displayed = current.link
        if (fence.accepts(old)) displayed = old.link
        assertEquals(b, displayed)
        assertNull(fence.capture(a))
        assertEquals(current, fence.capture(b))
        assertFalse(fence.accepts(old))
    }
    @Test fun cancellationOrUnmountInvalidatesPendingConfirmationCredentials() {
        val fence = PairAttemptFence()
        val pending = fence.begin(a)
        fence.cancel()
        assertFalse(fence.accepts(pending))
        assertNull(fence.capture(a))
    }
    @Test fun retryOfSameLinkNeverRevalidatesTheEarlierScanAttempt() {
        val fence = PairAttemptFence()
        val old = fence.begin(a)
        val retry = fence.begin(a)
        assertFalse(fence.accepts(old))
        assertTrue(fence.accepts(retry))
    }
}

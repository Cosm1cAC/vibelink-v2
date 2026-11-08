package com.wangzan101.vibelink.remote

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GroupWorkTest {
    @Test fun acceptedRequestWithLostResponseRetainsItsIdForRetry() {
        val pending = linkedMapOf("stable-send-1" to "payload")
        val accepted = linkedSetOf<String>()
        val calls = mutableListOf<String>()
        try {
            deliverGroupWorkRequests(pending.toList(), send = { id, _ ->
                accepted += id
                calls += id
                throw IOException("response lost after Hub accepted")
            }, acknowledge = { pending.remove(it) })
            fail("must surface ambiguous result")
        } catch (_: IOException) { }
        assertTrue(pending.containsKey("stable-send-1"))
        deliverGroupWorkRequests(pending.toList(), send = { id, _ -> accepted += id; calls += id }, acknowledge = { pending.remove(it) })
        assertEquals(listOf("stable-send-1", "stable-send-1"), calls)
        assertEquals(1, accepted.size)
        assertTrue(pending.isEmpty())
    }

    @Test fun unknownBackendRequiresUpgradeRatherThanLegacyExecution() {
        assertTrue(groupWorkFailure(IllegalStateException("HTTP 404: missing")).contains("升级"))
        assertTrue(groupWorkFailure(IllegalStateException("Hub work API response is incompatible")).contains("升级"))
    }

    @Test fun switchingAccountDuringRequestCannotAcknowledgeOldOutbox() {
        val pending = linkedMapOf("old-account-send" to "payload")
        var current = "old-account"
        try {
            deliverGroupWorkRequests(pending.toList(), send = { _, _ ->
                requireGroupWorkAccount("old-account", current)
                current = "new-account"
                requireGroupWorkAccount("old-account", current)
            }, acknowledge = { pending.remove(it) })
            fail("a stale response must not be applied")
        } catch (_: IllegalStateException) { }
        assertTrue(pending.containsKey("old-account-send"))
    }

    @Test fun needsInputRemainsActiveAndRetryRequiresExplicitHubPermission() {
        fun work(status: String) = GroupWork("w", "g", status, "", "", "", "", 0, 0)
        assertTrue(work("needs_input").active)
        assertFalse(work("needs_input").retryable)
        assertFalse(work("completed").active)
        assertFalse(work("completed").retryable)
        assertFalse(work("failed").retryable)
        assertFalse(work("cancelled").retryable)
        assertTrue(work("needs_input").copy(retryable = true).retryable)
    }

    @Test fun ordinarySendDoesNotAttachToPreviousTaskButExplicitFollowupDoes() {
        assertNull(GroupWorkSubmission("new", "new task").replyToWorkId)
        assertEquals("existing", GroupWorkSubmission("followup", "answer", replyToWorkId = "existing").replyToWorkId)
    }
}

package com.wangzan101.vibelink.remote

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LoginQrInboxReaderTest {
    @Test fun takingPendingRequestDoesNotCancelItsSuspendedRead() = runBlocking {
        LoginQrInbox.pending.value = null
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val reader = launch(start = CoroutineStart.UNDISPATCHED) {
            LoginQrInbox.consume {
                entered.complete(Unit)
                response.await()
                completed.complete(Unit)
            }
        }
        try {
            LoginQrInbox.pending.value = LoginQrLink("request", "fixture-secret", listOf("http://192.168.5.2:18995"))
            withTimeout(2000) { entered.await() }
            yield()
            assertNull(LoginQrInbox.pending.value)
            assertFalse(completed.isCompleted)
            response.complete(Unit)
            withTimeout(2000) { completed.await() }
            assertTrue(reader.isActive)
        } finally { reader.cancelAndJoin(); LoginQrInbox.pending.value = null }
    }

    @Test fun newerScanCancelsOldReadAndRetryOfConsumedLinkRunsAgain() = runBlocking {
        LoginQrInbox.pending.value = null
        val first = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val retry = CompletableDeferred<Unit>()
        var secondReads = 0
        val reader = launch(start = CoroutineStart.UNDISPATCHED) {
            LoginQrInbox.consume { link ->
                if (link.id == "first") {
                    first.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                } else {
                    secondReads++
                    if (secondReads == 2) retry.complete(Unit)
                }
            }
        }
        try {
            LoginQrInbox.pending.value = LoginQrLink("first", "fixture-secret", emptyList())
            withTimeout(2000) { first.await() }
            val second = LoginQrLink("second", "fixture-secret", emptyList())
            LoginQrInbox.pending.value = second
            withTimeout(2000) { cancelled.await(); while (secondReads < 1) yield() }
            LoginQrInbox.pending.value = second
            withTimeout(2000) { retry.await() }
            assertEquals(2, secondReads)
        } finally { reader.cancelAndJoin(); LoginQrInbox.pending.value = null }
    }
}

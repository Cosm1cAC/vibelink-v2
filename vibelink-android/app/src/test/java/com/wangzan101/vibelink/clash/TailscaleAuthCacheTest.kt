package com.wangzan101.vibelink.clash

import org.junit.Assert.*
import org.junit.Test

class TailscaleAuthCacheTest {
    private val good = "https://login.tailscale.com/a/0123456789abcdef"
    @Test fun onlyOfficialExactHttpsDestinationIsAllowed() {
        assertEquals(good, TailscaleAuthCache.allowedUrl(good))
        listOf("http://login.tailscale.com/a/01234567", "https://login.tailscale.com.evil/a/01234567",
            "https://login.tailscale.com@evil.test/a/01234567", "https://evil.test/a/01234567",
            "$good?redirect=evil", "$good#fragment", "https://login.tailscale.com:444/a/01234567",
            "https://login.tailscale.com/a/../01234567", "https://login.tailscale.com/a/%2f01234567")
            .forEach { assertNull(TailscaleAuthCache.allowedUrl(it)) }
    }
    @Test fun expiresAndCannotCrossProfileOrStoppedVpn() {
        var time = 0L
        val cache = TailscaleAuthCache({ time }, 100)
        cache.accept("[Tailscale] Visit $good", "one", true)
        assertEquals(good, cache.current("one", true))
        time = 99; cache.accept("[Tailscale] Visit $good", "one", true)
        time = 100; assertNull(cache.current("one", true))
        cache.accept("[Tailscale] Visit $good", "one", true)
        assertNull(cache.current("two", true))
        cache.accept("[Tailscale] Visit $good", "one", true)
        assertNull(cache.current("one", false))
        assertNull(cache.current("one", true))
    }
    @Test fun unrelatedLogCannotOfferBrowserAuthorization() {
        val cache = TailscaleAuthCache({ 0 })
        cache.accept("ordinary application requested $good", "one", true)
        assertNull(cache.current("one", true))
    }
    @Test fun observerDetachAndFailureDoNotStopAuthCapture() {
        val cache = TailscaleAuthCache({ 0 })
        val relay = TailscaleLogRelay<String>(cache)
        var observed = 0
        relay.setObserver { observed++ }
        relay.accept("log", "[Tailscale] Visit $good", "one", true)
        assertEquals(1, observed)
        relay.setObserver(null)
        relay.accept("log", "[Tailscale] Visit $good", "one", true)
        assertEquals(good, cache.current("one", true))
        relay.setObserver { error("UI disconnected") }
        relay.accept("log", "[Tailscale] Visit $good", "one", true)
        relay.accept("log", "[Tailscale] Visit $good", "one", true)
        assertEquals(good, cache.current("one", true))
    }
    @Test fun authorizationCapabilityIsRedactedFromForwardedLogs() {
        val text = "[Tailscale] Visit $good"
        assertFalse(TailscaleAuthCache.redact(text).contains(good))
        assertEquals("normal", TailscaleAuthCache.redact("normal"))
    }
    @Test fun browserForegroundAndServiceRebindingRetainShortLivedAuthorization() {
        val cache = TailscaleAuthCache({ 0 })
        val before = TailscaleLogRelay<String>(cache)
        before.accept("log", "[Tailscale] Visit $good", "one", true)
        before.setObserver(null)
        val after = TailscaleLogRelay<String>(cache)
        assertEquals(good, cache.current("one", true))
        after.accept("other", "normal log", "one", true)
        assertEquals(good, cache.current("one", true))
    }

    @Test fun sameProfileLoadedAfterStartupKeepsItsOnceOnlyAuthorizationLink() {
        val cache = TailscaleAuthCache({ 0 })
        cache.commitLoad(cache.beginLoad("managed"))
        cache.accept("[Tailscale](Tailscale) To start this tsnet server, go to: $good", "managed", true)
        cache.profileLoaded("managed")
        assertEquals(good, cache.current("managed", true))
        cache.profileLoaded("other")
        assertNull(cache.current("other", true))
    }

    @Test fun crossProfileLoadNeverRelabelsLateLogsEvenAfterCommitOrServiceRestart() {
        var wall = 10L
        val cache = TailscaleAuthCache({ 0 }, wallClock = { wall })
        cache.commitLoad(cache.beginLoad("a"))
        cache.acceptCaptured("[Tailscale] Visit $good", true, 11)
        assertEquals(good, cache.current("a", true))
        val b = cache.beginLoad("b")
        cache.acceptCaptured("[Tailscale] Visit $good", true, 12)
        assertNull(cache.current("b", true))
        wall = 20
        cache.commitLoad(b)
        // Old tsnet servers can still log after native load completes. There is
        // no source UUID in this format, so no URL may be offered as B's proof.
        cache.acceptCaptured("[Tailscale] Visit $good", true, 21)
        cache.profileLoaded("b")
        assertNull(cache.current("b", true))
        cache.stopped()
        wall = 30
        cache.commitLoad(cache.beginLoad("b"))
        cache.acceptCaptured("[Tailscale] Visit $good", true, 31)
        assertNull(cache.current("b", true))
        // A real :background process restart constructs a new, unambiguous cache.
        val freshProcess = TailscaleAuthCache({ 0 }, wallClock = { 30 })
        freshProcess.commitLoad(freshProcess.beginLoad("b"))
        freshProcess.acceptCaptured("[Tailscale] Visit $good", true, 31)
        assertEquals(good, freshProcess.current("b", true))
    }

    @Test fun initialLazyStartupUsesCommittedProfileAndDropsQueuedPreCommitLogs() {
        var wall = 10L
        val cache = TailscaleAuthCache({ 0 }, wallClock = { wall })
        val loading = cache.beginLoad("managed")
        cache.acceptCaptured("[Tailscale] Visit $good", true, 11)
        assertNull(cache.current("managed", true))
        wall = 20
        assertTrue(cache.commitLoad(loading))
        cache.acceptCaptured("[Tailscale] Visit $good", true, 19)
        cache.acceptCaptured("[Tailscale] Visit $good", true, 20)
        assertNull(cache.current("managed", true))
        // The first actual lazy dial happens after native load has committed.
        cache.acceptCaptured("[Tailscale] Visit $good", true, 21)
        cache.profileLoaded("managed")
        assertEquals(good, cache.current("managed", true))
    }

    @Test fun mismatchedLoadedBroadcastCannotReassignOrDiscardCommittedCapability() {
        val cache = TailscaleAuthCache({ 0 }, wallClock = { 10 })
        val first = cache.beginLoad("managed")
        assertTrue(cache.commitLoad(first))
        cache.acceptCaptured("[Tailscale] Visit $good", true, 11)
        cache.profileLoaded("other")
        cache.profileLoaded(null)
        assertEquals(good, cache.current("managed", true))
        val sameProfile = cache.beginLoad("managed")
        assertFalse(cache.commitLoad(first))
        assertEquals(good, cache.current("managed", true))
        assertTrue(cache.commitLoad(sameProfile))
        cache.profileLoaded("managed")
        assertEquals(good, cache.current("managed", true))
    }

    @Test fun stoppedOrFailedLoadCannotCommitItsLateCompletion() {
        val cache = TailscaleAuthCache({ 0 }, wallClock = { 10 })
        val stopped = cache.beginLoad("managed")
        cache.stopped()
        assertFalse(cache.commitLoad(stopped))
        val failed = cache.beginLoad("managed")
        cache.failLoad(failed)
        assertFalse(cache.commitLoad(failed))
        cache.acceptCaptured("[Tailscale] Visit $good", true, 11)
        assertNull(cache.current("managed", true))
    }

    @Test fun managedCoreRestartRequiresStoppedDevSeedAndExactCurrentProfileInBackgroundProcess() {
        fun allowed(beta: Boolean = false, seed: Boolean = true, active: String? = "managed", managed: String? = "managed",
            running: Boolean = false, process: String? = "com.example:background") =
            ManagedAuthorizationRestartPolicy.allows(beta, seed, "managed", active, managed, running, process, "com.example")
        assertTrue(allowed())
        assertFalse(allowed(beta = true))
        assertFalse(allowed(seed = false))
        assertFalse(allowed(active = "custom"))
        assertFalse(allowed(managed = "other"))
        assertFalse(allowed(managed = null))
        assertFalse(allowed(running = true))
        assertFalse(allowed(process = "com.example"))
        assertFalse(allowed(process = "other.app:background"))
        assertFalse(allowed(process = null))
    }
}

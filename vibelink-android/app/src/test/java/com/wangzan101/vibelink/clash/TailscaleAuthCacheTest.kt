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
        cache.accept("[Tailscale](Tailscale) To start this tsnet server, go to: $good", "managed", true)
        cache.profileLoaded("managed")
        assertEquals(good, cache.current("managed", true))
        cache.profileLoaded("other")
        assertNull(cache.current("other", true))
    }
}

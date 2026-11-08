package com.wangzan101.vibelink.remote

import org.junit.Assert.*
import org.junit.Test

class HubRoutesTest {
    @Test fun oldInflightSuccessCannotRepopulateNewAccountsFallbacks() {
        val routes = HubRoutes()
        routes.configure("https://old", "old-token", listOf("http://old-lan"), emptyList())
        val old = routes.capture("https://old", "old-token")
        routes.configure("https://new", "new-token", listOf("http://new-lan"), emptyList())
        assertThrows(IllegalStateException::class.java) { routes.remember(old, "http://old-lan") }
        assertEquals(listOf("https://new", "http://new-lan"), routes.capture("https://new", "new-token").candidates)
        assertThrows(IllegalStateException::class.java) { routes.capture("https://old", "new-token") }
    }

    @Test fun changingOnlyTokenInvalidatesAlreadyCapturedRequests() {
        val routes = HubRoutes()
        routes.configure("https://hub", "seat-a", emptyList(), emptyList())
        val first = routes.capture("https://hub", "seat-a")
        routes.configure("https://hub", "seat-b", emptyList(), emptyList())
        assertThrows(IllegalStateException::class.java) { routes.requireCurrent(first) }
        assertThrows(IllegalStateException::class.java) { routes.capture("https://hub", "seat-a") }
    }

    @Test fun repeatedReadKeepsFastRouteButNewPairingReplacesOldAddresses() {
        val routes = HubRoutes()
        routes.configure("https://hub", "token", listOf("http://lan"), emptyList())
        routes.remember(routes.capture("https://hub", "token"), "http://lan")
        routes.configure("https://hub", "token", listOf("http://lan"), emptyList())
        assertEquals("http://lan", routes.capture("https://hub", "token").candidates.first())
        routes.configure("https://hub", "token", listOf("http://replacement"), emptyList())
        assertEquals(listOf("https://hub", "http://replacement"), routes.capture("https://hub", "token").candidates)
    }
}

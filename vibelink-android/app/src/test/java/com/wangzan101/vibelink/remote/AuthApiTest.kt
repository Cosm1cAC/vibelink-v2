package com.wangzan101.vibelink.remote

import kotlinx.coroutines.*
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AuthApiTest {
    private fun session(legacy: Boolean = false): JSONObject = JSONObject().put("userId", "fixture-owner")
        .put("label", "Fixture").put("tenantId", JSONObject.NULL).put("edition", "dev")
        .put("sessionId", if (legacy) "legacy" else "client-fixture").put("issuer", "hub_fixture").put("hubId", "hub_fixture")
        .put("expiresAt", if (legacy) 0L else System.currentTimeMillis() + 60000).put("permissions", org.json.JSONArray(listOf("client")))

    @Test fun legacyValidationAndExchangeKeepSubjectAndSendOnlyDeviceName() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(JSONObject().put("ok", true).put("session", session(true)).toString()))
            server.enqueue(MockResponse().setBody(JSONObject().put("ok", true).put("token", "client-fixture-token").put("session", session()).toString()))
            val hub = server.url("/").toString().trimEnd('/')
            val old = AuthApi.me(hub, "legacy-fixture-token")
            val migrated = AuthApi.exchange(hub, "legacy-fixture-token", "Fixture Android")
            assertEquals(old.userId, migrated.session.userId)
            assertEquals(old.issuer, migrated.session.issuer)
            assertTrue(migrated.session.usableOffline(System.currentTimeMillis()))
            assertEquals("/api/auth/me", server.takeRequest().path)
            val request = server.takeRequest()
            assertEquals("Bearer legacy-fixture-token", request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("Fixture Android", body.getString("deviceName"))
            assertFalse(body.has("code"))
            assertFalse(SessionJson.encode(migrated.session).has("token"))
        }
    }
    @Test fun scanningDesktopCodeIsPublicAndDoesNotAutomaticallyConfirm() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":"scanned","deviceName":"Fixture PC","edition":"dev","issuer":"hub_fixture","hubId":"hub_fixture","verificationCode":"123456","expiresAt":9999999999999}"""))
            val link = LoginQrLink("request", "fixture-scan-secret-that-is-not-a-session", listOf(server.url("/").toString().trimEnd('/')))
            val scan = runBlocking { AuthApi.scan(link) }
            assertEquals("123456", scan.verificationCode)
            assertEquals("hub_fixture", scan.issuer)
            val request = server.takeRequest()
            assertEquals("/api/auth/qr/scan", request.path)
            assertNull(request.getHeader("Authorization"))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun unauthorizedMeNeverFallsBackToAnotherIdentityOrExchange() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            val error = assertThrows(AuthHttpFailure::class.java) { AuthApi.me(server.url("/").toString(), "expired-fixture-token") }
            assertEquals(401, error.code)
            assertEquals(1, server.requestCount)
            assertEquals("/api/auth/me", server.takeRequest().path)
        }
    }
    @Test fun explicitDesktopConfirmationDoesNotReturnOrAdoptDesktopCredential() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"status":"approved"}"""))
            val hub = server.url("/").toString().trimEnd('/')
            val result = AuthApi.confirm(hub, "phone-fixture-token", LoginQrLink("request", "fixture-scan-secret", listOf("https://untrusted.example")), "approve")
            assertEquals(Unit, result)
            val request = server.takeRequest()
            assertEquals("/api/auth/qr/confirm", request.path)
            assertEquals("Bearer phone-fixture-token", request.getHeader("Authorization"))
            assertEquals("approve", JSONObject(request.body.readUtf8()).getString("decision"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun unreachableSavedPrimaryCanMigrateThroughSavedAliasWithoutChangingPrincipal() {
        val primary = MockWebServer()
        primary.start()
        val unavailable = primary.url("/").toString().trimEnd('/')
        primary.shutdown()
        MockWebServer().use { alias ->
            alias.enqueue(MockResponse().setBody(JSONObject().put("ok", true).put("session", session(true)).toString()))
            alias.enqueue(MockResponse().setBody(JSONObject().put("ok", true).put("token", "migrated-fixture-token").put("session", session()).toString()))
            val reachable = alias.url("/").toString().trimEnd('/')
            val located = AuthApi.meTrusted(listOf(unavailable, reachable), "legacy-fixture-token")
            val validated = validateLoginCandidate("legacy-fixture-token", System.currentTimeMillis(), read = { located.session },
                exchange = { AuthApi.exchange(located.origin, "legacy-fixture-token", "Fixture Android").let { ValidatedLogin(it.token, it.session) } })
            assertEquals(reachable, located.origin)
            assertEquals("fixture-owner", validated.session.userId)
            assertEquals("/api/auth/me", alias.takeRequest().path)
            assertEquals("/api/auth/session", alias.takeRequest().path)
            assertEquals(2, alias.requestCount)
        }
    }
    @Test fun unauthorizedSavedPrimaryNeverTriesAnAlias() {
        MockWebServer().use { primary -> MockWebServer().use { alias ->
            primary.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            alias.enqueue(MockResponse().setBody(JSONObject().put("ok", true).put("session", session()).toString()))
            assertThrows(AuthHttpFailure::class.java) {
                AuthApi.meTrusted(listOf(primary.url("/").toString(), alias.url("/").toString()), "revoked-fixture-token")
            }
            assertEquals(1, primary.requestCount)
            assertEquals(0, alias.requestCount)
        } }
    }
    @Test fun serviceUnavailableMayTryOnlySuppliedTrustedAlias() {
        MockWebServer().use { primary -> MockWebServer().use { alias ->
            primary.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            alias.enqueue(MockResponse().setBody(JSONObject().put("ok", true).put("session", session()).toString()))
            val reachable = alias.url("/").toString()
            val located = AuthApi.meTrusted(listOf(primary.url("/").toString(), reachable), "client-fixture-token")
            assertEquals(reachable, located.origin)
            assertEquals("fixture-owner", located.session.userId)
            assertEquals(1, alias.requestCount)
        } }
    }

    @Test fun desktopScanSkipsClosedAdapterAndHttpFailureWithoutSendingCredential() {
        val closed = MockWebServer().apply { start() }
        val dead = closed.url("/").toString()
        closed.shutdown()
        MockWebServer().use { old -> MockWebServer().use { lan ->
            old.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            lan.enqueue(MockResponse().setBody("""{"status":"scanned","deviceName":"LAN PC","edition":"dev","issuer":"hub_fixture","hubId":"hub_fixture","verificationCode":"654321","expiresAt":9999999999999}"""))
            val reachable = lan.url("/").toString()
            val scan = runBlocking { AuthApi.scan(LoginQrLink("fixture-request", "fixture-secret", listOf(dead, old.url("/").toString(), reachable))) }
            assertEquals(reachable, scan.hub)
            assertEquals("654321", scan.verificationCode)
            assertNull(old.takeRequest().getHeader("Authorization"))
            assertNull(lan.takeRequest().getHeader("Authorization"))
        } }
    }

    @Test fun failedScanExplainsRecoveryInsteadOfExposingLastVirtualAdapterException() {
        val closed = MockWebServer().apply { start() }
        val dead = closed.url("/").toString()
        closed.shutdown()
        val error = assertThrows(java.io.IOException::class.java) {
            runBlocking { AuthApi.scan(LoginQrLink("fixture-request", "fixture-secret", listOf(dead))) }
        }
        assertTrue(error.message!!.contains("重新读取"))
        assertFalse(error.message!!.contains("failed to connect"))
    }

    @Test fun cancellingScanAbortsInFlightCallAndNeverProbesRemainingQrOrigins() = runBlocking {
        MockWebServer().use { slow -> MockWebServer().use { later ->
            slow.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            later.enqueue(MockResponse().setResponseCode(404))
            val read = launch {
                AuthApi.scan(LoginQrLink("fixture", "fixture-secret", listOf(slow.url("/").toString(), later.url("/").toString())))
            }
            try {
                val started = withContext(Dispatchers.IO) { slow.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS) }
                assertNotNull(started)
                withTimeout(1000) { read.cancelAndJoin() }
                assertEquals(0, later.requestCount)
                assertNull(started!!.getHeader("Authorization"))
            } finally { read.cancelAndJoin() }
        } }
    }
}

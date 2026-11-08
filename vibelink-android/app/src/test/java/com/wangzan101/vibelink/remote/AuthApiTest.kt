package com.wangzan101.vibelink.remote

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
            val scan = AuthApi.scan(link)
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
}

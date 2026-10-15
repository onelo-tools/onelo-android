package com.onelo.android

import com.onelo.android.internal.HttpResponse
import com.onelo.android.internal.UserTokenSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #820 — a signed-in Onelo Auth user's report names a VERIFIED reporter: the
 * feedback initiate call carries `Authorization: Bearer <access token>`.
 * Signed out, nothing is sent (anonymous); an explicit `userId` stays an
 * unverified label. Security headers always ride along.
 */
class FeedbackIdentityTest {

    private val url = "https://api.test/api/sdk/feedback/initiate?key=pk_test"
    private val security = mapOf("X-Bundle-Id" to "com.app")
    private val ok = HttpResponse(200, mapOf("hosted_url" to "https://onelo.tools/feedback/hosted?ft=x"))
    private val refused = HttpResponse(401, mapOf("detail" to mapOf("error" to "invalid_user_token")))

    private fun source(token: String?, refreshed: String? = null) =
        UserTokenSource(readCurrent = { token }, readRefreshed = { refreshed })

    private class Server(vararg answers: HttpResponse) {
        private val queue = ArrayDeque(answers.toList())
        val seen = mutableListOf<Map<String, String>>()
        val get: suspend (String, Map<String, String>) -> HttpResponse = { _, headers ->
            seen += headers
            queue.removeFirstOrNull() ?: HttpResponse(200, mapOf("hosted_url" to "https://onelo.tools/feedback/hosted?ft=x"))
        }
    }

    @Test
    fun `signed in sends the access token as Bearer, no userId needed`() = runBlocking {
        val server = Server(ok)
        val hosted = FeedbackInitiate.hostedUrl(url, null, security, source("AT_1"), server.get)
        assertEquals("https://onelo.tools/feedback/hosted?ft=x", hosted)
        assertEquals(1, server.seen.size)
        assertEquals("Bearer AT_1", server.seen[0]["Authorization"])
        assertNull(server.seen[0]["X-Onelo-User-Id"])
        assertEquals("com.app", server.seen[0]["X-Bundle-Id"])
    }

    @Test
    fun `signed out is anonymous`() = runBlocking {
        val server = Server(ok)
        FeedbackInitiate.hostedUrl(url, null, security, source(null), server.get)
        assertNull(server.seen[0]["Authorization"])
        assertNull(server.seen[0]["X-Onelo-User-Id"])
    }

    @Test
    fun `signed out with an explicit userId keeps the unverified label`() = runBlocking {
        val server = Server(ok)
        FeedbackInitiate.hostedUrl(url, "dev-42", security, null, server.get)
        assertNull(server.seen[0]["Authorization"])
        assertEquals("dev-42", server.seen[0]["X-Onelo-User-Id"])
    }

    @Test
    fun `a refused token is refreshed once and retried`() = runBlocking {
        val server = Server(refused, ok)
        FeedbackInitiate.hostedUrl(url, null, security, source("AT_OLD", "AT_NEW"), server.get)
        assertEquals(listOf("Bearer AT_OLD", "Bearer AT_NEW"), server.seen.map { it["Authorization"] })
    }

    @Test
    fun `refused after refresh the form still opens without the token`() = runBlocking {
        val server = Server(refused, refused, ok)
        val hosted = FeedbackInitiate.hostedUrl(url, "dev-42", security, source("AT_OLD", "AT_NEW"), server.get)
        assertTrue(hosted.startsWith("https://onelo.tools/"))
        assertEquals(3, server.seen.size)
        assertNull(server.seen[2]["Authorization"])
        assertEquals("dev-42", server.seen[2]["X-Onelo-User-Id"])
    }

    @Test
    fun `a non-2xx answer throws`() = runBlocking {
        val server = Server(HttpResponse(500, emptyMap()))
        try {
            FeedbackInitiate.hostedUrl(url, null, security, null, server.get)
            fail("expected a thrown error")
        } catch (_: OneloError) {}
    }
}

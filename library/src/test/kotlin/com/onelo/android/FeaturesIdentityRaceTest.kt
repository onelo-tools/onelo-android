package com.onelo.android

import com.onelo.android.internal.HttpResponse
import com.onelo.android.internal.UserTokenSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * #737 — the Swift feature-snapshot identity races, checked on Android.
 * A snapshot is bound to the identity it was resolved for: a late answer for
 * a previous identity, an anonymous answer while signed in, or the previous
 * user's cache never become the current user's state, and ready() means
 * "confirmed for the CURRENT identity".
 */
class FeaturesIdentityRaceTest {

    private class Req(val url: String, val body: Map<String, Any?>?, val auth: String?)

    private fun snapshot(version: Int, vararg enabled: String) = HttpResponse(
        200,
        mapOf("config_version" to version, "features" to enabled.associateWith { mapOf("status" to "enabled") }),
    )

    private fun features(
        sink: MutableList<Req>,
        tokens: UserTokenSource? = null,
        post: suspend (body: Map<String, Any?>, auth: String?) -> HttpResponse,
        get: suspend (url: String) -> HttpResponse = { HttpResponse(200, mapOf("up_to_date" to true)) },
    ) = OneloFeatures(
        OneloConfig(publishableKey = "onelo_pk_live_abc", apiUrl = "https://example.test"),
        httpGet = { url, h -> sink += Req(url, null, h["Authorization"]); get(url) },
        httpPost = { url, body, h -> sink += Req(url, body, h["Authorization"]); post(body, h["Authorization"]) },
        userToken = tokens,
    )

    @Test
    fun `a late resolve for the previous identity is dropped`() = runBlocking {
        val sink = Collections.synchronizedList(mutableListOf<Req>())
        val gateA = CompletableDeferred<HttpResponse>()
        val f = features(sink, post = { body, _ ->
            if (body["userId"] == "A") gateA.await() else snapshot(3, "b-only")
        })
        val loadA = async(kotlinx.coroutines.Dispatchers.IO) { f.load("A") }
        while (sink.none { it.body?.get("userId") == "A" }) Thread.sleep(5)
        f.load("B")
        gateA.complete(snapshot(3, "a-only"))
        loadA.await()

        assertTrue(f.feature("b-only").isEnabled())
        assertFalse("A's late answer must not replace B's snapshot", f.feature("a-only").isEnabled())
        f.stopPolling()
    }

    @Test
    fun `a signed-in refusal never applies an anonymous answer`() = runBlocking {
        val sink = Collections.synchronizedList(mutableListOf<Req>())
        val tokens = UserTokenSource(readCurrent = { "stale" }, readRefreshed = { "fresh" })
        val f = features(sink, tokens, post = { _, auth ->
            if (auth == null) snapshot(5, "global-only")
            else HttpResponse(401, mapOf("detail" to mapOf("error" to "invalid_user_token")))
        })
        f.load("u1")

        assertFalse(f.feature("global-only").isEnabled())
        assertFalse("an unconfirmed identity is not ready", f.isReady)
        assertTrue("no anonymous call is made for a signed-in user", sink.all { it.auth != null })
        f.stopPolling()
    }

    @Test
    fun `a new identity with no snapshot does not keep the previous one`() = runBlocking {
        val sink = Collections.synchronizedList(mutableListOf<Req>())
        val f = features(sink, post = { body, _ ->
            if (body["userId"] == "A") snapshot(2, "pro") else HttpResponse(503, emptyMap())
        })
        f.load("A")
        assertTrue(f.feature("pro").isEnabled())

        f.load("B")
        assertFalse("B must not see A's features", f.feature("pro").isEnabled())
        f.stopPolling()
    }

    @Test
    fun `ready resets on identity change and a failure does not confirm`() = runBlocking {
        val sink = Collections.synchronizedList(mutableListOf<Req>())
        val f = features(sink, post = { body, _ ->
            if (body["userId"] == "A") snapshot(2, "x") else HttpResponse(503, emptyMap())
        })
        f.load("A")
        assertTrue(f.isReady)

        f.load("B")
        assertFalse(f.isReady)
        f.ready(50)
        assertFalse(f.isReady)
        f.stopPolling()
    }

    @Test
    fun `poll never sends a since_version not confirmed for the current identity`() = runBlocking {
        val sink = Collections.synchronizedList(mutableListOf<Req>())
        val f = features(sink, post = { body, _ ->
            if (body["userId"] == "A") snapshot(7, "x") else HttpResponse(503, emptyMap())
        })
        f.load("A")
        f.load("B")
        f.poll()

        val poll = sink.last { it.url.contains("/features/poll") }
        assertTrue(poll.url, poll.url.contains("since_version=0"))
        f.stopPolling()
    }

    @Test
    fun `poll answer for the confirmed identity keeps the cursor`() = runBlocking {
        val sink = Collections.synchronizedList(mutableListOf<Req>())
        val f = features(sink, post = { _, _ -> snapshot(7, "x") })
        f.load("A")
        f.poll()
        assertTrue(sink.last().url.contains("since_version=7"))
        assertEquals(true, f.isReady)
        f.stopPolling()
    }
}

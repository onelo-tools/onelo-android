package com.onelo.android

import com.onelo.android.internal.HttpResponse
import com.onelo.android.internal.OneloEventStream
import com.onelo.android.internal.SingleFlight
import com.onelo.android.internal.UserTokenEpisode
import com.onelo.android.internal.UserTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

/**
 * #519 — every Features call proves the signed-in user with
 * `Authorization: Bearer <Onelo access token>`, the same rules as the JS SDKs
 * (#518): a refused token (`401 invalid_user_token`) is refreshed once per
 * refusal episode and retried once, then the call goes anonymous (no token, no
 * `userId`); module-token never goes anonymous. `userIdHash` is never sent.
 */
class UserTokenTest {

    private class Req(val url: String, val body: Map<String, Any?>?, val headers: Map<String, String>) {
        val authorization: String? get() = headers["Authorization"]
    }

    /** Token source the test controls and counts. */
    private class Tokens(@Volatile var current: String?, @Volatile var refreshed: String? = null) {
        @Volatile var refreshCount = 0
        var onRefresh: (() -> Unit)? = null
        val source = UserTokenSource(
            readCurrent = { current },
            readRefreshed = { refreshCount++; onRefresh?.invoke(); refreshed },
        )
    }

    private val refused = HttpResponse(401, mapOf("detail" to mapOf("error" to "invalid_user_token")))
    private val ok = HttpResponse(200, mapOf("features" to emptyMap<String, Any?>()))

    private fun features(
        tokens: Tokens?,
        sink: MutableList<Req>,
        answer: (url: String, auth: String?) -> HttpResponse,
    ): OneloFeatures = OneloFeatures(
        OneloConfig(publishableKey = "onelo_pk_live_abc", apiUrl = "https://example.test"),
        httpGet = { url, headers ->
            sink += Req(url, null, headers)
            answer(url, headers["Authorization"])
        },
        httpPost = { url, body, headers ->
            sink += Req(url, body, headers)
            answer(url, headers["Authorization"])
        },
        userToken = tokens?.source,
    )

    private fun List<Req>.at(path: String) = filter { it.url.contains(path) }

    // ── The Bearer on every call ─────────────────────────────────────────────

    @Test
    fun `signed in - every Features call carries the Bearer and never a userIdHash`() = runBlocking {
        val tokens = Tokens("tok-1")
        val sink = Collections.synchronizedList(mutableListOf<Req>())
        val f = features(tokens, sink) { url, _ ->
            if (url.contains("module-token")) HttpResponse(200, mapOf("token" to "mt")) else ok
        }
        f.userId = "u1"
        f.declare(listOf("a"))

        f.resolve()
        f.batchPing()
        f.poll()
        f.moduleToken("slug")

        for (path in listOf("/features/resolve", "/features/batch-ping", "/features/poll", "/features/module-token")) {
            val reqs = sink.at(path)
            assertTrue("no $path request", reqs.isNotEmpty())
            reqs.forEach { r ->
                assertEquals(path, "Bearer tok-1", r.authorization)
                assertFalse(path, r.url.contains("userIdHash"))
                assertFalse(path, r.body?.containsKey("userIdHash") == true)
            }
        }
        assertEquals("u1", sink.at("/features/resolve").single().body!!["userId"])
        assertTrue(sink.at("/features/poll").single().url.contains("userId=u1"))
        assertEquals(0, tokens.refreshCount)
    }

    @Test
    fun `signed out - no token and no userId`() = runBlocking {
        val sink = Collections.synchronizedList(mutableListOf<Req>())
        val f = features(Tokens(null), sink) { _, _ -> ok }
        f.declare(listOf("a"))

        f.resolve()
        f.batchPing()
        f.poll()

        assertEquals(3, sink.count { it.url.contains("/features/") })
        sink.forEach { r ->
            assertNull(r.authorization)
            assertFalse(r.url.contains("userId="))
            assertFalse(r.body?.containsKey("userId") == true)
        }
    }

    // ── Refusal: refresh once, retry once, then anonymous ────────────────────

    @Test
    fun `refused token - refresh once, retry once, then never anonymous while signed in`() = runBlocking {
        val tokens = Tokens("stale", refreshed = "fresh")
        val sink = Collections.synchronizedList(mutableListOf<Req>())
        val f = features(tokens, sink) { _, auth -> if (auth == null) ok else refused }
        f.userId = "u1"

        f.resolve()

        val reqs = sink.at("/features/resolve")
        // #737: an anonymous answer would replace the signed-in user's snapshot.
        assertEquals(listOf("Bearer stale", "Bearer fresh"), reqs.map { it.authorization })
        assertEquals("u1", reqs[1].body!!["userId"])
        assertFalse(f.isReady)
        assertEquals(1, tokens.refreshCount)
    }

    @Test
    fun `refused poll never goes anonymous while signed in`() = runBlocking {
        val tokens = Tokens("stale", refreshed = "fresh")
        val sink = Collections.synchronizedList(mutableListOf<Req>())
        val f = features(tokens, sink) { _, auth ->
            if (auth == null) HttpResponse(200, mapOf("config_version" to 1, "up_to_date" to true)) else refused
        }
        f.userId = "u1"

        f.poll()

        val reqs = sink.at("/features/poll")
        assertEquals(listOf("Bearer stale", "Bearer fresh"), reqs.map { it.authorization })
    }

    @Test
    fun `a 401 that is not invalid_user_token is not retried`() = runBlocking {
        val tokens = Tokens("tok", refreshed = "fresh")
        val sink = Collections.synchronizedList(mutableListOf<Req>())
        val f = features(tokens, sink) { _, _ -> HttpResponse(401, mapOf("detail" to mapOf("error" to "invalid_publishable_key"))) }
        f.userId = "u1"

        f.resolve()

        assertEquals(1, sink.at("/features/resolve").size)
        assertEquals(0, tokens.refreshCount)
    }

    @Test
    fun `module-token refused twice throws NOT_AUTHENTICATED and never goes anonymous`() = runBlocking {
        val tokens = Tokens("stale", refreshed = "fresh")
        val sink = Collections.synchronizedList(mutableListOf<Req>())
        val f = features(tokens, sink) { _, _ -> refused }
        f.userId = "u1"

        try {
            f.moduleToken("slug")
            fail("expected a throw")
        } catch (e: OneloError) {
            assertEquals(OneloError.Code.NOT_AUTHENTICATED, e.code)
        }
        assertEquals(listOf("Bearer stale", "Bearer fresh"), sink.at("/features/module-token").map { it.authorization })
        assertEquals(1, tokens.refreshCount)
    }

    // ── One refresh per refusal episode ──────────────────────────────────────

    @Test
    fun `a spent episode does not refresh again until a token is accepted`() = runBlocking {
        val tokens = Tokens("stale", refreshed = "still-refused")
        val sink = Collections.synchronizedList(mutableListOf<Req>())
        var refuse = true
        val f = features(tokens, sink) { _, auth -> if (auth != null && refuse) refused else ok }
        f.userId = "u1"

        f.resolve()
        f.resolve()
        assertEquals("the second refusal in the same episode must not refresh", 1, tokens.refreshCount)
        assertEquals(
            listOf("Bearer stale", "Bearer still-refused", "Bearer stale"),
            sink.at("/features/resolve").map { it.authorization },
        )

        refuse = false
        f.resolve() // accepted → the episode is over
        refuse = true
        f.resolve()
        assertEquals("after an accepted token a new refusal may refresh again", 2, tokens.refreshCount)
    }

    /** Review trap R2-1 of #530: batch-ping answers a throttled 204 before it looks
     *  at the token, so its 2xx must not end a refusal episode. */
    @Test
    fun `a throttled batch-ping 204 does not re-arm the refresh`() = runBlocking {
        val tokens = Tokens("stale", refreshed = "still-refused")
        val sink = Collections.synchronizedList(mutableListOf<Req>())
        val f = features(tokens, sink) { url, auth ->
            when {
                url.contains("batch-ping") -> HttpResponse(204, emptyMap())
                auth != null -> refused
                else -> ok
            }
        }
        f.userId = "u1"
        f.declare(listOf("a"))

        f.resolve()
        assertEquals(1, tokens.refreshCount)
        f.batchPing()
        assertEquals("Bearer stale", sink.at("/features/batch-ping").first().authorization)
        assertTrue(tokens.source.refreshSpentForTesting)
        f.resolve()
        assertEquals(1, tokens.refreshCount)
    }

    /** Review trap R1-1 of #530: `Onelo` reloads features on every auth emission,
     *  and a refresh emits. Against a server that refuses every token this must
     *  not loop refresh → load → refresh. */
    @Test
    fun `a server refusing every token with a reload on every refresh is bounded`() = runBlocking {
        val tokens = Tokens("t0", refreshed = "t1")
        val sink = Collections.synchronizedList(mutableListOf<Req>())
        val f = features(tokens, sink) { _, auth -> if (auth != null) refused else ok }
        f.userId = "u1"
        val reloads = Collections.synchronizedList(mutableListOf<Job>())
        tokens.onRefresh = {
            // Without the episode gate every reload refreshes again, forever; the
            // cap only keeps a broken build from hanging the suite.
            if (reloads.size < 20) reloads += launch { f.resolve() }
        }

        f.resolve()
        while (reloads.any { !it.isCompleted }) reloads.toList().joinAll()

        assertEquals(1, tokens.refreshCount)
        assertEquals(1, reloads.size)
        // Two resolves (the original + the one reload), at most three requests each.
        assertTrue(sink.at("/features/resolve").size <= 6)
    }

    /** `Onelo`'s auth bridge, end to end minus the Context: every emission goes
     *  through [UserTokenEpisode] and then reloads features, exactly as the
     *  `onAuthStateChange()` collector does — and a refresh re-emits the same user. */
    @Test
    fun `the auth bridge - a refresh re-emitting the same user does not loop`() = runBlocking {
        val tokens = Tokens("t0", refreshed = "t1")
        val sink = Collections.synchronizedList(mutableListOf<Req>())
        val f = features(tokens, sink) { _, auth -> if (auth != null) refused else ok }
        val episode = UserTokenEpisode(tokens.source)
        val reloads = Collections.synchronizedList(mutableListOf<Job>())
        fun emit(userId: String?) {
            episode.onSession(userId)
            f.userId = userId
            reloads += launch { f.resolve() }
        }
        tokens.onRefresh = {
            // A successful refresh saves the session → the same user is emitted,
            // after the refresh has finished (OneloAuth emits from its own scope).
            if (reloads.size < 20) reloads += launch { delay(50); emit("u1") }
        }

        emit("u1") // sign-in
        while (reloads.any { !it.isCompleted }) reloads.toList().joinAll()

        assertEquals(1, tokens.refreshCount)
        // sign-in reload, the delayed re-emission, and its reload
        assertEquals(3, reloads.size)
    }

    @Test
    fun `the auth bridge - only a user change starts a new episode`() = runBlocking {
        val tokens = Tokens("stale", refreshed = null)
        val episode = UserTokenEpisode(tokens.source)
        episode.onSession("u1")
        tokens.source.refreshAfterRefusal()
        assertTrue(tokens.source.refreshSpentForTesting)

        episode.onSession("u1")
        assertTrue("the same user re-emitted keeps the episode", tokens.source.refreshSpentForTesting)
        episode.onSession("u2")
        assertFalse("a different user starts a new episode", tokens.source.refreshSpentForTesting)
        tokens.source.refreshAfterRefusal()
        episode.onSession(null)
        assertFalse("signing out starts a new episode", tokens.source.refreshSpentForTesting)
    }

    // ── Single-flight session refresh (OneloAuth.refreshSession) ─────────────

    @Test
    fun `overlapping refreshes share one request`() = runBlocking {
        val flight = SingleFlight<String?>(CoroutineScope(Dispatchers.IO + SupervisorJob()))
        var requests = 0
        val refresh: suspend () -> String? = { requests++; delay(100); "session-$requests" }

        val results = listOf(async { flight.run(refresh) }, async { flight.run(refresh) }).awaitAll()

        assertEquals("two overlapping refreshes must send ONE /auth/refresh", 1, requests)
        assertEquals(listOf("session-1", "session-1"), results)
        assertEquals("a later refresh runs again", "session-2", flight.run(refresh))
    }

    // ── Event Stream ─────────────────────────────────────────────────────────

    /** Scripted connection: records the request headers, answers [status] + [body]. */
    private class FakeConn(url: URL, private val status: Int, private val body: String) : HttpURLConnection(url) {
        val sent = mutableMapOf<String, String>()
        override fun setRequestProperty(key: String, value: String) { sent[key] = value }
        override fun getResponseCode(): Int = status
        override fun getInputStream(): InputStream =
            if (status == 200) body.byteInputStream() else throw IOException("HTTP $status")
        override fun getErrorStream(): InputStream? = if (status == 200) null else body.byteInputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
    }

    private class Opened(val url: String, val authorization: String?)

    private val refusedBody = """{"detail":{"error":"invalid_user_token"}}"""
    private val okBody = "event: up_to_date\ndata: {}\n\n"

    private fun stream(tokens: Tokens, opened: MutableList<Opened>, answer: (auth: String?) -> Pair<Int, String>) =
        OneloEventStream(
            apiUrl = "https://example.test",
            publishableKey = "onelo_pk_live_abc",
            instanceId = "inst-1",
            sdkVersion = "1.42.0",
            environment = null,
            userToken = tokens.source,
            openConnection = { url ->
                object : HttpURLConnection(url) {
                    private var inner: FakeConn? = null
                    private val sent = mutableMapOf<String, String>()
                    private fun conn(): FakeConn = inner ?: run {
                        val (status, body) = answer(sent["Authorization"])
                        opened += Opened(url.toString(), sent["Authorization"])
                        FakeConn(url, status, body).also { inner = it }
                    }
                    override fun setRequestProperty(key: String, value: String) { sent[key] = value }
                    override fun getResponseCode(): Int = conn().responseCode
                    override fun getInputStream(): InputStream = conn().inputStream
                    override fun getErrorStream(): InputStream? = conn().errorStream
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy(): Boolean = false
                }
            },
        )

    private fun waitUntil(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test
    fun `stream - connect carries the Bearer and every reconnect reads a fresh token`() {
        val tokens = Tokens("tok-1")
        val opened = Collections.synchronizedList(mutableListOf<Opened>())
        val s = stream(tokens, opened) { 200 to okBody }

        s.start("u1")
        waitUntil { opened.size >= 1 }
        tokens.current = "tok-2"
        waitUntil { opened.size >= 2 }
        s.stop()

        assertEquals("Bearer tok-1", opened[0].authorization)
        assertEquals("Bearer tok-2", opened[1].authorization)
        assertTrue(opened[0].url.contains("userId=u1"))
        assertFalse(opened[0].url.contains("userIdHash"))
    }

    @Test
    fun `stream - refused token refreshes once, then connects anonymous without a userId`() {
        val tokens = Tokens("stale", refreshed = "fresh")
        val opened = Collections.synchronizedList(mutableListOf<Opened>())
        val s = stream(tokens, opened) { auth -> if (auth == null) 200 to okBody else 401 to refusedBody }

        s.start("u1")
        waitUntil { opened.size >= 5 } // first connect (3 opens) + a reconnect
        s.stop()

        assertEquals(listOf("Bearer stale", "Bearer fresh", null), opened.take(3).map { it.authorization })
        assertTrue(opened[1].url.contains("userId=u1"))
        assertFalse("the anonymous connection names no user", opened[2].url.contains("userId="))
        // The reconnect tries the user again, but the spent episode does not refresh.
        assertEquals("Bearer stale", opened[3].authorization)
        assertNull(opened[4].authorization)
        assertEquals(1, tokens.refreshCount)
    }

    @Test
    fun `stream - an accepted refreshed token ends the episode`() {
        val tokens = Tokens("stale", refreshed = "fresh")
        val opened = Collections.synchronizedList(mutableListOf<Opened>())
        var refusedOnce = false
        val s = stream(tokens, opened) { auth ->
            if (auth == "Bearer stale" && !refusedOnce) { refusedOnce = true; 401 to refusedBody } else 200 to okBody
        }

        s.start("u1")
        waitUntil { opened.size >= 2 && !tokens.source.refreshSpentForTesting }
        s.stop()

        assertEquals(listOf("Bearer stale", "Bearer fresh"), opened.take(2).map { it.authorization })
        assertFalse(tokens.source.refreshSpentForTesting)
        assertEquals(1, tokens.refreshCount)
    }
}

package com.onelo.android

import com.onelo.android.internal.HttpResponse
import com.onelo.android.internal.OneloEventStream
import com.onelo.android.internal.UserTokenSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

/** #737 — stream frames land only on the identity their connection is bound to. */
class FeaturesStreamIdentityTest {

    private fun snapshot(vararg enabled: String): Map<String, Any?> =
        mapOf("config_version" to 4, "features" to enabled.associateWith { mapOf("status" to "enabled") })

    private fun features(post: (Map<String, Any?>) -> HttpResponse = { HttpResponse(503, emptyMap()) }) = OneloFeatures(
        OneloConfig(publishableKey = "onelo_pk_live_abc", apiUrl = "https://example.test"),
        httpGet = { _, _ -> HttpResponse(503, emptyMap()) },
        httpPost = { _, body, _ -> post(body) },
    )

    @Test
    fun `a frame from an anonymous connection never lands on a signed-in user`() = runBlocking {
        val f = features()
        f.load("u1")
        f.applySseSnapshot(snapshot("global-only"), streamUserId = null)

        assertFalse(f.feature("global-only").isEnabled())
        assertFalse(f.isReady)
        f.stopPolling()
    }

    @Test
    fun `a frame from the user's own connection is applied and confirms`() = runBlocking {
        val f = features()
        f.load("u1")
        f.applySseSnapshot(snapshot("pro"), streamUserId = "u1")

        assertTrue(f.feature("pro").isEnabled())
        assertTrue(f.isReady)
        f.stopPolling()
    }

    @Test
    fun `up_to_date confirms nothing for an unconfirmed identity`() = runBlocking {
        val f = features()
        f.load("u1")
        f.applySseSnapshot(mapOf("config_version" to 4), streamUserId = "u1")
        assertFalse(f.isReady)
        f.stopPolling()
    }

    @Test
    fun `signed out - no Bearer is sent even when a token source exists`() = runBlocking {
        val auths = Collections.synchronizedList(mutableListOf<String?>())
        val f = OneloFeatures(
            OneloConfig(publishableKey = "onelo_pk_live_abc", apiUrl = "https://example.test"),
            httpPost = { _, _, h -> auths += h["Authorization"]; HttpResponse(200, snapshot("x")) },
            userToken = UserTokenSource(readCurrent = { "someone-elses-token" }, readRefreshed = { null }),
        )
        f.load(null)
        assertEquals(listOf<String?>(null), auths)
        assertTrue(f.isReady)
        f.stopPolling()
    }

    // ── OneloEventStream.connectionUserId ────────────────────────────────────

    private class FakeConn(url: URL, private val status: Int, private val body: String) : HttpURLConnection(url) {
        override fun getResponseCode(): Int = status
        override fun getInputStream(): InputStream =
            if (status == 200) body.byteInputStream() else throw IOException("HTTP $status")
        override fun getErrorStream(): InputStream? = if (status == 200) null else body.byteInputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
    }

    private fun boundUsers(answer: (auth: String?) -> Int): List<String?> {
        val seen = Collections.synchronizedList(mutableListOf<String?>())
        lateinit var s: OneloEventStream
        s = OneloEventStream(
            apiUrl = "https://example.test", publishableKey = "onelo_pk_live_abc", instanceId = "i",
            sdkVersion = "1", environment = null,
            userToken = UserTokenSource(readCurrent = { "stale" }, readRefreshed = { "fresh" }),
            openConnection = { url ->
                object : HttpURLConnection(url) {
                    private val sent = mutableMapOf<String, String>()
                    private var inner: FakeConn? = null
                    private fun c() = inner ?: run {
                        val st = answer(sent["Authorization"])
                        FakeConn(url, st, if (st == 200) "event: connected\ndata: {}\n\n" else """{"detail":{"error":"invalid_user_token"}}""").also { inner = it }
                    }
                    override fun setRequestProperty(key: String, value: String) { sent[key] = value }
                    override fun getResponseCode() = c().responseCode
                    override fun getInputStream() = c().inputStream
                    override fun getErrorStream() = c().errorStream
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                }
            },
        )
        s.on("connected") { seen += s.connectionUserId ?: "<anon>" }
        s.start("u1")
        val deadline = System.currentTimeMillis() + 5_000
        while (seen.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        s.stop()
        assertNull(s.connectionUserId)
        return seen.toList()
    }

    @Test
    fun `stream - a user connection reports its user`() {
        assertEquals("u1", boundUsers { 200 }.first())
    }

    @Test
    fun `stream - the refused-token anonymous fallback reports no user`() {
        assertEquals("<anon>", boundUsers { auth -> if (auth == null) 200 else 401 }.first())
    }
}

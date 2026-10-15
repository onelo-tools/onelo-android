package com.onelo.android

import com.onelo.android.internal.HttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wiring test for [OneloFeatures.poll] (#105, ADR-0003).
 *
 * [OneloFeaturesEnvironmentTest] proves `pollParams()` and `pollHeaders()` are
 * each right in isolation. That leaves the one line that joins them — the
 * `HttpClient.get(url, headers)` call inside `poll()` — uncovered: swapping
 * `pollHeaders()` for the bare `securityHeaders` there keeps every unit test
 * green while the credential silently vanishes from the wire. This test drives
 * a real `poll()` tick through the injected `httpGet` seam and asserts on the
 * REQUEST the transport receives, not on the helpers.
 */
class OneloFeaturesPollWiringTest {

    private class Captured(val url: String, val headers: Map<String, String>)

    private fun featuresCapturing(sink: MutableList<Captured>): OneloFeatures =
        OneloFeatures(
            OneloConfig(
                publishableKey = "onelo_pk_test_abc",
                apiUrl = "https://example.test",
                featureEnvironment = "test",
            ),
            httpGet = { url, headers ->
                sink += Captured(url, headers)
                HttpResponse(200, mapOf("config_version" to 1, "up_to_date" to true))
            },
        )

    /** `load()` is the production writer of `securityHeaders`, but it also fires
     *  resolve/batch-ping over the real HttpClient; set the field directly instead. */
    private fun setSecurityHeaders(f: OneloFeatures, headers: Map<String, String>) {
        val field = OneloFeatures::class.java.getDeclaredField("securityHeaders")
        field.isAccessible = true
        field.set(f, headers)
    }

    @Test
    fun `poll sends the key as X-Publishable-Key and never in the URL`() = runTest {
        val sink = mutableListOf<Captured>()
        val f = featuresCapturing(sink)
        f.userId = "u1"

        f.poll()

        assertEquals(1, sink.size)
        val req = sink.single()
        assertTrue(req.url.startsWith("https://example.test/api/sdk/features/poll?"))
        assertTrue(req.url.contains("since_version=0"))
        assertTrue(req.url.contains("userId=u1"))
        assertTrue(req.url.contains("environment=test"))
        assertFalse("credential must not be in the query string", req.url.contains("key="))
        assertFalse(req.url.contains("onelo_pk_test_abc"))
        assertEquals("onelo_pk_test_abc", req.headers["X-Publishable-Key"])
    }

    @Test
    fun `poll keeps the security headers alongside the credential`() = runTest {
        val sink = mutableListOf<Captured>()
        val f = featuresCapturing(sink)
        setSecurityHeaders(f, mapOf("X-Onelo-Instance-Id" to "inst-1", "X-Bundle-Id" to "com.example"))

        f.poll()

        val headers = sink.single().headers
        assertEquals("inst-1", headers["X-Onelo-Instance-Id"])
        assertEquals("com.example", headers["X-Bundle-Id"])
        assertNotNull("the credential must survive the merge with securityHeaders", headers["X-Publishable-Key"])
        assertEquals("onelo_pk_test_abc", headers["X-Publishable-Key"])
    }
}

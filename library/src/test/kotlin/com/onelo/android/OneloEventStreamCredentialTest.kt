package com.onelo.android

import com.onelo.android.internal.OneloEventStream
import org.junit.Assert.*
import org.junit.Test

/**
 * ADR-0003: the Event Stream's credential is an `X-Publishable-Key` request
 * header for every SDK but browser JS. A key in the query string lands in the
 * reverse proxy's access.log on every connect, and the backend stamps such a
 * connection with RFC 8594 `Deprecation`/`Sunset`.
 */
class OneloEventStreamCredentialTest {

    private fun stream(environment: String? = null) = OneloEventStream(
        apiUrl = "https://example.test",
        publishableKey = "onelo_pk_test_abc123",
        instanceId = "inst-1",
        sdkVersion = "1.41.0",
        environment = environment,
        securityHeaders = { mapOf("X-Bundle-Id" to "com.example.app") },
    )

    @Test
    fun `the publishable key travels as a header`() {
        val headers = stream().streamHeaders()
        assertEquals("onelo_pk_test_abc123", headers["X-Publishable-Key"])
    }

    @Test
    fun `the publishable key never appears in the query string`() {
        val query = stream().streamQuery(forUser = "u1")
        assertFalse(query.split("&").any { it.startsWith("key=") })
        assertFalse(query.contains("onelo_pk_test_abc123"))
    }

    @Test
    fun `the rest of the query string is unchanged`() {
        val query = stream(environment = "test").streamQuery(forUser = "u1")
        assertTrue(query.contains("userId=u1"))
        assertTrue(query.contains("instance_id=inst-1"))
        assertTrue(query.contains("sdk_platform=android"))
        assertTrue(query.contains("sdk_version=1.41.0"))
        assertTrue(query.contains("environment=test"))
        // No leading `&` — the first param must not be orphaned by the removal.
        assertFalse(query.startsWith("&"))
    }

    @Test
    fun `an anonymous connect omits userId and still carries the key header`() {
        val s = stream()
        assertFalse(s.streamQuery(forUser = null).contains("userId="))
        assertEquals("onelo_pk_test_abc123", s.streamHeaders()["X-Publishable-Key"])
    }

    @Test
    fun `the security and version headers still ride along`() {
        val headers = stream().streamHeaders()
        assertEquals("text/event-stream", headers["Accept"])
        assertEquals("1.41.0", headers["X-Sdk-Version"])
        assertEquals("com.example.app", headers["X-Bundle-Id"])
    }
}

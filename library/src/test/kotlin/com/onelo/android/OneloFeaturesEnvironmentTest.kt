package com.onelo.android

import org.junit.Assert.*
import org.junit.Test

class OneloFeaturesEnvironmentTest {

    private fun featuresWith(env: String?): OneloFeatures =
        OneloFeatures(
            OneloConfig(
                publishableKey = "onelo_pk_test_abc",
                apiUrl = "https://example.test",
                featureEnvironment = env
            )
        )

    @Test
    fun `environment normalizes test and live, anything else to null`() {
        assertEquals("test", featuresWith("test").featureEnvironment)
        assertEquals("live", featuresWith("live").featureEnvironment)
        assertEquals("test", featuresWith("  TEST ").featureEnvironment)
        assertEquals("live", featuresWith("Live").featureEnvironment)
        assertNull(featuresWith(null).featureEnvironment)
        assertNull(featuresWith("").featureEnvironment)
        assertNull(featuresWith("staging").featureEnvironment)
    }

    @Test
    fun `environment present in resolve body when set`() {
        val body = featuresWith("test").resolveBody(userId = "u1")
        assertEquals("test", body["environment"])
        assertEquals("u1", body["userId"])
        assertEquals("onelo_pk_test_abc", body["publishableKey"])
    }

    @Test
    fun `environment absent from resolve body when unset`() {
        val body = featuresWith(null).resolveBody(userId = "u1")
        assertFalse(body.containsKey("environment"))
    }

    @Test
    fun `environment present in batch-ping body when set`() {
        val body = featuresWith("live").batchPingBody(listOf("chat"))
        assertEquals("live", body["environment"])
        assertEquals(listOf("chat"), body["features"])
    }

    @Test
    fun `environment absent from batch-ping body when unset`() {
        val body = featuresWith(null).batchPingBody(listOf("chat"))
        assertFalse(body.containsKey("environment"))
    }

    @Test
    fun `environment present in poll query params when set`() {
        val params = featuresWith("test").pollParams(userId = "u1", version = 3)
        assertTrue(params.contains("&environment=test"))
    }

    @Test
    fun `environment absent from poll query params when unset`() {
        val params = featuresWith(null).pollParams(userId = "u1", version = 3)
        assertFalse(params.contains("environment"))
    }
}

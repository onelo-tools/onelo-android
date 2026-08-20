package com.onelo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The store endpoints return the Apple in-app-store block as a NESTED FastAPI
 * body (`{"detail": {"error": …}}`), while other handlers return a flat `error`
 * or a plain string `detail` — all three must resolve to the same code.
 */
class OneloErrorCodeTest {

    @Test
    fun `nested detail error is extracted`() {
        val body = mapOf<String, Any?>(
            "detail" to mapOf(
                "error" to "in_app_store_not_allowed",
                "message" to "Apple does not allow selling digital content in-app.",
            ),
        )
        assertEquals("in_app_store_not_allowed", extractErrorCode(body))
        assertEquals(
            "Apple does not allow selling digital content in-app.",
            extractErrorMessage(body),
        )
    }

    @Test
    fun `flat error field is extracted`() {
        val body = mapOf<String, Any?>("error" to "paywall_not_enabled")
        assertEquals("paywall_not_enabled", extractErrorCode(body))
        assertNull(extractErrorMessage(body))
    }

    @Test
    fun `string detail is extracted as both code and message`() {
        val body = mapOf<String, Any?>("detail" to "Not Found")
        assertEquals("Not Found", extractErrorCode(body))
        assertEquals("Not Found", extractErrorMessage(body))
    }

    @Test
    fun `unrecognised body yields null instead of throwing`() {
        assertNull(extractErrorCode(emptyMap()))
        assertNull(extractErrorCode(mapOf("detail" to listOf(1, 2, 3))))
        assertNull(extractErrorMessage(mapOf("error" to 42)))
    }

    @Test
    fun `in-app-store factory carries the dedicated code`() {
        val err = OneloError.inAppStoreNotAllowed("blocked")
        assertEquals(OneloError.Code.IN_APP_STORE_NOT_ALLOWED, err.code)
        assertEquals("blocked", err.message)
    }
}

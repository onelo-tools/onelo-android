package com.onelo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #548 — what a refused `/api/sdk/auth/refresh` tells the app. Only a ban is
 * "suspended"; a replaced (`session_compromised`), expired or unknown session
 * is a neutral "session ended"; a lapsed plan stays plan-accurate.
 */
class RefreshRefusalTest {

    private fun refusal(detail: Map<String, Any?>) = refreshRefusalError(mapOf("detail" to detail))

    @Test
    fun `session codes map to the neutral session ended error`() {
        for (code in listOf("session_compromised", "session_expired", "session_invalid")) {
            val err = refusal(mapOf("error" to code))
            assertEquals(code, OneloError.Code.SESSION_ENDED, err.code)
            assertEquals("Your session ended — please sign in again", err.message)
            assertFalse(err.message!!.contains("suspended", ignoreCase = true))
            assertTrue(err.endsSession())
        }
    }

    @Test
    fun `only a ban says suspended`() {
        val err = refusal(mapOf("error" to "banned"))
        assertEquals(OneloError.Code.USER_REVOKED, err.code)
        assertEquals("This user account has been suspended", err.message)
    }

    @Test
    fun `no plan available is plan accurate`() {
        val err = refusal(mapOf("error_code" to "no_plan_available", "message" to "x"))
        assertEquals(OneloError.Code.NO_ACTIVE_PLAN, err.code)
    }

    @Test
    fun `an unknown refusal never claims an account action`() {
        assertEquals(OneloError.Code.SESSION_ENDED, refreshRefusalError(emptyMap()).code)
    }

    @Test
    fun `a transient error does not end the session`() {
        assertFalse(OneloError.network("offline").endsSession())
    }

    @Test
    fun `transient retry backs off and the first retry lands inside the 30 s grace window`() {
        assertEquals(5_000L, refreshRetryDelayMs(0))
        assertTrue(refreshRetryDelayMs(0) < 30_000L)
        assertEquals(10_000L, refreshRetryDelayMs(1))
        assertEquals(40_000L, refreshRetryDelayMs(3))
        assertEquals(60_000L, refreshRetryDelayMs(10))
        assertEquals(60_000L, refreshRetryDelayMs(99))
    }
}

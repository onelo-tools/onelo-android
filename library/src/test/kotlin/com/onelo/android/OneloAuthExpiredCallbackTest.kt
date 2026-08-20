package com.onelo.android

import org.junit.Assert.*
import org.junit.Test

/**
 * `?error=invalid_token` must be classified as EXPIRED, never as a cancel.
 *
 * It is what "Use a different account" on the no-plan page sends after signing
 * the user out, and what an idle expiry sends. Taking the cancel path CLOSED the
 * auth Activity on the user; the correct response is to re-resolve the flow and
 * load a clean sign-in page. Parity with Swift's `onSessionExpired`.
 */
class OneloAuthExpiredCallbackTest {

    @Test
    fun `the three expiry codes are recognised`() {
        assertTrue(OneloAuthActivity.isExpiredAuthError("invalid_token"))
        assertTrue(OneloAuthActivity.isExpiredAuthError("expired_token"))
        assertTrue(OneloAuthActivity.isExpiredAuthError("token_expired"))
    }

    @Test
    fun `a genuine failure is NOT expiry`() {
        // Not a catch-all — reloading on every error would loop on a real failure.
        assertFalse(OneloAuthActivity.isExpiredAuthError("attest_invalid"))
        assertFalse(OneloAuthActivity.isExpiredAuthError("access_denied"))
    }

    @Test
    fun `absent values do not claim expiry`() {
        assertFalse(OneloAuthActivity.isExpiredAuthError(null))
        assertFalse(OneloAuthActivity.isExpiredAuthError(""))
    }
}

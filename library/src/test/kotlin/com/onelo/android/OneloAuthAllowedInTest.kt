package com.onelo.android

import org.junit.Assert.*
import org.junit.Test

/**
 * The Access Gate answer, isolated from session/storage lookup.
 *
 * Android never read `user.allowed_in` from the backend at all (found
 * 2026-08-20, while updating the dashboard's Android snippet) — `hasActiveAccess`
 * only ever checked the raw entitlement, so this SDK had no equivalent of
 * `isAllowedIn()` on Swift/Electron/RN/JS. Those four each independently
 * re-derived `!paywallEnabled || entitlement == ACTIVE` locally at some point and
 * were each found wrong on a different day — the whole reason the server ships
 * `allowed_in` directly. These tests pin the same contract for Android.
 */
class OneloAuthAllowedInTest {

    private fun decide(allowedIn: Boolean?, paywallEnabled: Boolean, entitlement: OneloEntitlement) =
        OneloAuth.allowedInDecision(allowedIn, paywallEnabled, entitlement)

    @Test
    fun `a server YES wins even when the local rule would say no`() {
        // Contradictory on purpose: paywall on, no entitlement — the old local
        // rule would refuse. The server may know about a grant this client
        // hasn't seen yet, and the server decides.
        assertTrue(decide(allowedIn = true, paywallEnabled = true, entitlement = OneloEntitlement.NONE))
    }

    @Test
    fun `a server NO wins even when the local rule would say yes`() {
        assertFalse(decide(allowedIn = false, paywallEnabled = false, entitlement = OneloEntitlement.ACTIVE))
    }

    @Test
    fun `falls back to the local rule ONLY when the server has not answered (older backend)`() {
        assertTrue(decide(allowedIn = null, paywallEnabled = false, entitlement = OneloEntitlement.NONE))
        assertTrue(decide(allowedIn = null, paywallEnabled = true, entitlement = OneloEntitlement.ACTIVE))
        assertFalse(decide(allowedIn = null, paywallEnabled = true, entitlement = OneloEntitlement.NONE))
    }
}

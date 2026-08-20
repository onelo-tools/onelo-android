package com.onelo.android

import org.junit.Assert.*
import org.junit.Test

/**
 * `/api/sdk/flow/init` response → routing decision.
 *
 * Until 1.37.0 this SDK called `/api/sdk/auth/initiate` directly. That endpoint
 * answers exactly one question — "give me a sign-in page" — so a signed-in user
 * with no plan was handed the form they had already completed, and the routing
 * was left to whatever the host app improvised. `/flow/init` makes the decision
 * once, server-side, for every SDK.
 *
 * These test `decisionFromFlow` rather than `resolveFlow` because `HttpClient` is
 * an object with a direct `openConnection` and `OneloAuth` needs a `Context` —
 * exercising the public method would mean adding Robolectric. `decisionFromFlow`
 * holds every rule that can regress silently, and each case below fails if the
 * rule is changed.
 */
class OneloAuthFlowRoutingTest {

    private fun decide(status: Int, body: Map<String, Any?>) =
        OneloAuth.decisionFromFlow(status, body)

    // ── The two real outcomes ────────────────────────────────────────────────

    @Test
    fun `authorized means open nothing`() {
        assertEquals(OneloAuth.FlowDecision.Authorized, decide(200, mapOf("action" to "authorized")))
    }

    @Test
    fun `present carries the url`() {
        val d = decide(200, mapOf(
            "action" to "present",
            "surface" to "sign_in",
            "url" to "https://x/auth/hosted?token=t",
        )) as OneloAuth.FlowDecision.Present
        assertEquals("https://x/auth/hosted?token=t", d.url)
        assertEquals("sign_in", d.surface)
    }

    @Test
    fun `the no_plan surface is routed like any other — the SDK opens what it is handed`() {
        // This is the surface that did not exist for Android before: a signed-in
        // user with no plan now lands on an honest "No active plan" page instead
        // of the sign-in form they already completed.
        val d = decide(200, mapOf(
            "action" to "present",
            "surface" to "no_plan",
            "url" to "https://x/no-plan/hosted?token=t",
        )) as OneloAuth.FlowDecision.Present
        assertEquals("no_plan", d.surface)
        assertTrue(d.url.contains("/no-plan/hosted"))
    }

    @Test
    fun `an unknown future surface round-trips instead of throwing`() {
        // The SDK must never branch on `surface` to pick a URL. If it did, a
        // surface added server-side would break every already-shipped client.
        val d = decide(200, mapOf(
            "action" to "present", "surface" to "something_new", "url" to "https://x/new",
        )) as OneloAuth.FlowDecision.Present
        assertEquals("something_new", d.surface)
    }

    @Test
    fun `a missing surface is tolerated — only the url is load-bearing`() {
        val d = decide(200, mapOf("action" to "present", "url" to "https://x/auth"))
            as OneloAuth.FlowDecision.Present
        assertEquals("", d.surface)
    }

    // ── Fallback: ONLY when the endpoint is absent ───────────────────────────

    @Test
    fun `404 and 405 fall back to the legacy path`() {
        assertNull(decide(404, mapOf("detail" to "Not Found")))
        assertNull(decide(405, emptyMap()))
    }

    @Test
    fun `403 throws instead of falling back`() {
        // The regression this guards: quietly retrying on /auth/initiate would
        // hand a revoked device a sign-in form and hide the real reason.
        val e = assertThrows(OneloError::class.java) {
            decide(403, mapOf("error" to "attest_invalid"))
        }
        assertTrue(e.message!!.contains("attest_invalid"))
    }

    @Test
    fun `500 throws instead of falling back`() {
        assertThrows(OneloError::class.java) { decide(500, mapOf("detail" to "boom")) }
    }

    @Test
    fun `the backend reason is surfaced, not a bare status`() {
        // A developer with a paywall on but no store configured must see WHY.
        val e = assertThrows(OneloError::class.java) {
            decide(400, mapOf("detail" to "store_not_configured"))
        }
        assertTrue(e.message!!.contains("store_not_configured"))
    }

    // ── Broken contracts are errors, not absent endpoints ────────────────────

    @Test
    fun `a 2xx with an unknown action throws rather than falling back`() {
        assertThrows(OneloError::class.java) { decide(200, mapOf("something" to "else")) }
    }

    @Test
    fun `present without a url throws`() {
        assertThrows(OneloError::class.java) { decide(200, mapOf("action" to "present")) }
    }

    @Test
    fun `present with a blank url throws rather than opening about-blank`() {
        assertThrows(OneloError::class.java) {
            decide(200, mapOf("action" to "present", "url" to ""))
        }
    }
}

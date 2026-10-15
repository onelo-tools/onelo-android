package com.onelo.android

import android.app.Activity
import com.onelo.android.internal.FlowPkce
import com.onelo.android.internal.KeyValueStore
import com.onelo.android.internal.Pkce
import org.junit.Assert.*
import org.junit.Test

/**
 * The hosted flow's PKCE verifier (#541): the challenge on `/flow/init` pairs a
 * magic link with a code (#540), and the verifier must still be there when the
 * code comes back — often after Android killed the process behind the mail app.
 *
 * `OneloAuth` needs a Context and `HttpClient` opens real connections, so these
 * test the pure pieces the flow is built from (as OneloAuthFlowRoutingTest does).
 */
class FlowPkceTest {

    /** Stands in for EncryptedSharedPreferences; survives a "process restart". */
    private class MapStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @Test
    fun `the challenge is the S256 of the persisted verifier`() {
        val store = MapStore()
        val challenge = FlowPkce(store).begin()
        val stored = store.map[FlowPkce.VERIFIER_KEY]
        assertNotNull(stored)
        assertEquals(Pkce.generateCodeChallenge(stored!!), challenge)
    }

    @Test
    fun `a new process exchanges with the stored verifier`() {
        val store = MapStore()
        FlowPkce(store).begin()
        val relaunched = FlowPkce(store)
        assertEquals(store.map[FlowPkce.VERIFIER_KEY], relaunched.pendingVerifier())
    }

    @Test
    fun `a second flow does not invalidate an outstanding link`() {
        val store = MapStore()
        val first = FlowPkce(store).begin()
        assertEquals(first, FlowPkce(store).begin())
    }

    @Test
    fun `a verifier past the link lifetime is replaced`() {
        val store = MapStore()
        var now = 1_000_000L
        val pkce = FlowPkce(store) { now }
        val first = pkce.begin()
        now += FlowPkce.LIFETIME_MS + 1
        assertNotEquals(first, pkce.begin())
    }

    @Test
    fun `an undated or future-dated verifier fails closed`() {
        val store = MapStore()
        val pkce = FlowPkce(store) { 1_000_000L }
        val first = pkce.begin()
        store.remove(FlowPkce.ISSUED_AT_KEY)
        val second = pkce.begin()
        assertNotEquals(first, second)
        store.set(FlowPkce.ISSUED_AT_KEY, (2_000_000L).toString())
        assertNotEquals(second, pkce.begin())
    }

    @Test
    fun `clear removes the verifier and its stamp`() {
        val store = MapStore()
        val pkce = FlowPkce(store)
        pkce.begin()
        pkce.clear()
        assertNull(pkce.pendingVerifier())
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun `sign-out storage keys include the flow verifier`() {
        assertTrue(com.onelo.android.internal.SecureStorage.TOKEN_KEYS.contains(FlowPkce.VERIFIER_KEY))
        assertTrue(com.onelo.android.internal.SecureStorage.TOKEN_KEYS.contains(FlowPkce.ISSUED_AT_KEY))
    }

    // ── The exchange ─────────────────────────────────────────────────────────

    @Test
    fun `a paired callback code is exchanged with the stored verifier`() {
        val store = MapStore()
        FlowPkce(store).begin()
        // `turingo://callback?code=paired`, delivered after a relaunch.
        val body = OneloAuth.hostedCallbackBody("pk", "paired", FlowPkce(store).pendingVerifier())
        assertEquals("paired", body["code"])
        assertEquals(store.map[FlowPkce.VERIFIER_KEY], body["code_verifier"])
    }

    @Test
    fun `a missing verifier sends none and the refusal names the reason`() {
        val body = OneloAuth.hostedCallbackBody("pk", "paired", null)
        assertFalse(body.containsKey("code_verifier"))
        val e = OneloAuth.exchangeFailure(401, mapOf("detail" to "code_verifier required"))
        assertEquals(OneloError.Code.SERVER_ERROR, e.code)
        assertTrue(e.message!!.contains("code_verifier required"))
    }

    @Test
    fun `a PKCE mismatch is reported with the backend reason`() {
        val e = OneloAuth.exchangeFailure(401, mapOf("detail" to "PKCE verification failed"))
        assertTrue(e.message!!.contains("PKCE verification failed"))
        assertTrue(OneloAuth.exchangeFailure(500, emptyMap()).message!!.contains("HTTP 500"))
    }

    // ── Closing the sign-in ──────────────────────────────────────────────────

    @Test
    fun `closing the auth Activity is a clean cancel`() {
        assertEquals(
            OneloAuth.LauncherOutcome.Cancelled,
            OneloAuth.launcherOutcome(Activity.RESULT_CANCELED, null, null, false, false),
        )
        assertEquals(
            OneloAuth.LauncherOutcome.Cancelled,
            OneloAuth.launcherOutcome(Activity.RESULT_OK, null, null, false, false),
        )
    }

    @Test
    fun `a returned code is exchanged, not cancelled`() {
        assertEquals(
            OneloAuth.LauncherOutcome.Code("paired"),
            OneloAuth.launcherOutcome(Activity.RESULT_OK, "paired", null, false, false),
        )
        assertEquals(
            OneloAuth.LauncherOutcome.Authorized,
            OneloAuth.launcherOutcome(Activity.RESULT_OK, null, null, false, true),
        )
    }

    @Test
    fun `a native OAuth code goes with its own verifier, a hosted code with the flow one`() {
        // An `oac_` code carries the challenge signInWithOAuth bound, never the flow's.
        assertEquals("oauth", OneloAuth.exchangeVerifier("oauth", "flow", "config"))
        assertEquals("flow", OneloAuth.exchangeVerifier(null, "flow", "config"))
        assertEquals("config", OneloAuth.exchangeVerifier(null, null, "config"))
        assertNull(OneloAuth.exchangeVerifier(null, null, null))
    }
}

package com.onelo.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Navigation policy for social sign-in inside [OneloAuthActivity]'s WebView.
 *
 * Android was the only UI SDK without an OAuth diversion, so Google/Apple blocked
 * the flow inside the embedded WebView (disallowed_useragent). These tests pin
 * both halves: what must escape to the system browser, and what must NOT — an
 * over-eager matcher would push the sign-in page or the deep-link callback out of
 * the app and lose the one-time code.
 */
class OneloOAuthNavigationTest {

    @Test
    fun `diverts the onelo oauth init route for every provider`() {
        assertTrue(isOneloOAuthUrl("https://onelo.tools/api/sdk/auth/oauth/google?token=abc"))
        assertTrue(isOneloOAuthUrl("https://onelo.tools/api/sdk/auth/oauth/github?token=abc"))
        assertTrue(isOneloOAuthUrl("https://onelo.tools/api/sdk/auth/oauth/apple?token=abc"))
    }

    @Test
    fun `diverts provider authorization hosts as a backstop`() {
        assertTrue(isOneloOAuthUrl("https://accounts.google.com/o/oauth2/v2/auth?client_id=x"))
        assertTrue(isOneloOAuthUrl("https://github.com/login/oauth/authorize?client_id=x"))
        assertTrue(isOneloOAuthUrl("https://appleid.apple.com/auth/authorize?client_id=x"))
    }

    @Test
    fun `keeps the hosted sign-in page in-app`() {
        assertFalse(isOneloOAuthUrl("https://onelo.tools/auth/hosted?token=abc"))
        assertFalse(isOneloOAuthUrl("https://onelo.tools/store/hosted?token=abc"))
    }

    @Test
    fun `does not divert the hosted-init token hand-off`() {
        // Server-side hand-off consumed by the SDK, not a page the user visits.
        assertFalse(isOneloOAuthUrl("https://onelo.tools/api/sdk/auth/oauth/hosted-init?provider=google&token=t"))
    }

    @Test
    fun `ignores the deep-link callback scheme`() {
        // Must fall through to the callback branch that extracts the code —
        // diverting it to a browser would drop the sign-in on the floor.
        assertFalse(isOneloOAuthUrl("oneloandroid://callback?code=oac_123"))
    }

    @Test
    fun `ignores malformed input instead of throwing`() {
        assertFalse(isOneloOAuthUrl(""))
        assertFalse(isOneloOAuthUrl("not a url"))
    }

    @Test
    fun `provider host backstop is exact, not a suffix match`() {
        assertFalse(isOneloOAuthUrl("https://accounts.google.com.evil.test/o/oauth2/v2/auth"))
    }

    @Test
    fun `init path matches on any host, including custom auth domains`() {
        // Deliberate: the hosted page can live on st.onelo.tools or on the
        // developer's own auth domain, so the init route is matched by PATH.
        // Host-locking this to onelo.tools would break custom domains. Diverting
        // is safe by construction — the worst case is opening a browser.
        assertTrue(isOneloOAuthUrl("https://auth.acme.example/api/sdk/auth/oauth/google?token=t"))
    }
}

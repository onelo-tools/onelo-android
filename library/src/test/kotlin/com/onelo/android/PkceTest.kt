package com.onelo.android

import com.onelo.android.internal.Pkce
import org.junit.Assert.*
import org.junit.Test

class PkceTest {

    @Test
    fun `verifier is 43 base64url characters`() {
        val verifier = Pkce.generateCodeVerifier()
        assertEquals(43, verifier.length)
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `challenge is different from verifier`() {
        val verifier = Pkce.generateCodeVerifier()
        val challenge = Pkce.generateCodeChallenge(verifier)
        assertNotEquals(verifier, challenge)
    }

    @Test
    fun `challenge is stable for same verifier`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val challenge1 = Pkce.generateCodeChallenge(verifier)
        val challenge2 = Pkce.generateCodeChallenge(verifier)
        assertEquals(challenge1, challenge2)
    }

    @Test
    fun `challenge has no padding or forbidden chars`() {
        val challenge = Pkce.generateCodeChallenge(Pkce.generateCodeVerifier())
        assertFalse(challenge.contains('='))
        assertFalse(challenge.contains('+'))
        assertFalse(challenge.contains('/'))
    }

    @Test
    fun `two verifiers are different`() {
        assertNotEquals(Pkce.generateCodeVerifier(), Pkce.generateCodeVerifier())
    }

    @Test
    fun `challenge matches RFC 7636 test vector`() {
        // RFC 7636 Appendix B test vector
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(expectedChallenge, Pkce.generateCodeChallenge(verifier))
    }
}

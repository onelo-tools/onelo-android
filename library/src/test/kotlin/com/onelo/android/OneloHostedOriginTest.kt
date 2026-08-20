package com.onelo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `oneloHostedOriginHost` gates whether a `gate` deep-link value (Access Gate
 * refusal, e.g. after a magic link) is trusted enough to load into a WebView.
 *
 * Fails closed: only a well-formed `https://` URL yields a host; everything
 * else — a non-https scheme, the callback scheme itself, a malformed value —
 * answers null, which `OneloAuth.isOneloHostedSurface` then refuses.
 */
class OneloHostedOriginTest {

    @Test
    fun `extracts the lowercased host of an https url`() {
        assertEquals("onelo.tools", oneloHostedOriginHost("https://onelo.tools/auth/hosted?token=x"))
        assertEquals("st.onelo.tools", oneloHostedOriginHost("https://ST.ONELO.TOOLS/auth/hosted?reason=no_plan"))
    }

    @Test
    fun `refuses a non-https scheme`() {
        assertNull(oneloHostedOriginHost("http://onelo.tools/auth/hosted"))
        assertNull(oneloHostedOriginHost("oneloandroid://callback?gate=x"))
    }

    @Test
    fun `refuses a malformed or empty-host value`() {
        assertNull(oneloHostedOriginHost("not a url"))
        assertNull(oneloHostedOriginHost("https:///no-host"))
    }
}

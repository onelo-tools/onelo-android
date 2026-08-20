package com.onelo.android

import org.junit.Assert.*
import org.junit.Test

/**
 * Transport hardening for the Android SDK.
 *
 * Every request this SDK makes carries credentials — the access token, the
 * publishable key, the Play Integrity token. Two defaults worked against that:
 *
 *  1. `HttpURLConnection.instanceFollowRedirects` defaults to TRUE and REPLAYS
 *     every header, Authorization included, at the redirect target. A 3xx to a
 *     foreign origin (hijacked or expired domain, misconfigured CDN, a dev
 *     pointing apiUrl at a tunnel) would hand the user's token to that host.
 *     Now disabled at all seven connection sites; every Onelo endpoint is direct.
 *  2. `apiUrl` was validated only for non-blankness, so `http://` was accepted
 *     and the same credentials travelled in plaintext.
 */
class OneloAuthTransportSecurityTest {

    @Test
    fun `https is accepted`() {
        OneloAuth.requireSecureApiUrl("https://api.onelo.tools")
        OneloAuth.requireSecureApiUrl("HTTPS://API.ONELO.TOOLS")
    }

    @Test
    fun `plaintext http to a real host is rejected`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            OneloAuth.requireSecureApiUrl("http://api.onelo.tools")
        }
        assertTrue(e.message!!.contains("https://"))
    }

    @Test
    fun `local development over http still works`() {
        // Never leaves the machine; blocking it would break dev servers for no gain.
        OneloAuth.requireSecureApiUrl("http://localhost:8000")
        OneloAuth.requireSecureApiUrl("http://127.0.0.1:8000")
        // The Android emulator's alias for the host machine.
        OneloAuth.requireSecureApiUrl("http://10.0.2.2:8000")
    }

    @Test
    fun `a LAN address is allowed — a physical test device cannot use localhost`() {
        OneloAuth.requireSecureApiUrl("http://192.168.1.20:8000")
        OneloAuth.requireSecureApiUrl("http://172.16.4.9:8000")
    }

    @Test
    fun `a public IP over http is still rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            OneloAuth.requireSecureApiUrl("http://8.8.8.8:8000")
        }
        // 172.32 is OUTSIDE the private 172.16–172.31 range — an easy off-by-one.
        assertThrows(IllegalArgumentException::class.java) {
            OneloAuth.requireSecureApiUrl("http://172.32.0.1")
        }
    }

    @Test
    fun `a hostname that merely starts with localhost is NOT treated as local`() {
        // e.g. localhost.evil.com — the substring trap.
        assertThrows(IllegalArgumentException::class.java) {
            OneloAuth.requireSecureApiUrl("http://localhost.evil.com")
        }
    }

    @Test
    fun `a path containing localhost does not make a remote host local`() {
        assertThrows(IllegalArgumentException::class.java) {
            OneloAuth.requireSecureApiUrl("http://evil.com/localhost")
        }
    }
}

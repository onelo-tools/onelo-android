package com.onelo.android

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class OneloWaitlistTest {

    @Test
    fun `WaitlistResult data class holds all fields`() {
        val r = WaitlistResult(success = true, position = 5, alreadyJoined = false)
        assertTrue(r.success)
        assertEquals(5, r.position)
        assertFalse(r.alreadyJoined)
    }

    @Test
    fun `WaitlistResult alreadyJoined true`() {
        val r = WaitlistResult(success = true, alreadyJoined = true)
        assertTrue(r.alreadyJoined)
        assertNull(r.position)
    }

    // #1038 — a join sends the App's language, else the device language; the
    // backend stores the join's Page Language. Same scenarios in every SDK.

    private fun withDeviceLanguage(locale: Locale, block: () -> Unit) {
        val saved = Locale.getDefault()
        Locale.setDefault(locale)
        try { block() } finally { Locale.setDefault(saved) }
    }

    @Test
    fun `join sends the device language when the App passes none`() = withDeviceLanguage(Locale.GERMANY) {
        val body = OneloWaitlist.joinBody("pk", "ada@example.com", "beta", null, null)
        assertEquals("de-DE", body["language"])
    }

    @Test
    fun `join sends a language the App passes unchanged`() = withDeviceLanguage(Locale.GERMANY) {
        val body = OneloWaitlist.joinBody("pk", "ada@example.com", "beta", null, "pt-PT")
        assertEquals("pt-PT", body["language"])
    }

    @Test
    fun `the language goes next to the consent flags`() {
        val body = OneloWaitlist.joinBody("pk", "ada@example.com", null, OneloWaitlistConsent(marketing = true), "pl")
        assertEquals("pl", body["language"])
        assertEquals(mapOf("marketing" to true), body["consent"])
        assertFalse(body.containsKey("slug"))
    }
}

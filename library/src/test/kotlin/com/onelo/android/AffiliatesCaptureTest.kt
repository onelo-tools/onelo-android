package com.onelo.android

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #929 — native referral capture: consent first, winner decided by Onelo, claim once. */
class AffiliatesCaptureTest {
    private val click = "11111111-2222-3333-4444-555555555555"

    private class MemStore : OneloAffiliates.Store {
        var value: Map<String, String>? = null
        override fun read() = value
        override fun write(value: Map<String, String>) { this.value = value }
    }

    private fun make(
        store: MemStore,
        calls: MutableList<Pair<String, Map<String, Any?>>>,
        signedIn: Boolean = true,
        reply: (String) -> Pair<Int, Map<String, Any?>> = { 200 to emptyMap() },
    ) = OneloAffiliates("onelo_pk_x", store, { path, body -> calls += path to body; reply(path) }) {
        if (signedIn) "tok" to "u1" else null
    }

    @Test
    fun `refusal stores nothing and only counts`() = runTest {
        val store = MemStore(); val calls = mutableListOf<Pair<String, Map<String, Any?>>>()
        assertFalse(make(store, calls).capture("myapp://open?ref=abcdef1234", consent = false))
        assertNull(store.value)
        assertEquals(listOf("/v1/sdk/affiliates/refused"), calls.map { it.first })
    }

    @Test
    fun `consented ref is kept and claimed once`() = runTest {
        val store = MemStore(); val calls = mutableListOf<Pair<String, Map<String, Any?>>>()
        val aff = make(store, calls) { p -> if (p.endsWith("/click")) 200 to mapOf("click_id" to click) else 200 to mapOf("claimed" to true) }
        assertTrue(aff.capture("https://app.test/x?ref=ABCDEF1234", consent = true))
        assertEquals(listOf("/v1/sdk/affiliates/click", "/v1/sdk/affiliates/claim"), calls.map { it.first })
        assertEquals("abcdef1234", calls[0].second["code"])
        assertEquals("sdk", calls[0].second["source"])
        assertEquals(click, calls[1].second["clickId"])
        assertEquals(OneloAffiliates.Kept(click, "u1"), aff.kept())
        aff.claimForSession()
        assertEquals(2, calls.size)
    }

    @Test
    fun `forwarded click replaces an earlier one`() = runTest {
        val store = MemStore().apply { value = mapOf("click_id" to "99999999-2222-3333-4444-555555555555") }
        val calls = mutableListOf<Pair<String, Map<String, Any?>>>()
        val aff = make(store, calls, signedIn = false)
        aff.capture("myapp://open?onelo_click=$click", consent = true)
        assertEquals(click, aff.kept()?.clickId)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `existing click is sent so Onelo applies the winner`() = runTest {
        val store = MemStore().apply { value = mapOf("click_id" to click) }
        val calls = mutableListOf<Pair<String, Map<String, Any?>>>()
        make(store, calls, signedIn = false) { 200 to mapOf("click_id" to click, "kept" to true) }
            .capture("myapp://open?ref=zzzzzz9999", consent = true)
        assertEquals(click, calls.first().second["existing"])
    }

    @Test
    fun `junk parameters are ignored`() = runTest {
        val store = MemStore(); val calls = mutableListOf<Pair<String, Map<String, Any?>>>()
        assertFalse(make(store, calls).capture("myapp://open?ref=%3Cx%3E&onelo_click=nope", consent = true))
        assertTrue(calls.isEmpty())
    }
}

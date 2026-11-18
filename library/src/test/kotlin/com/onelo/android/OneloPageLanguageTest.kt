package com.onelo.android

import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * #1039 — every SDK sends the device language when it opens a hosted page and
 * the App passes none; a language the App passes is sent unchanged. The backend
 * picks the Page Language within the App Languages (#1036); the SDK never
 * chooses. Same scenarios in every SDK.
 */
class OneloPageLanguageTest {

    private lateinit var saved: Locale

    @Before
    fun setDeviceLanguage() {
        saved = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun restoreDeviceLanguage() {
        Locale.setDefault(saved)
    }

    private fun lang(url: String): String? =
        URI(url).rawQuery.split("&").map { it.split("=", limit = 2) }
            .firstOrNull { it[0] == "lang" }?.let { URLDecoder.decode(it[1], "UTF-8") }

    private val api = "https://api.example.com"

    // ── Store (store-initiate) ─────────────────────────────────────────────

    @Test
    fun `store sends the device language when the App passes none`() {
        assertEquals("de-DE", lang(OneloPaywall.storeInitiateUrl(api, "pk", "myapp", null)))
    }

    @Test
    fun `store sends the language the App passes unchanged`() {
        assertEquals("pt-BR", lang(OneloPaywall.storeInitiateUrl(api, "pk", "myapp", "pt-BR")))
    }

    // ── Upgrade (upgrade-initiate) ─────────────────────────────────────────

    @Test
    fun `upgrade sends the device language when the App passes none`() {
        assertEquals("de-DE", lang(Onelo.upgradeInitiateUrl(api, "pk", "pro", "myapp", null)))
    }

    @Test
    fun `upgrade sends the language the App passes unchanged`() {
        assertEquals("pl", lang(Onelo.upgradeInitiateUrl(api, "pk", "pro", "myapp", "pl")))
    }

    // ── Sign-in (flow/init) ───────────────────────────────────────────────

    @Test
    fun `sign-in sends the device language when the App passes none`() {
        assertEquals("de-DE", lang(OneloAuth.flowInitUrl(api, "pk", "myapp", null, "chal")))
    }

    @Test
    fun `sign-in sends the language the App passes unchanged`() {
        assertEquals("pl", lang(OneloAuth.flowInitUrl(api, "pk", "myapp", "pl", "chal")))
    }

    @Test
    fun `the rest of the query is unchanged`() {
        assertEquals(
            "$api/api/sdk/flow/init?key=pk&callback_scheme=myapp&lang=de-DE&code_challenge=chal",
            OneloAuth.flowInitUrl(api, "pk", "myapp", null, "chal"),
        )
        assertEquals(
            "$api/api/sdk/paywall/store-initiate?key=pk&callback_scheme=myapp&lang=de-DE",
            OneloPaywall.storeInitiateUrl(api, "pk", "myapp", null),
        )
        assertEquals(
            "$api/api/sdk/paywall/upgrade-initiate?key=pk&plan=pro&callback_scheme=myapp&lang=de-DE",
            Onelo.upgradeInitiateUrl(api, "pk", "pro", "myapp", null),
        )
    }
}

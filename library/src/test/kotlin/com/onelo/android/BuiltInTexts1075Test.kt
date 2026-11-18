package com.onelo.android

import com.onelo.android.internal.KeyValueStore
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #1075 — the Android SDK's own texts follow the App's Page Language (ADR-0043).
 * The backend chooses it (`page_language` of `/api/sdk/config`); the SDK adopts
 * the answer, keeps it for the next launch and draws every built-in text in it.
 */
class BuiltInTexts1075Test {

    private lateinit var savedLocale: Locale

    private class MemoryStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @Before
    fun freshProcess() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        OneloPageLanguage.resetForTest()
    }

    @After
    fun restore() {
        OneloPageLanguage.resetForTest()
        Locale.setDefault(savedLocale)
    }

    // ── The table ─────────────────────────────────────────────────────────

    @Test
    fun `every built-in text exists in all eight languages`() {
        assertEquals(listOf("en", "pl", "de", "fr", "es", "it", "pt", "nl"), OneloPageLanguage.SUPPORTED)
        assertEquals(OneloPageLanguage.SUPPORTED.toSet(), OneloText.TABLE.keys)
        for (lang in OneloPageLanguage.SUPPORTED) {
            for (key in OneloText.values()) {
                val text = OneloText.TABLE.getValue(lang)[key]
                assertTrue("$lang/$key missing", !text.isNullOrBlank())
            }
        }
    }

    @Test
    fun `placeholders survive every translation`() {
        for (key in OneloText.values()) {
            val en = key.text("en")
            val placeholders = Regex("\\{[a-z]+\\}").findAll(en).map { it.value }.toSet()
            for (lang in OneloPageLanguage.SUPPORTED) {
                val found = Regex("\\{[a-z]+\\}").findAll(key.text(lang)).map { it.value }.toSet()
                assertEquals("$lang/$key placeholders", placeholders, found)
            }
        }
    }

    @Test
    fun `sentences are translated, not copied from English`() {
        val sentences = listOf(
            OneloText.CLOSE, OneloText.TRY_AGAIN, OneloText.COULD_NOT_REFRESH_SIGN_IN,
            OneloText.BADGE_COMING_SOON, OneloText.BADGE_AVAILABLE_IN,
            OneloText.ERROR_CANCELLED, OneloText.ERROR_SESSION_ENDED, OneloText.ERROR_NO_ACTIVE_PLAN,
        )
        for (lang in OneloPageLanguage.SUPPORTED - "en") {
            for (key in sentences) assertNotEquals("$lang/$key", key.text("en"), key.text(lang))
        }
    }

    @Test
    fun `German is formal and Portuguese is European`() {
        assertTrue(OneloText.COULD_NOT_REFRESH_SIGN_IN.text("de").contains("Sie"))
        assertTrue(OneloText.ERROR_SESSION_ENDED.text("de").contains("Ihre"))
        assertTrue(OneloText.ERROR_NOT_AUTHENTICATED.text("pt").contains("utilizador"))
        assertTrue(OneloText.COULD_NOT_REFRESH_SIGN_IN.text("pt").contains("ligação"))
    }

    // ── Choosing the language: the backend answers, the SDK adopts ─────────

    @Test
    fun `before any answer the texts are English`() {
        assertEquals("en", OneloPageLanguage.current)
        assertEquals("Close", OneloText.CLOSE.text())
    }

    @Test
    fun `the config answer becomes the Page Language and is kept for the next launch`() {
        val store = MemoryStore()
        assertEquals("pl", OneloPageLanguage.adoptFromConfig(mapOf("page_language" to "pl"), store))
        assertEquals("pl", OneloPageLanguage.current)
        assertEquals("Zamknij", OneloText.CLOSE.text())
        assertEquals("pl", store.map[OneloPageLanguage.STORAGE_KEY])

        // Next launch: a fresh process seeds the first paint from the kept answer.
        OneloPageLanguage.resetForTest()
        OneloPageLanguage.restore(store)
        assertEquals("Spróbuj ponownie", OneloText.TRY_AGAIN.text())
    }

    @Test
    fun `a region tag is read by its primary subtag`() {
        OneloPageLanguage.adoptFromConfig(mapOf("page_language" to "pt-PT"), null)
        assertEquals("pt", OneloPageLanguage.current)
        assertEquals("Fechar", OneloText.CLOSE.text())
    }

    @Test
    fun `a missing or empty answer keeps the language it had`() {
        val store = MemoryStore()
        OneloPageLanguage.adoptFromConfig(mapOf("page_language" to "fr"), store)
        // An older backend omits the field; an unreadable App Languages answers null.
        assertNull(OneloPageLanguage.adoptFromConfig(emptyMap(), store))
        assertNull(OneloPageLanguage.adoptFromConfig(mapOf("page_language" to null), store))
        assertNull(OneloPageLanguage.adoptFromConfig(mapOf("page_language" to "  "), store))
        assertEquals("fr", OneloPageLanguage.current)
        assertEquals("fr", store.map[OneloPageLanguage.STORAGE_KEY])
    }

    @Test
    fun `a language Onelo does not translate into falls back to English text`() {
        OneloPageLanguage.adopt("ja")
        assertEquals("Close", OneloText.CLOSE.text())
    }

    // ── Every built-in surface reads it ──────────────────────────────────

    @Test
    fun `App User error messages follow the Page Language`() {
        OneloPageLanguage.adopt("de")
        assertEquals("Die Anmeldung wurde abgebrochen", OneloError.cancelled().message)
        assertEquals("Ihre Sitzung ist abgelaufen — bitte melden Sie sich erneut an", OneloError.sessionEnded().message)
        assertEquals("Dieses Benutzerkonto wurde gesperrt", refreshRefusalError(mapOf("detail" to mapOf("error" to "banned"))).message)
        assertEquals("Netzwerkfehler: timeout", OneloError.network("timeout").message)
        assertEquals(
            "Höchstens 3 Bestätigungs-E-Mails pro Stunde — bitte versuchen Sie es später erneut",
            OneloError.verification(429, emptyMap()).message,
        )
        // The code is unchanged: branch on it, never on the message.
        assertEquals(OneloError.Code.CANCELLED, OneloError.cancelled().code)
    }

    @Test
    fun `developer-facing error messages stay English`() {
        OneloPageLanguage.adopt("de")
        assertEquals("Invalid publishable key: bad", OneloError.invalidKey("bad").message)
        assertEquals("This app requires the hosted sign-in flow. Use loadAuthView().", OneloError.hostedFlowRequired().message)
        assertEquals("Hosted callback failed: HTTP 500", OneloAuth.exchangeFailure(500, emptyMap()).message)
    }

    @Test
    fun `feature badges follow the Page Language and keep the plan name as written`() {
        OneloPageLanguage.adopt("pl")
        fun badge(status: FeatureStatus, label: String? = null, plan: String? = null) =
            OneloFeatureHandle(ResolvedFeature(status, requiredPlan = plan, requiredPlanLabel = label)).badgeLabel
        assertEquals("Nowość", badge(FeatureStatus.NEW))
        assertEquals("Wkrótce", badge(FeatureStatus.COMING_SOON))
        assertEquals("Dostępne w planie Pro Max", badge(FeatureStatus.UPSELL, label = "Pro Max"))
        assertEquals("Dostępne w planie pro", badge(FeatureStatus.UPSELL, plan = "pro"))
        assertEquals("Zmień plan", badge(FeatureStatus.UPSELL))
        assertEquals("🔒", badge(FeatureStatus.GREYED))
        assertNull(badge(FeatureStatus.ENABLED))
    }

    // ── Badge words are the hosted pages' words (owner decision) ──────────

    /** `frontend/lib/<name>` of the monorepo, found by walking up from the test's
     *  CWD; the published SDK repository has no `frontend/`, so the pin is skipped. */
    private fun frontendDictionary(name: String): String {
        var dir: java.io.File? = java.io.File(".").absoluteFile
        while (dir != null) {
            val f = java.io.File(dir, "frontend/lib/$name")
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        org.junit.Assume.assumeTrue("frontend/lib/$name not present (published SDK repository)", false)
        error("unreachable")
    }

    /** The value of [key] in the `  <lang>: {` block of a TS dictionary: a quoted
     *  string, or a template literal whose `${...}` becomes `{plan}`. */
    private fun dictionaryValue(source: String, lang: String, key: String): String? {
        val start = source.indexOf("\n  $lang: {")
        if (start < 0) return null
        val rest = source.substring(start)
        val m = Regex("$key:\\s*(?:\\([a-z]+\\)\\s*=>\\s*`([^`]*)`|'([^']*)'|\"([^\"]*)\")").find(rest) ?: return null
        val raw = m.groupValues.drop(1).first { it.isNotEmpty() }
        return raw.replace(Regex("\\$\\{[a-zA-Z]+\\}"), "{plan}")
    }

    @Test
    fun `badge words are the hosted pages' words in every language`() {
        val notice = frontendDictionary("hosted-notice-i18n.ts")
        val store = frontendDictionary("store-i18n.ts")
        for (lang in OneloPageLanguage.SUPPORTED) {
            assertEquals("$lang Coming soon", dictionaryValue(notice, lang, "lockComingSoon"), OneloText.BADGE_COMING_SOON.text(lang))
            assertEquals("$lang Available in", dictionaryValue(notice, lang, "lockAvailableIn"), OneloText.BADGE_AVAILABLE_IN.text(lang))
            assertEquals("$lang Change plan", dictionaryValue(store, lang, "changePlan"), OneloText.BADGE_UPGRADE.text(lang))
        }
        assertEquals("Novità", OneloText.BADGE_NEW.text("it"))
    }

    // ── The App's language: asked with, and the hosted pages' default ──────

    @Test
    fun `the request language is the call's, else the App's, else the device's`() {
        val app = OneloConfig(publishableKey = "pk", apiUrl = "https://api.example.com", language = "fr")
        val none = OneloConfig(publishableKey = "pk", apiUrl = "https://api.example.com")
        assertEquals("pl", app.requestLanguage("pl"))
        assertEquals("fr", app.requestLanguage())
        assertEquals("de-DE", none.requestLanguage())
        assertEquals("de-DE", none.copy(language = " ").requestLanguage())
    }

    private fun query(url: String, name: String): String? =
        URI(url).rawQuery.split("&").map { it.split("=", limit = 2) }
            .firstOrNull { it[0] == name }?.let { URLDecoder.decode(it[1], "UTF-8") }

    @Test
    fun `config asks with the App's language and sends the device locales`() {
        val app = OneloConfig(publishableKey = "pk", apiUrl = "https://api.example.com", language = "it")
        val url = OneloAuth.configUrl(app.apiUrl, app.publishableKey, "chal", app.requestLanguage())
        assertEquals("https://api.example.com/api/sdk/config?key=pk&code_challenge=chal&lang=it", url)
        assertEquals("it", query(url, "lang"))

        val headers = OneloAuth.configHeaders(mapOf("X-SDK-Version" to "1"), listOf("pl-PL", "en-GB", "pl-PL"))
        assertEquals("pl-PL, en-GB;q=0.9", headers["Accept-Language"])
        assertEquals("1", headers["X-SDK-Version"])
        assertEquals(mapOf("X-SDK-Version" to "1"), OneloAuth.configHeaders(mapOf("X-SDK-Version" to "1"), listOf("")))
    }

    @Test
    fun `the hosted pages open in the App's language when the call passes none`() {
        val app = OneloConfig(publishableKey = "pk", apiUrl = "https://api.example.com", language = "nl")
        assertEquals("nl", query(OneloPaywall.storeInitiateUrl(app.apiUrl, "pk", "s", app.requestLanguage(null)), "lang"))
        assertEquals("nl", query(OneloAuth.flowInitUrl(app.apiUrl, "pk", "s", app.requestLanguage(null), "c"), "lang"))
        assertEquals("nl", query(OneloConsent.consentRequiredUrl(app.apiUrl, app.requestLanguage()), "lang"))
        assertEquals(
            "nl",
            OneloWaitlist.joinBody("pk", "a@b.c", null, null, app.requestLanguage(null))["language"],
        )
    }
}

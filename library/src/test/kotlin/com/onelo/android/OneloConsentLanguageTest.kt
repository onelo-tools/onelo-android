package com.onelo.android

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1073 — the consent check sends the device language; the backend picks the
 * Page Language and links each legal document in it. Same query as every SDK.
 */
class OneloConsentLanguageTest {

    @Test
    fun `the consent check sends the device language`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals(
                "https://api.example.com/v1/sdk/consent/required?lang=de-DE",
                OneloConsent.consentRequiredUrl("https://api.example.com"),
            )
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `the language is query-encoded`() {
        assertEquals(
            "https://api.example.com/v1/sdk/consent/required?lang=pt-BR",
            OneloConsent.consentRequiredUrl("https://api.example.com", "pt-BR"),
        )
    }
}

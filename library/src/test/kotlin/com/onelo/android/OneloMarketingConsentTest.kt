package com.onelo.android

import org.junit.Assert.*
import org.junit.Test

/** #818 — Marketing Consent for an app's own screens. */
class OneloMarketingConsentTest {

    @Test
    fun `the config question is read, and an older backend reads as not asked`() {
        val q = OneloSignupMarketingConsent.fromConfig(
            mapOf("signup_marketing_consent" to mapOf("asked" to true, "text" to "Hi", "language" to "pl")),
        )
        assertEquals(OneloSignupMarketingConsent(true, "Hi", "pl"), q)
        assertEquals(OneloSignupMarketingConsent.NOT_ASKED, OneloSignupMarketingConsent.fromConfig(emptyMap()))
    }

    @Test
    fun `sign-up sends the ticked consent in the language of the text, nothing otherwise`() {
        val q = OneloSignupMarketingConsent(true, "Hi", "pl")
        assertEquals(mapOf("marketing_consent" to true, "lang" to "pl"), OneloSignupMarketingConsent.signUpFields(true, q))
        assertEquals(emptyMap<String, Any?>(), OneloSignupMarketingConsent.signUpFields(false, q))
    }

    @Test
    fun `the waitlist sends only the given checkboxes`() {
        assertEquals(mapOf("marketing" to true), OneloWaitlistConsent(marketing = true).wireValue())
        assertEquals(mapOf("marketing" to false, "custom" to true), OneloWaitlistConsent(false, true).wireValue())
        assertNull(OneloWaitlistConsent().wireValue())
    }

    @Test
    fun `signUp takes the consent and join takes the checkboxes`() {
        val signUp = OneloAuth::class.java.declaredMethods.filter { it.name == "signUp" }
        assertTrue(signUp.any { m -> m.parameterTypes.any { it == Boolean::class.javaPrimitiveType } })
        val join = OneloWaitlist::class.java.declaredMethods.filter { it.name == "join" }
        assertTrue(join.any { m -> m.parameterTypes.any { it == OneloWaitlistConsent::class.java } })
    }
}

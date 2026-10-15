package com.onelo.android

import java.util.Locale

/**
 * #818 — whether your own sign-up screen should show the optional Marketing
 * Consent checkbox, and with what text. Set on the parent App in Auth →
 * Sign-up; an App that sells asks at Checkout instead (`asked` false). Show
 * [text] unticked and pass the person's choice to
 * `signUp(email, password, marketingConsent = …)`.
 */
data class OneloSignupMarketingConsent(
    val asked: Boolean,
    /** The checkbox label, already filled in. Null when not asked. */
    val text: String?,
    /** The language [text] is in; sent back with the consent. */
    val language: String?,
) {
    companion object {
        val NOT_ASKED = OneloSignupMarketingConsent(asked = false, text = null, language = null)

        /** `signup_marketing_consent` of an `/api/sdk/config` body; an older
         *  backend that omits it reads as not asked. */
        fun fromConfig(body: Map<String, Any?>): OneloSignupMarketingConsent {
            @Suppress("UNCHECKED_CAST")
            val raw = body["signup_marketing_consent"] as? Map<String, Any?> ?: return NOT_ASKED
            return OneloSignupMarketingConsent(
                asked = raw["asked"] == true,
                text = raw["text"] as? String,
                language = raw["language"] as? String,
            )
        }

        /** The device language as a BCP 47 tag (`en`, `pl`, `pt-BR`). */
        internal fun deviceLanguage(): String =
            Locale.getDefault().toLanguageTag().ifBlank { "en" }

        /** The fields a sign-up adds to `/api/sdk/auth/signup` — only when ticked. */
        internal fun signUpFields(marketingConsent: Boolean, question: OneloSignupMarketingConsent): Map<String, Any?> =
            if (!marketingConsent) emptyMap()
            else mapOf("marketing_consent" to true, "lang" to (question.language ?: deviceLanguage()))
    }
}

/** #818 — the waitlist checkboxes your form showed. Only the reserved purposes. */
data class OneloWaitlistConsent(
    /** Marketing Consent — optional, never pre-ticked. */
    val marketing: Boolean? = null,
    /** The waitlist's custom checkbox, when it has one. */
    val custom: Boolean? = null,
) {
    /** The `consent` field of the join body, or null when nothing was given. */
    internal fun wireValue(): Map<String, Boolean>? {
        val out = mutableMapOf<String, Boolean>()
        marketing?.let { out["marketing"] = it }
        custom?.let { out["custom"] = it }
        return out.ifEmpty { null }
    }
}

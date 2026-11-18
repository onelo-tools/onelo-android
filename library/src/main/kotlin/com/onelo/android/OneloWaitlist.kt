package com.onelo.android

import com.onelo.android.internal.HttpClient

data class WaitlistResult(val success: Boolean, val position: Int? = null, val alreadyJoined: Boolean = false)

class OneloWaitlist(
    private val config: OneloConfig,
    // X-Bundle-Id (+ X-Integrity-Token + instance id) provider — /waitlist/join is gated by
    // the backend security check (403s a live app with a registered bundle without it).
    // Defaults to none for standalone/test construction. Wired from Onelo.
    private val securityHeaders: () -> Map<String, String> = { emptyMap() },
) {

    /**
     * Join a waitlist. [consent] (#818): the checkboxes your form showed —
     * Marketing Consent and the waitlist's custom checkbox. Omitted, none is sent.
     * [language] (#1038): the App's language for this sign-up. `null` (default)
     * sends [OneloConfig.language], else the device language (#1075); the backend stores the join's Page Language
     * (one of the App Languages) for the waitlist emails.
     */
    suspend fun join(
        email: String,
        slug: String? = null,
        consent: OneloWaitlistConsent? = null,
        language: String? = null,
    ): WaitlistResult {
        return try {
            val body = joinBody(config.publishableKey, email, slug, consent, config.requestLanguage(language))
            val headers = mapOf(
                "X-Publishable-Key" to config.publishableKey,
                "X-SDK-Version" to SDK_VERSION,
            ) + securityHeaders()
            val resp = HttpClient.post("${config.apiUrl}/api/sdk/waitlist/join", body, headers)
            // Backend returns {"ok": true, ...} — reading `success` (never sent)
            // silently made every join look failed.
            val success = resp.body["ok"] as? Boolean ?: false
            val position = (resp.body["position"] as? Number)?.toInt()
            val alreadyJoined = resp.body["alreadyJoined"] as? Boolean ?: false
            WaitlistResult(success = success, position = position, alreadyJoined = alreadyJoined)
        } catch (e: Exception) {
            WaitlistResult(success = false)
        }
    }

    internal companion object {
        /** `POST /api/sdk/waitlist/join` body. Pure, so the wire is testable on the JVM. */
        internal fun joinBody(
            publishableKey: String,
            email: String,
            slug: String?,
            consent: OneloWaitlistConsent?,
            language: String?,
        ): Map<String, Any?> {
            val body = mutableMapOf<String, Any?>(
                "publishableKey" to publishableKey,
                "email" to email,
                // #1038: the App's language, else the device language.
                "language" to (language ?: OneloSignupMarketingConsent.deviceLanguage()),
            )
            if (slug != null) body["slug"] = slug
            consent?.wireValue()?.let { body["consent"] = it }
            return body
        }
    }
}

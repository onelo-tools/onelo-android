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

    suspend fun join(email: String, slug: String? = null): WaitlistResult {
        return try {
            val body = mutableMapOf<String, Any?>(
                "publishableKey" to config.publishableKey,
                "email" to email,
            )
            if (slug != null) body["slug"] = slug
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
}

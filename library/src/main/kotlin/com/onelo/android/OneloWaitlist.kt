package com.onelo.android

import com.onelo.android.internal.HttpClient

data class WaitlistResult(val success: Boolean, val position: Int? = null, val alreadyJoined: Boolean = false)

class OneloWaitlist(private val config: OneloConfig) {

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
            )
            val resp = HttpClient.post("${config.apiUrl}/api/sdk/waitlist/join", body, headers)
            val success = resp.body["success"] as? Boolean ?: false
            val position = (resp.body["position"] as? Number)?.toInt()
            val alreadyJoined = resp.body["alreadyJoined"] as? Boolean ?: false
            WaitlistResult(success = success, position = position, alreadyJoined = alreadyJoined)
        } catch (e: Exception) {
            WaitlistResult(success = false)
        }
    }
}

package com.onelo.android

import com.onelo.android.internal.HttpClient

// ---------------------------------------------------------------------------
// Options & result types
// ---------------------------------------------------------------------------

data class CancelSubscriptionOptions(
    /** One of the OneloResponseReasonCode constants, or a custom string. */
    val reasonCode: String? = null,
    /** Free-text explanation (max 500 chars). */
    val reasonText: String? = null,
)

data class CancelSubscriptionResult(
    /** True when the subscription stays active until the end of the billing period. */
    val cancelAtPeriodEnd: Boolean,
    /** ISO-8601 timestamp of when cancellation takes effect, or null if immediate. */
    val cancelsAt: String? = null,
)

// ---------------------------------------------------------------------------
// Reason-code constants
// ---------------------------------------------------------------------------

object OneloResponseReasonCode {
    const val TOO_EXPENSIVE     = "too_expensive"
    const val MISSING_FEATURES  = "missing_features"
    const val NOT_WORKING       = "not_working"
    const val NOT_USING_ANYMORE = "not_using_anymore"
    const val FOUND_ALTERNATIVE = "found_alternative"
    const val BOUGHT_BY_MISTAKE = "bought_by_mistake"
    const val PREFER_NOT_TO_SAY = "prefer_not_to_say"
    const val OTHER             = "other"
    const val SKIPPED           = "skipped"
}

// ---------------------------------------------------------------------------
// OneloPaywall
// ---------------------------------------------------------------------------

class OneloPaywall(private val config: OneloConfig) {

    // ------------------------------------------------------------------
    // Local plan-gating helper (unchanged behaviour from previous version)
    // ------------------------------------------------------------------

    private val tier = mapOf("free" to 0, "pro" to 1, "business" to 2, "enterprise" to 3)

    fun check(requiredPlan: String, userPlan: String = "free"): Boolean {
        val req = tier[requiredPlan] ?: return false
        val usr = tier[userPlan] ?: return false
        return usr >= req
    }

    // ------------------------------------------------------------------
    // cancelSubscription — calls the Onelo backend portal-cancel endpoint.
    // Requires the user's session token (access_token from OneloSession).
    // ------------------------------------------------------------------

    /**
     * Cancels the active subscription for the authenticated user.
     *
     * @param token      Access token from the user's current [OneloSession].
     * @param options    Optional cancellation context (reason code + free text).
     * @return           [CancelSubscriptionResult] with cancellation timing.
     * @throws [OneloError] on network failure or non-2xx response.
     */
    suspend fun cancelSubscription(
        token: String,
        options: CancelSubscriptionOptions = CancelSubscriptionOptions(),
    ): CancelSubscriptionResult {
        val body = mutableMapOf<String, Any?>(
            "token" to token,
        )
        options.reasonCode?.let { body["reason_code"] = it }
        options.reasonText?.let { body["reason_text"] = it.take(500) }

        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "X-Onelo-Key" to config.publishableKey,
        )

        val resp = HttpClient.post(
            url = "${config.apiUrl}/api/sdk/paywall/portal-cancel",
            body = body,
            headers = headers,
        )

        if (resp.status !in 200..299) {
            val msg = (resp.body["error"] as? String)
                ?: (resp.body["detail"] as? String)
                ?: "cancel subscription failed (HTTP ${resp.status})"
            throw OneloError.server(msg)
        }

        val cancelAtPeriodEnd = resp.body["cancel_at_period_end"] as? Boolean ?: true
        val cancelsAt = resp.body["cancels_at"] as? String

        return CancelSubscriptionResult(
            cancelAtPeriodEnd = cancelAtPeriodEnd,
            cancelsAt = cancelsAt,
        )
    }
}

package com.onelo.android

import com.onelo.android.internal.HttpClient
import java.net.URLEncoder

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

class OneloPaywall(
    private val config: OneloConfig,
    /**
     * Supplies the TOFU security headers (X-Onelo-Instance-Id + X-Bundle-Id +
     * X-Integrity-Token) that the backend's `validate_sdk_request_security` gate
     * requires on `store-initiate` for a live mobile app. Injected by [Onelo] so
     * every gated request carries them — parity with Swift's HTTP client, which
     * adds them transparently. Defaults to empty for construction in isolation.
     */
    private val securityHeaders: () -> Map<String, String> = { emptyMap() },
) {

    // ------------------------------------------------------------------
    // Store flow — mints a fresh hosted store URL (plans / checkout).
    // ------------------------------------------------------------------

    /**
     * Initiate the hosted Store flow and return its URL. Present it in a WebView
     * (e.g. push an Activity hosting one). Mirrors Swift `initiateStoreFlow`.
     *
     * @param lang   Language for the hosted store (e.g. "en"). Default "en".
     * @param token  Optional access token from the signed-in [OneloSession]
     *               (`onelo.auth.currentSession?.accessToken`) — pass it so a
     *               returning buyer goes straight to checkout instead of re-auth.
     * @return       The hosted `store_url` to load.
     * @throws [OneloError] on network failure or a non-200 response.
     */
    suspend fun initiateStoreFlow(lang: String = "en", token: String? = null): String {
        val url = "${config.apiUrl}/api/sdk/paywall/store-initiate" +
            "?key=${enc(config.publishableKey)}" +
            "&callback_scheme=${enc(config.callbackScheme)}" +
            "&lang=${enc(lang)}"
        val headers = mutableMapOf("X-SDK-Version" to SDK_VERSION)
        token?.let { headers["Authorization"] = "Bearer $it" }
        // TOFU security headers — store-initiate runs validate_sdk_request_security,
        // which 403s a live mobile app without X-Bundle-Id (parity with Swift).
        headers.putAll(securityHeaders())
        val resp = HttpClient.get(url, headers)
        if (resp.status != 200) throw OneloError.server("store-initiate failed: HTTP ${resp.status}")
        return resp.body["store_url"] as? String
            ?: throw OneloError.server("store-initiate response missing store_url")
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

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

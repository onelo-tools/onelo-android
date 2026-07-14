package com.onelo.android

import com.onelo.android.internal.HttpClient

data class FormResult(val success: Boolean, val message: String? = null)

class OneloForms(
    private val config: OneloConfig,
    // X-Bundle-Id (+ X-Integrity-Token + instance id) provider — /forms/submit is gated by
    // the backend security check (403s a live app with a registered bundle without it).
    // Defaults to none for standalone/test construction. Wired from Onelo.
    private val securityHeaders: () -> Map<String, String> = { emptyMap() },
) {

    suspend fun submit(
        formSlug: String,
        data: Map<String, Any>,
        submitterEmail: String? = null
    ): FormResult {
        return try {
            val body = mutableMapOf<String, Any?>(
                "publishableKey" to config.publishableKey,
                "formSlug" to formSlug,
                "data" to data,
            )
            if (submitterEmail != null) body["submitterEmail"] = submitterEmail
            val headers = mapOf(
                "X-Publishable-Key" to config.publishableKey,
                "X-SDK-Version" to SDK_VERSION,
            ) + securityHeaders()
            val resp = HttpClient.post("${config.apiUrl}/api/sdk/forms/submit", body, headers)
            // Backend returns {"ok": true, "submissionId": ...} — reading `success`
            // (never sent) silently made every submit look failed.
            val success = resp.body["ok"] as? Boolean ?: false
            val message = resp.body["message"] as? String
            FormResult(success = success, message = message)
        } catch (e: Exception) {
            FormResult(success = false, message = e.message)
        }
    }
}

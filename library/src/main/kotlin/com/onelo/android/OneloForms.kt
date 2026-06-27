package com.onelo.android

import com.onelo.android.internal.HttpClient

data class FormResult(val success: Boolean, val message: String? = null)

class OneloForms(private val config: OneloConfig) {

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
            )
            val resp = HttpClient.post("${config.apiUrl}/api/sdk/forms/submit", body, headers)
            val success = resp.body["success"] as? Boolean ?: false
            val message = resp.body["message"] as? String
            FormResult(success = success, message = message)
        } catch (e: Exception) {
            FormResult(success = false, message = e.message)
        }
    }
}

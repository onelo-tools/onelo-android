package com.onelo.android

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import org.json.JSONArray
import java.net.URLEncoder

data class FeedbackOptions(
    val type: String? = null,  // "bug" | "feature_request" | "general"
    val area: String? = null,
    val userId: String? = null,
)

class OneloFeedback(
    private val config: OneloConfig,
    private val features: OneloFeatures,
    // X-Bundle-Id (+ X-Integrity-Token + instance id) provider. The backend security
    // gate 403s a LIVE app with registered bundle ids on /feedback/initiate without it.
    // Passed to the Activity as an Intent extra (it does the actual request). Defaults
    // to none for standalone/test construction. Wired from Onelo.
    private val securityHeaders: () -> Map<String, String> = { emptyMap() },
) {
    private var launcher: ActivityResultLauncher<Intent>? = null

    fun registerLauncher(launcher: ActivityResultLauncher<Intent>) {
        this.launcher = launcher
    }

    // Launch Activity immediately — it shows skeleton and fetches URL internally
    fun open(context: Context, options: FeedbackOptions = FeedbackOptions()) {
        val sb = StringBuilder("${config.apiUrl}/api/sdk/feedback/initiate?key=${config.publishableKey}")
        options.type?.let { sb.append("&type=${URLEncoder.encode(it, "UTF-8")}") }
        options.area?.let { sb.append("&area=${URLEncoder.encode(it, "UTF-8")}") }
        val active = features.getActiveFeatures()
        if (active.isNotEmpty()) {
            sb.append("&session=${URLEncoder.encode(JSONArray(active).toString(), "UTF-8")}")
        }
        // userId is passed as an intent extra → sent to /initiate as a header
        // (X-Onelo-User-Id), NOT a query param, so it never lands in the access log.
        val intent = Intent(context, OneloFeedbackActivity::class.java).apply {
            putExtra(OneloFeedbackActivity.EXTRA_INITIATE_URL, sb.toString())
            options.userId?.let { putExtra(OneloFeedbackActivity.EXTRA_USER_ID, it) }
            // Security headers (X-Bundle-Id / X-Integrity-Token / instance id) captured now
            // and applied by the Activity on the /initiate request → not 403'd on a live app.
            putExtra(OneloFeedbackActivity.EXTRA_SECURITY_HEADERS, HashMap(securityHeaders()))
        }
        launcher?.launch(intent) ?: context.startActivity(intent)
    }
}

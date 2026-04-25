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

class OneloFeedback(private val config: OneloConfig, private val features: OneloFeatures) {
    private var launcher: ActivityResultLauncher<Intent>? = null

    fun registerLauncher(launcher: ActivityResultLauncher<Intent>) {
        this.launcher = launcher
    }

    // Launch Activity immediately — it shows skeleton and fetches URL internally
    fun open(context: Context, options: FeedbackOptions = FeedbackOptions()) {
        val sb = StringBuilder("${config.apiUrl}/api/sdk/feedback/initiate?key=${config.publishableKey}")
        options.type?.let { sb.append("&type=${URLEncoder.encode(it, "UTF-8")}") }
        options.area?.let { sb.append("&area=${URLEncoder.encode(it, "UTF-8")}") }
        options.userId?.let { sb.append("&userId=${URLEncoder.encode(it, "UTF-8")}") }
        val active = features.getActiveFeatures()
        if (active.isNotEmpty()) {
            sb.append("&session=${URLEncoder.encode(JSONArray(active).toString(), "UTF-8")}")
        }
        val intent = Intent(context, OneloFeedbackActivity::class.java).apply {
            putExtra(OneloFeedbackActivity.EXTRA_INITIATE_URL, sb.toString())
        }
        launcher?.launch(intent) ?: context.startActivity(intent)
    }
}

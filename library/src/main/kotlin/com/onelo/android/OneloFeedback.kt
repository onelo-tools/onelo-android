package com.onelo.android

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
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

    suspend fun open(context: Context, options: FeedbackOptions = FeedbackOptions()) {
        val hostedUrl = fetchHostedUrl(options)
        val intent = Intent(context, OneloFeedbackActivity::class.java).apply {
            putExtra(OneloFeedbackActivity.EXTRA_URL, hostedUrl)
        }
        withContext(Dispatchers.Main) {
            launcher?.launch(intent) ?: context.startActivity(intent)
        }
    }

    private suspend fun fetchHostedUrl(options: FeedbackOptions): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder("${config.apiUrl}/api/sdk/feedback/initiate?key=${config.publishableKey}")
        options.type?.let { sb.append("&type=${URLEncoder.encode(it, "UTF-8")}") }
        options.area?.let { sb.append("&area=${URLEncoder.encode(it, "UTF-8")}") }
        options.userId?.let { sb.append("&userId=${URLEncoder.encode(it, "UTF-8")}") }
        val active = features.getActiveFeatures()
        if (active.isNotEmpty()) {
            val json = JSONArray(active).toString()
            sb.append("&session=${URLEncoder.encode(json, "UTF-8")}")
        }
        val json = URL(sb.toString()).readText()
        JSONObject(json).getString("hosted_url")
    }
}

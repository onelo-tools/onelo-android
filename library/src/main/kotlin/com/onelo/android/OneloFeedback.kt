package com.onelo.android

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import com.onelo.android.internal.HttpClient
import com.onelo.android.internal.HttpResponse
import com.onelo.android.internal.UserTokenSource
import com.onelo.android.internal.bearerHeaders
import com.onelo.android.internal.sendWithUserToken
import org.json.JSONArray
import java.net.URLEncoder

data class FeedbackOptions(
    val type: String? = null,  // "bug" | "feature_request" | "general"
    val area: String? = null,
    val userId: String? = null,
)

class OneloFeedback internal constructor(
    private val config: OneloConfig,
    private val features: OneloFeatures,
    // X-Bundle-Id (+ X-Integrity-Token + instance id) provider. The backend security
    // gate 403s a LIVE app with registered bundle ids on /feedback/initiate without it.
    // Passed to the Activity as an Intent extra (it does the actual request). Defaults
    // to none for standalone/test construction. Wired from Onelo.
    private val securityHeaders: () -> Map<String, String>,
    // The signed-in Onelo user's access token (#820). Wired by `Onelo` to `auth`;
    // with a session the initiate call carries it and the report names a
    // verified reporter. Null (standalone construction) sends no token.
    private val userToken: UserTokenSource?,
) {
    /** Standalone construction (no Onelo Auth session): reports stay anonymous
     *  unless a `userId` label is passed. */
    constructor(
        config: OneloConfig,
        features: OneloFeatures,
        securityHeaders: () -> Map<String, String> = { emptyMap() },
    ) : this(config, features, securityHeaders, null)

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
        // The token source reaches the Activity in-process, never as an Intent
        // extra: an access token must not sit in an Intent (#820).
        FeedbackInitiate.userToken = userToken
        launcher?.launch(intent) ?: context.startActivity(intent)
    }
}

/**
 * The `/feedback/initiate` request the feedback Activity makes (#820).
 *
 * Signed in: `Authorization: Bearer <access token>` makes the server sign the
 * token's user as a VERIFIED reporter. A refused token is refreshed and retried
 * once; refused again, the form still opens without it (as a signed-out call
 * would), so feedback never fails on identity. `userId` travels as a HEADER
 * (`X-Onelo-User-Id`), never a query param, so it stays out of the access log;
 * without a session it is the developer's unverified label.
 */
internal object FeedbackInitiate {
    /** The opening `OneloFeedback`'s token source, read by the Activity in-process. */
    @Volatile var userToken: UserTokenSource? = null

    suspend fun hostedUrl(
        initiateUrl: String,
        userId: String?,
        securityHeaders: Map<String, String>,
        userToken: UserTokenSource?,
        get: suspend (String, Map<String, String>) -> HttpResponse = { url, headers -> HttpClient.get(url, headers) },
    ): String {
        val res = sendWithUserToken(userToken, onRefused = {}) { attempt ->
            // X-Bundle-Id (+ X-Integrity-Token / instance id) — without it the
            // backend security gate 403s a live app with a registered bundle.
            val headers = securityHeaders.toMutableMap()
            userId?.let { headers["X-Onelo-User-Id"] = it }
            headers.putAll(bearerHeaders(attempt.token))
            get(initiateUrl, headers)
        }
        if (res.status !in 200..299) throw OneloError.server("Feedback initiate failed: ${res.status}")
        return (res.body["hosted_url"] as? String)?.takeIf { it.isNotEmpty() }
            ?: throw OneloError.server("Feedback initiate returned no hosted_url")
    }
}

package com.onelo.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.onelo.android.internal.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder

// ---------------------------------------------------------------------------
// Internal constant — the deep-link scheme used by the SDK.
// Must match what is passed as `callback_scheme` to the backend and what the
// hosted portal page redirects to on completion.
// ---------------------------------------------------------------------------

internal const val PORTAL_CALLBACK_SCHEME = "oneloandroid"

/**
 * Hard account-lifecycle events the hosted portal can deep-link back on "Done" — each
 * must clear the local session INSTANTLY (instead of waiting up to ~13 min for the
 * heartbeat 401). SINGLE SOURCE OF TRUTH, mirroring Swift `OneloAuth.portalRevokeEvents`
 * and Flutter `OneloCustomerPortal.portalRevokeEvents`. `account_revoked` is emitted by
 * the hosted portal after a refund-triggered sign-out (PortalClient returnToApp) — the
 * live gap that previously left Android buyers logged-in locally after a refund.
 */
internal val PORTAL_REVOKE_EVENTS = setOf(
    "account_deletion_scheduled",
    "account_revoked",
    "session_compromised",
)

// ---------------------------------------------------------------------------
// Result type
// ---------------------------------------------------------------------------

/**
 * Outcome of a Customer Portal session.
 *
 * @param dismissed True when the user closed the portal (RESULT_CANCELED).
 * @param event     Optional hard account-lifecycle event emitted by the portal page
 *                  on completion (see [PORTAL_REVOKE_EVENTS]: "account_deletion_scheduled",
 *                  "account_revoked", "session_compromised"). For any of these the SDK has
 *                  ALREADY cleared the local session before this callback — route the user
 *                  to sign-in. Null for ordinary "Done" dismissals.
 */
/**
 * Where the Customer Portal opens (#606). [CHANGE_PLAN] lands straight in the plan
 * picker; the hosted portal asks the backend where the change goes. Parity with
 * `@onelo/js` `customerPortal.open({ intent: 'change_plan' })`.
 */
enum class PortalIntent(val wire: String) {
    CHANGE_PLAN("change_plan"),
}

/**
 * The hosted portal URL with `intent` as a query param — the same param the web SDK
 * sets. Null intent → the URL unchanged. Pure string work (no android.net.Uri) so
 * it runs in a JVM unit test.
 */
internal fun withPortalIntent(hostedUrl: String, intent: PortalIntent?): String {
    if (intent == null) return hostedUrl
    val hashAt = hostedUrl.indexOf('#')
    val base = if (hashAt >= 0) hostedUrl.substring(0, hashAt) else hostedUrl
    val fragment = if (hashAt >= 0) hostedUrl.substring(hashAt) else ""
    val queryAt = base.indexOf('?')
    val path = if (queryAt >= 0) base.substring(0, queryAt) else base
    val kept = if (queryAt >= 0) {
        base.substring(queryAt + 1).split('&').filter { it.isNotEmpty() && it.substringBefore('=') != "intent" }
    } else emptyList()
    val query = (kept + "intent=${intent.wire}").joinToString("&")
    return "$path?$query$fragment"
}

data class CustomerPortalResult(
    val dismissed: Boolean,
    val event: String? = null,
)

// ---------------------------------------------------------------------------
// OneloCustomerPortal
// ---------------------------------------------------------------------------

/**
 * Customer Portal module — opens the Onelo-hosted subscription management
 * page for the currently signed-in user.
 *
 * ## Setup (call in Activity / Fragment onCreate, before any UI interaction):
 * ```kotlin
 * val portalLauncher = onelo.customerPortal.registerLauncher(this, auth = onelo.auth) { result ->
 *     if (result.event == "account_deletion_scheduled") {
 *         // session already cleared by the SDK; navigate to sign-in
 *     }
 * }
 * ```
 *
 * ## Open the portal:
 * ```kotlin
 * lifecycleScope.launch {
 *     onelo.customerPortal.openCustomerPortal(auth, portalLauncher)
 * }
 * ```
 *
 * The portal Activity is dismissed and the launcher callback fires when:
 * - The user taps "Done" inside the portal (normal close).
 * - The hosted page redirects to `oneloandroid://callback?source=portal`.
 * - The user presses Back.
 *
 * Account deletion: when the portal emits `event=account_deletion_scheduled`,
 * the SDK automatically clears the local session (equivalent to `auth.signOut()`)
 * before delivering the result to the caller — the user is already logged out
 * when the callback fires.
 */
class OneloCustomerPortal(
    private val config: OneloConfig,
    context: Context,
    // X-Bundle-Id (+ X-Integrity-Token + instance id) provider. The backend security
    // gate 403s a LIVE app with registered bundle ids on portal-initiate without it.
    // Defaults to none for standalone/test construction. Wired from Onelo.
    private val securityHeaders: () -> Map<String, String> = { emptyMap() },
) {
    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Launcher registration ────────────────────────────────────────────────

    /**
     * Register the [ActivityResultLauncher] required to open the Customer Portal
     * Activity. Call this in your Activity or Fragment `onCreate`, before any
     * user interaction.
     *
     * @param activity  The [ComponentActivity] that hosts the portal.
     * @param auth      The [OneloAuth] instance. Required — the SDK clears the
     *                  local session unconditionally on `account_deletion_scheduled`
     *                  so the app never observes a signed-in state after deletion.
     * @param onResult  Callback invoked on the main thread when the portal closes.
     */
    fun registerLauncher(
        activity: ComponentActivity,
        auth: OneloAuth,
        onResult: (CustomerPortalResult) -> Unit,
    ): ActivityResultLauncher<Intent> {
        return activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result -> handleResult(auth, result.resultCode, result.data, onResult) }
    }

    /**
     * What a closed portal means, shared by [registerLauncher] and the SDK-started
     * upgrade ([openUpgradeUrl]). A code is exchanged, a hard account event clears
     * the session, and every other close re-reads the entitlement so
     * `auth.hasActiveAccess` reflects a plan bought or changed there (#606).
     */
    private fun handleResult(
        auth: OneloAuth,
        resultCode: Int,
        data: Intent?,
        onResult: (CustomerPortalResult) -> Unit,
    ) {
        if (resultCode == Activity.RESULT_OK) {
            val code = data?.getStringExtra(OneloCustomerPortalActivity.EXTRA_CODE)
            val event = data?.getStringExtra(OneloCustomerPortalActivity.EXTRA_EVENT)
            val switchReason = data?.getStringExtra(OneloCustomerPortalActivity.EXTRA_STORE_DONE)
            when {
                // A "Change plan" card completed (in the browser or in-WebView) →
                // exchange the one-time code for a refreshed session (new
                // entitlement), reconcile the plan at once (#659), then notify.
                !code.isNullOrEmpty() -> scope.launch {
                    try { auth.exchangeCode(code) } catch (_: Exception) { /* best-effort */ }
                    auth.reportPlanChange(PlanChange.Purchase)
                    onResult(CustomerPortalResult(dismissed = false))
                }
                // A code-less plan switch finished in the store the portal opened:
                // reconcile features + entitlement at once (#659) instead of
                // waiting for the stream.
                switchReason != null -> scope.launch {
                    auth.reportPlanChange(PlanChange.Switched(switchReason))
                    onResult(CustomerPortalResult(dismissed = false))
                }
                // Hard account event (deletion / revoke / compromise) — clear the
                // session before notifying so the app never sees a signed-in state
                // after a deletion OR a refund-triggered revoke (parity with Swift/
                // Flutter; account_revoked was previously ignored → session lingered).
                // signOutRevoked (not signOut) also raises isUserRevoked so the app
                // can show a "signed out remotely" notice — full Swift parity.
                event != null && event in PORTAL_REVOKE_EVENTS -> scope.launch {
                    try { auth.signOutRevoked() } catch (_: Exception) { /* best-effort */ }
                    onResult(CustomerPortalResult(dismissed = false, event = event))
                }
                else -> scope.launch {
                    try { auth.revalidateEntitlement() } catch (_: Exception) { /* best-effort */ }
                    onResult(CustomerPortalResult(dismissed = false, event = event))
                }
            }
        } else {
            scope.launch {
                try { auth.revalidateEntitlement() } catch (_: Exception) { /* best-effort */ }
                onResult(CustomerPortalResult(dismissed = true))
            }
        }
    }

    // ── Open portal ──────────────────────────────────────────────────────────

    /**
     * Fetch the portal hosted URL and open [OneloCustomerPortalActivity].
     *
     * Must be called from a coroutine (suspends during the network call to
     * `GET /api/sdk/paywall/portal-initiate`).
     *
     * @param auth      The authenticated [OneloAuth] instance. The current
     *                  access token is read automatically; if the user is not
     *                  signed in, [OneloError] is thrown before any network call.
     * @param launcher  The [ActivityResultLauncher] registered via [registerLauncher].
     * @param intent    [PortalIntent.CHANGE_PLAN] opens the portal straight into the
     *                  plan picker (#606). Null opens the management home.
     * @throws [OneloError] if the user is not signed in, [config.apiUrl] is blank,
     *                      or the backend returns a non-200 response.
     */
    suspend fun openCustomerPortal(
        auth: OneloAuth,
        launcher: ActivityResultLauncher<Intent>,
        intent: PortalIntent? = null,
    ) {
        require(config.apiUrl.isNotBlank()) { "[Onelo] apiUrl is required" }
        val portalScheme = config.callbackScheme.ifBlank { PORTAL_CALLBACK_SCHEME }
        val hostedUrl = withPortalIntent(initiateHostedUrl(auth), intent)

        // #613 / #614 — while this portal is open, its page may ask for a fresh
        // portal token (its own lives ten minutes). The Activity is started by
        // Intent and cannot receive a closure, so the answer is parked here.
        OneloCustomerPortalActivity.renewHandler = { requestId -> renewReply(auth, requestId) }
        // A launcher delivers the result itself; never also through a handler left by
        // an earlier launcher-less start.
        OneloCustomerPortalActivity.resultHandler = null

        launcher.launch(
            Intent(appContext, OneloCustomerPortalActivity::class.java)
                .putExtra(OneloCustomerPortalActivity.EXTRA_URL, hostedUrl)
                .putExtra(OneloCustomerPortalActivity.EXTRA_CALLBACK_SCHEME, portalScheme)
        )
    }

    /**
     * Open an already-resolved upgrade URL (`/upgrade-initiate`: the store, a plan
     * switch, or the portal explaining that plan changes are off) in the SDK's portal
     * Activity instead of the system browser (#606), so the store's hand-back closes
     * it. Started from the application context — `Onelo.openUpgrade` has no launcher —
     * so the result is handled here through [handleResult]; `onResult` is optional.
     */
    internal fun openUpgradeUrl(
        auth: OneloAuth,
        url: String,
        onResult: (CustomerPortalResult) -> Unit = {},
    ) {
        val portalScheme = config.callbackScheme.ifBlank { PORTAL_CALLBACK_SCHEME }
        OneloCustomerPortalActivity.renewHandler = { requestId -> renewReply(auth, requestId) }
        OneloCustomerPortalActivity.resultHandler = { code, data -> handleResult(auth, code, data, onResult) }
        try {
            appContext.startActivity(
                Intent(appContext, OneloCustomerPortalActivity::class.java)
                    .putExtra(OneloCustomerPortalActivity.EXTRA_URL, url)
                    .putExtra(OneloCustomerPortalActivity.EXTRA_CALLBACK_SCHEME, portalScheme)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // Nothing started: drop the handlers, or the next launcher-opened portal
            // would handle its close twice (the code exchanged twice).
            OneloCustomerPortalActivity.renewHandler = null
            OneloCustomerPortalActivity.resultHandler = null
            throw e
        }
    }

    /**
     * Answer the hosted portal's `onelo:renew_request` (#613 / #614): mint a fresh
     * portal with the signed-in user's own session. Signed out or refused (401) →
     * `renew_failed` with `not_authenticated`, and the page shows its branded
     * "signed out" screen. Never throws.
     */
    internal suspend fun renewReply(auth: OneloAuth, requestId: String): JSONObject =
        try {
            PortalRenewal.renewed(requestId, initiateHostedUrl(auth))
        } catch (e: OneloError) {
            PortalRenewal.failed(requestId, if (e.code == OneloError.Code.NOT_AUTHENTICATED) "not_authenticated" else "renew_failed")
        } catch (_: Exception) {
            PortalRenewal.failed(requestId, "renew_failed")
        }

    /** GET portal-initiate with the user's access token → the hosted URL. */
    internal suspend fun initiateHostedUrl(auth: OneloAuth): String {
        // Retrieve session — throws if not signed in.
        val session = auth.getSession()
            ?: throw OneloError.notAuthenticated()

        val accessToken = session.accessToken

        // Use the app's configured callback scheme (default "oneloandroid") — NOT a
        // hardcoded one. A "Change plan" card returns to `<config.callbackScheme>://
        // callback?code=`, which Onelo.handleRedirect matches against config.callbackScheme;
        // hardcoding a different scheme here would strand that return for apps that set a
        // custom scheme.
        val portalScheme = config.callbackScheme.ifBlank { PORTAL_CALLBACK_SCHEME }
        val url = "${config.apiUrl}/api/sdk/paywall/portal-initiate" +
            "?key=${enc(config.publishableKey)}" +
            "&callback_scheme=${enc(portalScheme)}"

        val resp = HttpClient.get(
            url = url,
            // + securityHeaders(): X-Bundle-Id (+ X-Integrity-Token / instance id) — the
            // backend security gate 403s a live app with a registered bundle without it.
            headers = mapOf(
                "Authorization" to "Bearer $accessToken",
                "X-SDK-Version" to SDK_VERSION,
            ) + securityHeaders(),
        )

        if (resp.status == 401) throw OneloError.notAuthenticated()
        if (resp.status != 200) {
            val msg = (resp.body["error"] as? String)
                ?: (resp.body["detail"] as? String)
                ?: "portal-initiate failed (HTTP ${resp.status})"
            throw OneloError.server(msg)
        }

        OneloBrand.adopt(resp.body) // #681: portal-initiate carries brand_tokens
        return resp.body["hosted_url"] as? String
            ?: throw OneloError.server("Invalid portal-initiate response: missing hosted_url")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/**
 * The page side of opener renewal (#613 / #614), kept pure for unit tests.
 *
 * The hosted portal (and the store it opens) posts
 * `{type:'onelo:renew_request', kind:'portal', request_id}` to its own window;
 * the relay injected by [OneloCustomerPortalActivity] forwards it through the
 * `OneloAndroid` bridge, and the reply goes back as a self-post the page trusts
 * (event.source === window).
 */
internal object PortalRenewal {
    fun renewed(requestId: String, hostedUrl: String): JSONObject =
        JSONObject().put("type", "onelo:renewed").put("request_id", requestId).put("hosted_url", hostedUrl)

    fun failed(requestId: String, reason: String): JSONObject =
        JSONObject().put("type", "onelo:renew_failed").put("request_id", requestId).put("reason", reason)

    /** `scheme://host[:port]` of [url], lowercased; null when it has none. */
    fun originOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val u = java.net.URI(url)
            val scheme = u.scheme?.lowercase() ?: return null
            val host = u.host?.lowercase() ?: return null
            if (u.port == -1) "$scheme://$host" else "$scheme://$host:${u.port}"
        } catch (_: Exception) { null }
    }

    /** The script that delivers [reply] into the page. */
    fun replyScript(reply: JSONObject): String = "window.postMessage($reply, '*');"

    /** Added to the page relay: forward a self-posted portal renew request (or one parked before the relay ran). */
    const val RELAY_SNIPPET = """
                function oneloRenew(d) {
                    if (d && d.type === 'onelo:renew_request' && d.kind === 'portal' && typeof d.request_id === 'string') {
                        OneloAndroid.renewPortal(d.request_id);
                        return true;
                    }
                    return false;
                }
                if (window.__oneloRenewPending) oneloRenew(window.__oneloRenewPending);
"""
}

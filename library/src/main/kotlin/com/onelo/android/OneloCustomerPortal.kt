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
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val code = data?.getStringExtra(OneloCustomerPortalActivity.EXTRA_CODE)
                val event = data?.getStringExtra(OneloCustomerPortalActivity.EXTRA_EVENT)
                when {
                    // A "Change plan" card completed (in the browser or in-WebView) →
                    // exchange the one-time code for a refreshed session (new
                    // entitlement) before notifying the caller.
                    !code.isNullOrEmpty() -> scope.launch {
                        try { auth.exchangeCode(code) } catch (_: Exception) { /* best-effort */ }
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
                    else -> onResult(CustomerPortalResult(dismissed = false, event = event))
                }
            } else {
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
     * @throws [OneloError] if the user is not signed in, [config.apiUrl] is blank,
     *                      or the backend returns a non-200 response.
     */
    suspend fun openCustomerPortal(
        auth: OneloAuth,
        launcher: ActivityResultLauncher<Intent>,
    ) {
        require(config.apiUrl.isNotBlank()) { "[Onelo] apiUrl is required" }

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

        if (resp.status != 200) {
            val msg = (resp.body["error"] as? String)
                ?: (resp.body["detail"] as? String)
                ?: "portal-initiate failed (HTTP ${resp.status})"
            throw OneloError.server(msg)
        }

        val hostedUrl = resp.body["hosted_url"] as? String
            ?: throw OneloError.server("Invalid portal-initiate response: missing hosted_url")

        launcher.launch(
            Intent(appContext, OneloCustomerPortalActivity::class.java)
                .putExtra(OneloCustomerPortalActivity.EXTRA_URL, hostedUrl)
                .putExtra(OneloCustomerPortalActivity.EXTRA_CALLBACK_SCHEME, portalScheme)
        )
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

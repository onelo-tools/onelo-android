package com.onelo.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.onelo.android.internal.HttpClient
import com.onelo.android.internal.OneloPlayIntegrity
import com.onelo.android.internal.Pkce
import com.onelo.android.internal.SecureStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class OneloAuth internal constructor(
    private val config: OneloConfig,
    context: Context,
    // X-Bundle-Id (+ X-Integrity-Token + instance id) provider. The backend security
    // gate 403s a LIVE app with registered bundle ids on /auth/initiate + /oauth/*/init
    // without it. Defaults to none for standalone/test construction. Wired from Onelo.
    private val securityHeaders: () -> Map<String, String> = { emptyMap() },
) {
    private val appContext: Context = context.applicationContext
    private val storage = SecureStorage(appContext)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var refreshJob: Job? = null
    private val refreshLeadSeconds: Long = 60

    private val _authStateFlow = MutableSharedFlow<OneloSession?>(replay = 1)
    internal val authStateFlow: SharedFlow<OneloSession?> = _authStateFlow.asSharedFlow()

    private var pkceVerifier: String? = null
    private val initDeferred = CompletableDeferred<Unit>()
    private var integrityToken: String? = null
    @Volatile private var pendingOAuth: CompletableDeferred<String?>? = null

    var isReady = false
        private set
    var isRevoked = false
        private set
    /** True once the server has revoked THIS user's session (ban / deletion /
     *  refresh-reuse). Surface a "signed out remotely" toast off this. */
    var isUserRevoked = false
        private set
    var allowCustomBranding = false
        private set
    var appName: String = "App"
        private set
    var appLogoUrl: String? = null
        private set

    /** The current in-memory session (null when signed out). Lets sync accessors
     *  like [hasActiveAccess] read entitlement without a storage round-trip. */
    @Volatile
    var currentSession: OneloSession? = null
        private set

    /** Whether the signed-in user currently has paid access. Mirrors Swift
     *  `hasActiveAccess`. False when signed out. See [revalidateEntitlement]. */
    val hasActiveAccess: Boolean
        get() = currentSession?.user?.entitlement == OneloEntitlement.ACTIVE

    // ── Config-driven routing flags (parsed from /api/sdk/config, parity w/ Swift's @Published props) ──
    var paywallEnabled = false
        private set
    var waitlistMode = false
        private set
    var storeUrl: String? = null
        private set
    var manageUrl: String? = null
        private set
    var sdkRedirectUrl: String? = null
        private set
    var oauthProviders: List<String> = emptyList()
        private set

    init {
        require(config.apiUrl.isNotBlank()) { "[Onelo] apiUrl is required" }
        require(config.publishableKey.isNotBlank()) { "[Onelo] publishableKey is required" }
        scope.launch { initialize() }
    }

    private suspend fun initialize() {
        try {
            integrityToken = OneloPlayIntegrity(
                context = appContext,
                apiUrl = config.apiUrl,
                publishableKey = config.publishableKey,
                packageName = appContext.packageName,
            ).getIntegrityToken()
            val verifier = Pkce.generateCodeVerifier()
            pkceVerifier = verifier
            val challenge = Pkce.generateCodeChallenge(verifier)
            val url = "${config.apiUrl}/api/sdk/config?key=${enc(config.publishableKey)}&code_challenge=${enc(challenge)}"
            val resp = HttpClient.get(url, baseHeaders())
            if (resp.status == 401 || resp.status == 404) {
                isRevoked = true
                throw OneloError.invalidKey("Server rejected the key")
            }
            if (resp.status != 200) throw OneloError.server("Config request failed: HTTP ${resp.status}")
            allowCustomBranding = resp.body["allow_custom_branding"] as? Boolean ?: false
            (resp.body["app_name"] as? String)?.let { appName = it }
            appLogoUrl = resp.body["app_logo_url"] as? String
            // Config-driven routing flags (parity with Swift's @Published props) so
            // the app can decide store-vs-signin, waitlist redirect, etc.
            paywallEnabled = resp.body["paywall_enabled"] as? Boolean ?: false
            waitlistMode = resp.body["waitlist_mode"] as? Boolean ?: false
            storeUrl = resp.body["store_url"] as? String
            manageUrl = resp.body["manage_url"] as? String
            sdkRedirectUrl = resp.body["sdk_redirect_url"] as? String
            @Suppress("UNCHECKED_CAST")
            oauthProviders = (resp.body["oauth_providers"] as? List<Any?>)?.filterIsInstance<String>() ?: emptyList()
            isReady = true
            initDeferred.complete(Unit)
        } catch (e: OneloError) {
            if (e.code == OneloError.Code.INVALID_PUBLISHABLE_KEY) isRevoked = true
            initDeferred.complete(Unit)
        } catch (_: Exception) {
            initDeferred.complete(Unit)
        }
    }

    /** Suspends until SDK initialization is complete. */
    suspend fun whenReady() {
        initDeferred.await()
    }

    // ── Hosted flow ──────────────────────────────────────────────────────────────

    /**
     * Register the ActivityResultLauncher needed for hosted auth.
     * Call this in Activity/Fragment onCreate, before any UI interaction.
     */
    fun registerLauncher(
        activity: ComponentActivity,
        onResult: (OneloSession?) -> Unit,
    ): ActivityResultLauncher<Intent> {
        return activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val code = result.data?.getStringExtra(OneloAuthActivity.EXTRA_CODE)
                if (code != null) {
                    scope.launch {
                        try {
                            val session = exchangeCode(code)
                            onResult(session)
                        } catch (_: Exception) {
                            onResult(null)
                        }
                    }
                } else {
                    onResult(null)
                }
            } else {
                onResult(null)
            }
        }
    }

    /**
     * Open the hosted sign-in page in OneloAuthActivity.
     * The result is delivered to the launcher registered via [registerLauncher].
     */
    suspend fun loadAuthView(launcher: ActivityResultLauncher<Intent>) {
        initDeferred.await()
        if (isRevoked) throw OneloError.invalidKey("Application key has been revoked")
        val resp = HttpClient.get(
            "${config.apiUrl}/api/sdk/auth/initiate?key=${enc(config.publishableKey)}&callback_scheme=${enc(config.callbackScheme)}",
            baseHeaders()
        )
        if (resp.status != 200) throw OneloError.server("Failed to initiate hosted auth flow")
        val hostedUrl = resp.body["hosted_url"] as? String
            ?: throw OneloError.server("Invalid initiate response")
        (resp.body["app_name"] as? String)?.let { appName = it }
        (resp.body["app_logo_url"] as? String)?.let { appLogoUrl = it }

        launcher.launch(Intent(appContext, OneloAuthActivity::class.java)
            .putExtra(OneloAuthActivity.EXTRA_URL, hostedUrl)
            .putExtra(OneloAuthActivity.EXTRA_SCHEME, config.callbackScheme))
    }

    internal suspend fun exchangeCode(code: String): OneloSession {
        val resp = HttpClient.post(
            "${config.apiUrl}/api/sdk/auth/hosted-callback",
            buildMap {
                put("publishableKey", config.publishableKey)
                put("code", code)
                pkceVerifier?.let { put("code_verifier", it) }
            },
            baseHeaders()
        )
        if (resp.status != 200) throw OneloError.server("Hosted callback failed")
        return mapSession(resp.body).also { saveSession(it) }
    }

    /**
     * Exchange a hosted-flow one-time `code` for a session — the public, Activity-
     * INDEPENDENT twin of Swift's `exchangeHostedCode`. On success the session is saved
     * and emitted on [onAuthStateChange]. [Onelo.handleRedirect] completes a store/checkout
     * return automatically, so most apps never call this directly; it exists for full
     * parity + for hosts that intercept the deep-link themselves.
     */
    suspend fun exchangeHostedCode(code: String): OneloSession = exchangeCode(code)

    /** True while a native [signInWithOAuth] is awaiting its deep-link callback. Lets
     *  [Onelo.handleRedirect] tell an OAuth callback apart from a store-card callback. */
    internal fun hasPendingOAuth(): Boolean = pendingOAuth != null

    /**
     * Fire-and-forget hosted-code exchange used by [Onelo.handleRedirect] when a store
     * card returns from the system browser but its WebView Activity is already GONE —
     * Android kills heavy activities backgrounded behind the full-screen browser, and
     * their [OneloAuthActivity.pendingExternalReturn] hook is nulled in onDestroy. Without
     * this the returning code would fall through to [handleOAuthRedirect] and be silently
     * dropped (no pending OAuth) → the buyer lands back signed-OUT despite paying. Runs on
     * the SDK scope (survives Activity death); the session is emitted on [onAuthStateChange].
     */
    internal fun completeHostedCodeInBackground(code: String) {
        scope.launch {
            try {
                exchangeCode(code)
            } catch (e: Exception) {
                android.util.Log.w("Onelo", "hosted code exchange failed: ${e.message}")
            }
        }
    }

    // ── Custom UI (paid plans only) ──────────────────────────────────────────────

    suspend fun signIn(email: String, password: String): OneloSession {
        initDeferred.await()
        if (!allowCustomBranding) throw OneloError.planRequired()
        if (pkceVerifier == null) pkceVerifier = Pkce.generateCodeVerifier()
        val resp = HttpClient.post(
            "${config.apiUrl}/api/sdk/auth/signin",
            mapOf(
                "email" to email,
                "password" to password,
                "publishableKey" to config.publishableKey,
                "code_verifier" to pkceVerifier,
            ),
            baseHeaders()
        )
        HttpClient.checkHostedFlowRequired(resp.body)
        if (resp.status == 403) {
            val detail = resp.body["detail"] as? Map<*, *>
            if (detail?.get("error") == "user_revoked") throw OneloError.userRevoked()
            throw OneloError.server((detail?.get("message") ?: resp.body["error"]) as? String ?: "Sign in failed")
        }
        if (resp.status != 200) throw OneloError.server("Sign in failed: HTTP ${resp.status}")
        pkceVerifier = null
        return mapSession(resp.body).also { saveSession(it) }
    }

    suspend fun signUp(email: String, password: String): OneloSession {
        initDeferred.await()
        if (!allowCustomBranding) throw OneloError.planRequired()
        if (pkceVerifier == null) pkceVerifier = Pkce.generateCodeVerifier()
        val resp = HttpClient.post(
            "${config.apiUrl}/api/sdk/auth/signup",
            mapOf(
                "email" to email,
                "password" to password,
                "publishableKey" to config.publishableKey,
                "code_verifier" to pkceVerifier,
            ),
            baseHeaders()
        )
        HttpClient.checkHostedFlowRequired(resp.body)
        if (resp.status != 200) throw OneloError.server("Sign up failed: HTTP ${resp.status}")
        pkceVerifier = null
        return mapSession(resp.body).also { saveSession(it) }
    }

    // ── Native social sign-in (OAuth) ────────────────────────────────────────────

    /**
     * Native social sign-in (`google` / `github` / `apple`). Opens the provider in
     * the SYSTEM browser (providers reject embedded WebViews) via an ACTION_VIEW
     * intent, then suspends until your deep-link Activity forwards the callback to
     * [handleOAuthRedirect]. Captures the one-time `oac_` code and exchanges it for
     * a session. Mirrors Swift's ASWebAuthenticationSession OAuth.
     *
     * SETUP (once): register an intent-filter for `<callbackScheme>://callback` on
     * an Activity, and in its `onCreate`/`onNewIntent` call
     * `onelo.auth.handleOAuthRedirect(intent?.data)`.
     *
     * @throws [OneloError] CANCELLED if the user backs out, or SERVER on failure.
     */
    suspend fun signInWithOAuth(activity: Activity, provider: String): OneloSession {
        initDeferred.await()
        if (pkceVerifier == null) pkceVerifier = Pkce.generateCodeVerifier()
        val challenge = Pkce.generateCodeChallenge(pkceVerifier!!)
        val redirectUri = "${config.callbackScheme}://callback"
        val resp = HttpClient.get(
            "${config.apiUrl}/api/sdk/auth/oauth/$provider/init" +
                "?key=${enc(config.publishableKey)}" +
                "&redirect_uri=${enc(redirectUri)}" +
                "&code_challenge=${enc(challenge)}",
            baseHeaders()
        )
        if (resp.status !in 200..299) throw OneloError.server("OAuth init failed: HTTP ${resp.status}")
        val providerUrl = resp.body["url"] as? String ?: throw OneloError.server("OAuth init missing url")

        val deferred = CompletableDeferred<String?>()
        pendingOAuth = deferred
        try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(providerUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            pendingOAuth = null
            throw OneloError.server("Could not open browser for OAuth: ${e.message}")
        }
        // Wait up to 5 min for the deep-link callback (or the user backing out).
        val code = try {
            withTimeoutOrNull(300_000) { deferred.await() }
        } finally {
            pendingOAuth = null
        }
        if (code.isNullOrEmpty()) throw OneloError.cancelled()
        return exchangeCode(code).let { currentSession ?: throw OneloError.server("OAuth exchange produced no session") }
    }

    /**
     * Call from your deep-link Activity (`onCreate`/`onNewIntent`) with the callback
     * Intent's data. Completes a pending [signInWithOAuth]. Returns true if the URI
     * was an Onelo OAuth callback (`<callbackScheme>://callback?code=…`).
     */
    fun handleOAuthRedirect(uri: Uri?): Boolean {
        if (uri == null) return false
        if (!uri.scheme.equals(config.callbackScheme, ignoreCase = true) || uri.host != "callback") return false
        val code = uri.getQueryParameter("code")
        val pending = pendingOAuth ?: return true
        pending.complete(if (!code.isNullOrEmpty()) code else null) // null → treated as cancel
        return true
    }

    // ── Session management ───────────────────────────────────────────────────────

    suspend fun getSession(): OneloSession? {
        initDeferred.await()
        val accessToken = storage.get("onelo_access_token") ?: return null
        val refreshToken = storage.get("onelo_refresh_token") ?: return null
        val userJson = storage.get("onelo_user") ?: return null
        val expiresAt = storage.get("onelo_expires_at")?.toLongOrNull() ?: 0L

        if (System.currentTimeMillis() / 1000 > expiresAt - 60) {
            return refreshSession()
        }
        val userObj = JSONObject(userJson)
        val session = OneloSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = expiresAt,
            user = OneloUser(
                id = userObj.getString("id"),
                email = userObj.optString("email").ifBlank { null },
                role = userObj.optString("role").ifBlank { "member" },
                tenantId = userObj.optString("tenantId").ifBlank { null },
                entitlement = OneloEntitlement.parse(userObj.optString("entitlement").ifBlank { null }),
            )
        )
        currentSession = session
        // Arm background refresh on first read after process start.
        scheduleRefresh(session)
        return session
    }

    suspend fun refreshSession(): OneloSession? {
        val refreshToken = storage.get("onelo_refresh_token") ?: return null
        val resp = HttpClient.post(
            "${config.apiUrl}/api/sdk/auth/refresh",
            // Backend RefreshRequest requires snake_case `refresh_token` — the old
            // camelCase `refreshToken` 422'd every time → forced logout at token TTL.
            mapOf("publishableKey" to config.publishableKey, "refresh_token" to refreshToken),
            baseHeaders()
        )
        HttpClient.checkHostedFlowRequired(resp.body)
        return when {
            resp.body["error"] == "user_revoked" -> { clearLocalSession(revoked = true); throw OneloError.userRevoked() }
            resp.body["error"] == "app_revoked" -> { clearLocalSession(revoked = true); throw OneloError.revoked() }
            // Generic non-200 (incl. transient 5xx) clears locally but is NOT a
            // "remote revoke" — don't raise the isUserRevoked toast flag on a blip.
            resp.status != 200 -> { clearLocalSession(revoked = false); null }
            else -> mapSession(resp.body).also { saveSession(it) }
        }
    }

    /** User-initiated sign-out. Best-effort server revoke + local clear. Does NOT set
     *  [isUserRevoked] — this is a deliberate sign-out, not a remote/hard revoke. */
    suspend fun signOut() = performSignOut(flagRevoked = false)

    /** Hard portal revoke (account deletion / refund-triggered revoke / session
     *  compromise — see [PORTAL_REVOKE_EVENTS]). Same as [signOut] but flags
     *  [isUserRevoked], so the app can show a "signed out remotely" notice and suppress
     *  auto-reopening the hosted auth view — parity with Swift `applyPortalEvent`. */
    internal suspend fun signOutRevoked() = performSignOut(flagRevoked = true)

    private suspend fun performSignOut(flagRevoked: Boolean) {
        // Best-effort SERVER-side revoke first (kills a leaked refresh token
        // elsewhere too). Offline / failure is non-fatal — the local clear runs.
        currentSession?.accessToken?.let { token ->
            try {
                HttpClient.post(
                    "${config.apiUrl}/api/sdk/auth/signout",
                    emptyMap(),
                    baseHeaders() + mapOf("Authorization" to "Bearer $token", "X-Publishable-Key" to config.publishableKey),
                )
            } catch (_: Exception) { /* offline — local sign-out still proceeds */ }
        }
        clearLocalSession(revoked = flagRevoked)
    }

    /**
     * Called by the realtime stream on a `session.revoked` push. The backend fans
     * the event to ALL subscribers of the app, so filter by `app_user_id` — a
     * missing target is treated as "this user" (forward-compatible). Clears locally
     * in REAL TIME (sub-second) vs the 13-min heartbeat / next-refresh fallback.
     */
    internal fun handleRemoteRevoke(appUserId: String?) {
        val current = currentSession?.user?.id
        if (appUserId != null && current != null && appUserId != current) return
        clearLocalSession(revoked = true)
    }

    /** Tear down the local session: stop timers, wipe storage, drop the in-memory
     *  session, notify. [revoked] flags a server-driven revoke so the app can show
     *  a "signed out remotely" toast via [isUserRevoked]. */
    private fun clearLocalSession(revoked: Boolean) {
        stopHeartbeat()
        cancelRefreshJob()
        storage.clear()
        currentSession = null
        if (revoked) isUserRevoked = true
        notifyListeners(null)
    }

    /**
     * Re-fetch the current user's entitlement from the backend and update the live
     * session if it changed. Returns the latest entitlement (or the cached one on
     * failure / when signed out). Mirrors Swift `revalidateEntitlement`
     * (`GET /api/sdk/auth/user`). Notifies listeners on change so features re-resolve.
     */
    suspend fun revalidateEntitlement(): OneloEntitlement {
        val session = currentSession ?: getSession() ?: return OneloEntitlement.NONE
        val resp = try {
            HttpClient.get(
                "${config.apiUrl}/api/sdk/auth/user",
                baseHeaders() + mapOf("Authorization" to "Bearer ${session.accessToken}", "X-Publishable-Key" to config.publishableKey),
            )
        } catch (_: Exception) {
            return session.user.entitlement
        }
        if (resp.status != 200) return session.user.entitlement
        val entitlement = OneloEntitlement.parse(resp.body["entitlement"])
        if (entitlement != session.user.entitlement) {
            val updated = session.copy(user = session.user.copy(entitlement = entitlement))
            currentSession = updated
            storage.set("onelo_user", userJson(updated.user))
            notifyListeners(updated)
        }
        return entitlement
    }

    /**
     * Returns a SharedFlow that emits whenever the auth state changes.
     * Collect this in a coroutine (e.g. lifecycleScope).
     */
    fun onAuthStateChange(): SharedFlow<OneloSession?> = authStateFlow

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun startHeartbeat(accessToken: String) {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (true) {
                delay(780_000L)
                try {
                    val url = URL("${config.apiUrl}/api/sdk/presence/heartbeat")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Authorization", "Bearer $accessToken")
                    conn.connect()
                    conn.responseCode // trigger the request
                    conn.disconnect()
                } catch (_: Exception) {
                    // fire-and-forget
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * Schedule a background refresh of the access token to fire `refreshLeadSeconds`
     * before it expires. Idempotent — cancels any pending refresh first. Without this,
     * an idle app would carry a stale token past its TTL and the next request would 401.
     */
    private fun scheduleRefresh(session: OneloSession) {
        cancelRefreshJob()
        val nowSec = System.currentTimeMillis() / 1000
        val delaySec = session.expiresAt - nowSec - refreshLeadSeconds
        val delayMs = if (delaySec > 0) delaySec * 1000 else 0L
        refreshJob = scope.launch {
            delay(delayMs)
            try {
                refreshSession()
            } catch (_: Exception) {
                // Errors already handled inside refreshSession (storage cleared / listeners notified).
            }
        }
    }

    private fun cancelRefreshJob() {
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun saveSession(session: OneloSession) {
        storage.set("onelo_access_token", session.accessToken)
        storage.set("onelo_refresh_token", session.refreshToken)
        storage.set("onelo_expires_at", session.expiresAt.toString())
        storage.set("onelo_user", userJson(session.user))
        currentSession = session
        isUserRevoked = false // a fresh session clears any prior remote-revoke flag
        notifyListeners(session)
        startHeartbeat(session.accessToken)
        scheduleRefresh(session)
    }

    private fun userJson(user: OneloUser): String = JSONObject().apply {
        put("id", user.id)
        put("email", user.email ?: "")
        put("role", user.role)
        put("tenantId", user.tenantId ?: "")
        put("entitlement", if (user.entitlement == OneloEntitlement.ACTIVE) "active" else "none")
    }.toString()

    private fun notifyListeners(session: OneloSession?) {
        scope.launch { _authStateFlow.emit(session) }
    }

    private fun mapSession(body: Map<String, Any?>): OneloSession {
        // Two backend response shapes, unified here:
        //   • NESTED    (/signin, /signup, /refresh): {session:{access_token, refresh_token, expires_in}, user}
        //   • TOP-LEVEL (/hosted-callback):            {access_token, refresh_token, expires_in, user}
        // Read tokens from `session` when present, else from the root. Reading the
        // root only (the old code) yielded EMPTY tokens for signin/signup/refresh.
        @Suppress("UNCHECKED_CAST")
        val tokens = (body["session"] as? Map<String, Any?>) ?: body
        val user = body["user"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val appMeta = user["app_metadata"] as? Map<*, *> ?: emptyMap<String, Any?>()
        // expires_in is RELATIVE seconds (e.g. 900), NOT an absolute timestamp — the
        // backend never sends `expires_at`. Convert to an epoch-second deadline.
        val expiresIn = (tokens["expires_in"] as? Number)?.toLong() ?: 900L
        val expiresAt = System.currentTimeMillis() / 1000 + expiresIn
        return OneloSession(
            accessToken = tokens["access_token"] as? String ?: "",
            refreshToken = tokens["refresh_token"] as? String ?: "",
            expiresAt = expiresAt,
            user = OneloUser(
                id = user["id"] as? String ?: "",
                email = user["email"] as? String,
                role = (appMeta["user_role"] ?: user["role"] ?: "member") as? String ?: "member",
                tenantId = (appMeta["tenant_id"] ?: user["tenant_id"]) as? String,
                entitlement = OneloEntitlement.parse(user["entitlement"]),
            )
        )
    }

    suspend fun sendMagicLink(email: String, redirectTo: String? = null) {
        val body = mutableMapOf<String, Any?>(
            "publishableKey" to config.publishableKey,
            "email" to email,
        )
        if (redirectTo != null) body["redirectTo"] = redirectTo
        HttpClient.post("${config.apiUrl}/api/sdk/auth/magic-link", body, baseHeaders())
    }

    suspend fun sendPasswordReset(email: String, redirectTo: String? = null) {
        val body = mutableMapOf<String, Any?>(
            "publishableKey" to config.publishableKey,
            "email" to email,
        )
        if (redirectTo != null) body["redirectTo"] = redirectTo
        HttpClient.post("${config.apiUrl}/api/sdk/auth/reset-password/request", body, baseHeaders())
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun baseHeaders(): Map<String, String> = buildMap {
        put("X-SDK-Version", SDK_VERSION)
        integrityToken?.let { put("X-Integrity-Token", it) }
        // X-Bundle-Id + X-Onelo-Instance-Id (and Onelo's integrity token when set) so a
        // live app with a registered bundle isn't 403'd on /auth/initiate + /oauth/*/init.
        putAll(securityHeaders())
    }
}

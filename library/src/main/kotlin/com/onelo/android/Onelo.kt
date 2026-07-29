package com.onelo.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.onelo.android.internal.HttpClient
import java.net.URLEncoder
import com.onelo.android.internal.OneloEventStream
import com.onelo.android.internal.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Onelo(private val config: OneloConfig, context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val appContext: Context = context.applicationContext

    // securityHeaders() (X-Bundle-Id + X-Onelo-Instance-Id + X-Integrity-Token) is threaded
    // into EVERY module that hits a bundle-enforcing endpoint, so a live app with a
    // registered bundle isn't 403'd. The lambda is lazy — invoked per-request (post-init),
    // so it safely reads instanceId/attestedBundleId/integrityToken declared below.
    val monitor: OneloMonitor = OneloMonitor(config, context) { securityHeaders() }
    val auth: OneloAuth = OneloAuth(config, context) { securityHeaders() }
    val features: OneloFeatures = OneloFeatures(config, monitor, context)
    val feedback: OneloFeedback = OneloFeedback(config, features) { securityHeaders() }
    val paywall: OneloPaywall = OneloPaywall(config) { securityHeaders() }
    val customerPortal: OneloCustomerPortal = OneloCustomerPortal(config, context) { securityHeaders() }
    val consent: OneloConsent = OneloConsent(config, context)
    val forms: OneloForms = OneloForms(config) { securityHeaders() }
    val waitlist: OneloWaitlist = OneloWaitlist(config) { securityHeaders() }

    // Stable per-install id — sent as X-Onelo-Instance-Id (fixes the test-env
    // batch-ping 403) and the identity the realtime SSE stream binds to.
    private val instanceId: String = SecureStorage(context).instanceId()

    @Volatile private var attestedBundleId: String = context.packageName
    @Volatile private var integrityToken: String? = null

    // ONE shared realtime stream. Auth (session.revoked), consent
    // (legal.consent_required) and features (features_updated) register disjoint
    // handlers so they coexist on a single SSE connection (parity w/ Swift/Flutter).
    private val eventStream = OneloEventStream(
        apiUrl = config.apiUrl,
        publishableKey = config.publishableKey,
        instanceId = instanceId,
        sdkVersion = SDK_VERSION,
        environment = features.featureEnvironment,
        // Send X-Bundle-Id (+ X-Integrity-Token) on the stream connect so a live app
        // with a registered bundle isn't 403'd → realtime stays alive. Same provider
        // paywall uses; called per-connect so a token fetched post-init rides reconnects.
        securityHeaders = { securityHeaders() },
    )

    fun securityHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        headers["X-Onelo-Instance-Id"] = instanceId
        if (attestedBundleId.isNotEmpty()) headers["X-Bundle-Id"] = attestedBundleId
        integrityToken?.let { headers["X-Integrity-Token"] = it }
        return headers
    }

    init {
        // Install unhandled-exception capture at init (parity with Swift/Flutter).
        // Idempotent + chains any handler already set, so an app that also sets one
        // (Crashlytics) still runs. Global crashes ship as `global_error` events.
        monitor.registerGlobalHandlers()
        registerSseHandlers()
        scope.launch {
            // Reuse the auth module's Play Integrity attestation instead of running
            // a second, independent one: ONE /api/sdk/config fetch + ONE Standard-API
            // provider warm-up per launch (the Standard prepare is rate-limited to
            // ~5/min). auth.initialize() fetches config + (if attest_required) the
            // integrity token before completing whenReady().
            auth.whenReady()
            integrityToken = auth.currentIntegrityToken()
            // Anonymous realtime kickstart so features-only apps get live updates
            // before any sign-in (re-targeted to the user when auth signs in below).
            eventStream.ensureStarted(null)
            features.load(null, securityHeaders())
        }
        scope.launch {
            auth.onAuthStateChange().collect { session ->
                val userId = session?.user?.id
                // Tag monitor events with the signed-in user (parity with Swift/Flutter).
                // Was the known Android bug: post-login events shipped userId=null.
                monitor.setUserId(userId)
                if (userId != null) {
                    eventStream.start(userId) // re-target the realtime stream to this user
                    // Auto-present the legal-consent gate if a blocking version is
                    // outstanding for this freshly signed-in user (parity with the
                    // other SDKs). No-op unless a launcher is registered + resumed.
                    consent.notifySignedIn()
                } else {
                    // Explicit sign-out / remote-revoke → drop the user binding and
                    // re-bind anonymously (ensureStarted alone never downgrades).
                    eventStream.stop()
                    eventStream.ensureStarted(null)
                }
                features.load(userId, securityHeaders())
            }
        }
    }

    /** Wire the shared realtime stream to the modules. Keys are disjoint per module. */
    private fun registerSseHandlers() {
        eventStream.on("session.revoked") { data -> auth.handleRemoteRevoke(data["app_user_id"] as? String) }
        eventStream.on("legal.consent_required") { consent.onRemoteConsentRequired() }
        // `connected` (full snapshot on every reconnect) + `features_updated` (fresh
        // snapshot) + `up_to_date` (cursor only) all route through applySseSnapshot —
        // it applies `features` when present and always advances the version cursor.
        eventStream.on("connected") { data -> features.applySseSnapshot(data) }
        eventStream.on("up_to_date") { data -> features.applySseSnapshot(data) }
        eventStream.on("features_updated") { data -> features.applySseSnapshot(data) }
        eventStream.on("discovery_requested") { features.onDiscoveryRequested() }
    }

    /**
     * Only needed when NOT using Onelo Auth (own auth system). Pass [userIdHash]
     * (`HMAC-SHA256(secret_key, "user:" + userId)`, computed on YOUR backend) to
     * use Secure Mode so per-user targeting can't be spoofed with a raw userId.
     */
    suspend fun identify(userId: String, userIdHash: String? = null) {
        monitor.setUserId(userId)
        features.load(userId, securityHeaders(), userIdHash)
    }

    /**
     * Resolve the upgrade destination for [plan] and open it in the SYSTEM browser.
     * The BACKEND decides the target (single source of truth for billing state):
     * an active subscriber lands on the hosted manage page with the plan pinned
     * ("Change plan" — the link never executes the change itself); a user without a
     * subscription lands on the hosted store. Requires a signed-in user (no-op +
     * Logcat warning otherwise). 1:1 with Swift `Onelo.openUpgrade(forPlan:)`.
     *
     * Call this from an "Available in <plan>" upsell tap (features carrying
     * `upgradeCta = true`). For the plain store/checkout at sign-up, present the
     * hosted auth flow via `auth.loadAuthView(...)` — the hosted page routes
     * sign-up → store server-side (parity with Swift `OneloAuthView`).
     */
    suspend fun openUpgrade(plan: String, lang: String = "en") {
        val token = auth.getSession()?.accessToken
        if (token == null) {
            Log.w("Onelo", "openUpgrade: no signed-in user — upgrade flow requires a session")
            return
        }
        val upgradeUrl = try {
            val url = "${config.apiUrl}/api/sdk/paywall/upgrade-initiate" +
                "?key=${enc(config.publishableKey)}" +
                "&plan=${enc(plan)}" +
                "&callback_scheme=${enc(config.callbackScheme)}" +
                "&lang=${enc(lang)}"
            val resp = HttpClient.get(
                url,
                // securityHeaders() (X-Onelo-Instance-Id + X-Bundle-Id + X-Integrity-Token)
                // is REQUIRED: the backend's validate_sdk_request_security gate 403s a
                // live mobile app with a registered bundle when X-Bundle-Id is missing.
                // Swift's HTTP client injects these on every request — we must too.
                mapOf("Authorization" to "Bearer $token", "X-SDK-Version" to SDK_VERSION) + securityHeaders(),
            )
            if (resp.status != 200) {
                Log.w("Onelo", "openUpgrade: upgrade-initiate HTTP ${resp.status}")
                return
            }
            resp.body["upgrade_url"] as? String
                ?: run { Log.w("Onelo", "openUpgrade: malformed upgrade-initiate response"); return }
        } catch (e: Exception) {
            Log.w("Onelo", "openUpgrade failed: ${e.message}")
            return
        }
        // System browser (ACTION_VIEW) on the MAIN thread — parity with Swift @MainActor
        // and the OAuth path (OneloAuth). NEW_TASK because we launch from app context.
        // Wrapped: a device with no browser throws ActivityNotFoundException, which must
        // degrade to a warning, not crash (best-effort, like every other exit here).
        withContext(Dispatchers.Main) {
            try {
                appContext.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(upgradeUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Log.w("Onelo", "openUpgrade: cannot open $upgradeUrl — ${e.message}")
            }
        }
    }

    /**
     * Route the shared `<scheme>://callback` deep link. Call this ONCE from your
     * deep-link Activity's `onCreate`/`onNewIntent` (same intent-filter OAuth uses).
     *
     * When the hosted store (shown via [OneloAuth.loadAuthView] in [OneloAuthActivity])
     * hands the Stripe CARD page to the system browser, that page returns here via the
     * OS deep-link — NOT into the store WebView. If a store WebView is on-screen we
     * finish it with the code (it then exchanges it via the launcher, exactly like an
     * in-WebView callback); otherwise we complete a pending OAuth sign-in. Returns
     * true if a pending flow consumed the callback. (= Swift `.onOpenURL`.)
     */
    fun handleRedirect(uri: Uri?): Boolean {
        if (uri == null) return false
        if (!uri.scheme.equals(config.callbackScheme, ignoreCase = true) || uri.host != "callback") return false
        val code = uri.getQueryParameter("code")

        // 1. A store/checkout card that escaped to the browser from a LIVE Onelo WebView
        //    (OneloAuthActivity sign-up → store, or OneloCustomerPortalActivity "Change
        //    plan") — forward the code so that WebView finishes with it. Only one is ever
        //    on-screen, so at most one hook is set; check both.
        if (!code.isNullOrEmpty()) {
            OneloAuthActivity.pendingExternalReturn?.let { it(code); return true }
            OneloCustomerPortalActivity.pendingExternalReturn?.let { it(code); return true }
        }

        // 2. A pending native OAuth sign-in (signInWithOAuth suspends until its callback).
        if (auth.hasPendingOAuth()) return auth.handleOAuthRedirect(uri)

        // 3. A store-card code whose WebView Activity was DESTROYED while the app was
        //    backgrounded behind the full-screen browser. Android kills heavy activities,
        //    and their pendingExternalReturn hook is nulled in onDestroy — so the poll is
        //    gone and step 1 found nothing. The srt_ token survives in the URL, but the
        //    code would otherwise fall through to handleOAuthRedirect and be silently
        //    dropped (no pending OAuth) → the buyer pays and lands back signed-OUT.
        //    Exchange it Activity-independently so the session is still established (this
        //    is what makes Android reach the seamless iOS/Swift return parity).
        if (!code.isNullOrEmpty()) {
            auth.completeHostedCodeInBackground(code)
            return true
        }

        // 4. No code (cancel / error) with no pending flow — let auth handle/ignore it.
        return auth.handleOAuthRedirect(uri)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

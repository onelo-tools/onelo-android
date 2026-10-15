package com.onelo.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.onelo.android.internal.HttpClient
import java.net.URLEncoder
import com.onelo.android.internal.OneloEventStream
import com.onelo.android.internal.SecureStorage
import com.onelo.android.internal.UserTokenEpisode
import com.onelo.android.internal.UserTokenSource
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
    val monitor: OneloMonitor = OneloMonitor(config, context, securityHeaders = { securityHeaders() })
    val auth: OneloAuth = OneloAuth(config, context) { securityHeaders() }

    // The signed-in user's access token, proving the user on every Features call
    // and on the Event Stream connect (#519). getSession() refreshes a token near
    // expiry, so a stale one is never sent; signed out → null → no token.
    private val userToken = UserTokenSource(
        readCurrent = { auth.getSession()?.accessToken },
        readRefreshed = { auth.refreshSession()?.accessToken },
    )
    val features: OneloFeatures = OneloFeatures(config, monitor, context, userToken = userToken)
    val feedback: OneloFeedback = OneloFeedback(config, features, { securityHeaders() }, userToken)
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
        userToken = userToken,
    )

    private val planChange = PlanChangeRefresher(
        scope = scope,
        refreshFeatures = { features.refresh(force = true) },
        revalidateEntitlement = { auth.revalidateEntitlement() },
        snapshot = { features.snapshotKey() },
    )

    fun securityHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        // Version + name together (#122) — every request through here is one the
        // dashboard's version capture may read.
        headers.putAll(sdkIdentityHeaders())
        headers["X-Onelo-Instance-Id"] = instanceId
        // WHICH OS. Always "android" here, and always accurate — which is the
        // point: /api/sdk/config suppresses the "Require plan on sign-up" gate
        // ONLY for Apple platforms, so a client that positively identifies as
        // Android can never be caught by that branch by accident.
        headers["X-Onelo-OS"] = "android"
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
        // #737: seed Features with the STORED user synchronously, so feature() reads
        // that user's persisted snapshot from the first frame and ready() waits for
        // that user — never for an anonymous snapshot that a restore then replaces.
        features.bindIdentity(auth.peekSession()?.user?.id)
        // #659: a purchase / plan switch handed back by any hosted Activity
        // reconciles features + entitlement at once, instead of waiting for the
        // stream. One refresher, so the containers cannot drift.
        auth.planChangeHandler = { change -> planChange.handBack(change) }
        scope.launch {
            // Reuse the auth module's Play Integrity attestation instead of running
            // a second, independent one: ONE /api/sdk/config fetch + ONE Standard-API
            // provider warm-up per launch (the Standard prepare is rate-limited to
            // ~5/min). auth.initialize() fetches config + (if attest_required) the
            // integrity token before completing whenReady().
            auth.whenReady()
            integrityToken = auth.currentIntegrityToken()
            // #737: bind Features to the RESTORED user, not anonymous. A stored
            // session is never emitted on onAuthStateChange at launch, so loading
            // null here left a returning user's features resolved as "anon" (with
            // their Bearer, then persisted under the anon key) for the whole run.
            val restored = runCatching { auth.getSession()?.user?.id }.getOrNull()
            if (restored != null) eventStream.start(restored)
            else eventStream.ensureStarted(null) // anonymous realtime before any sign-in
            features.load(restored, securityHeaders())
        }
        scope.launch {
            val episode = UserTokenEpisode(userToken)
            auth.onAuthStateChange().collect { session ->
                val userId = session?.user?.id
                // A different user starts a new token-refusal episode. The same
                // user (a refresh re-emitting the session) does not: that is what
                // keeps a persistently refused token from looping refresh → load
                // → refresh, since every emission reloads features below.
                episode.onSession(userId)
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
        // #635: this user's grant changed (purchase, switch, refund, pause…). The
        // backend sends it only to this user's streams, right after the fresh
        // `features_updated`; re-read the entitlement so hasActiveAccess follows.
        eventStream.on("paywall.access_changed") {
            scope.launch { try { auth.revalidateEntitlement() } catch (_: Exception) { /* best-effort */ } }
        }
        eventStream.on("legal.consent_required") { consent.onRemoteConsentRequired() }
        // `connected` (full snapshot on every reconnect) + `features_updated` (fresh
        // snapshot) + `up_to_date` (cursor only) all route through applySseSnapshot —
        // it applies `features` when present and always advances the version cursor.
        // The connection's bound user rides along: a frame from an anonymous
        // (refused-token fallback) connection never lands on a signed-in user (#737).
        eventStream.on("connected") { data -> features.applySseSnapshot(data, eventStream.connectionUserId) }
        eventStream.on("up_to_date") { data -> features.applySseSnapshot(data, eventStream.connectionUserId) }
        eventStream.on("features_updated") { data -> features.applySseSnapshot(data, eventStream.connectionUserId) }
        eventStream.on("discovery_requested") { features.onDiscoveryRequested() }
    }

    /**
     * Deprecated: the signed-in Onelo user is used automatically. Onelo Auth is
     * mandatory (#511), and every Features call proves that user with their
     * access token, so there is nothing to identify. [userIdHash] is ignored
     * (Secure Mode is retired). Kept so existing code compiles.
     *
     * Without an Onelo session the id is still sent unverified until the server
     * stops trusting it (#521); with one, the token decides the user.
     */
    @Deprecated("The signed-in Onelo user is used automatically; Features calls prove them with their access token. userIdHash is ignored.")
    suspend fun identify(userId: String, @Suppress("UNUSED_PARAMETER") userIdHash: String? = null) {
        monitor.setUserId(userId)
        features.load(userId, securityHeaders())
    }

    /**
     * Resolve the upgrade destination for [plan] and open it in the SDK's portal
     * Activity (#606). The BACKEND decides the target (single source of truth for
     * billing state): an active subscriber lands on the plan switch with the plan
     * pinned (the link never executes the change itself); a user without a paid plan
     * lands on the hosted store; with plan changes off, the portal explains it. The
     * store's hand-back closes the Activity, the entitlement is re-read, and the new
     * plan reaches `features` over the Event Stream. Requires a signed-in user (no-op +
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
                mapOf("Authorization" to "Bearer $token") + securityHeaders(),
            )
            if (resp.status != 200) {
                val code = extractErrorCode(resp.body)
                if (code == "in_app_store_not_allowed") {
                    Log.w(
                        "Onelo",
                        "openUpgrade: in_app_store_not_allowed — " +
                            (extractErrorMessage(resp.body)
                                ?: "the in-app store is hidden for this app (Paywall → Store → Access Gate)"),
                    )
                } else {
                    Log.w(
                        "Onelo",
                        "openUpgrade: upgrade-initiate HTTP ${resp.status}" + (code?.let { " ($it)" } ?: ""),
                    )
                }
                return
            }
            OneloBrand.adopt(resp.body) // #681: upgrade-initiate carries brand_tokens
            resp.body["upgrade_url"] as? String
                ?: run { Log.w("Onelo", "openUpgrade: malformed upgrade-initiate response"); return }
        } catch (e: Exception) {
            Log.w("Onelo", "openUpgrade failed: ${e.message}")
            return
        }
        // #606 — the SDK's own portal Activity, not the system browser: a browser tab
        // never hands a finished purchase or plan switch back. The Activity decodes
        // every hand-back, closes itself and re-reads the entitlement. Main thread,
        // NEW_TASK (started from the application context). Wrapped: a start failure
        // degrades to a warning, never a crash.
        withContext(Dispatchers.Main) {
            try {
                customerPortal.openUpgradeUrl(auth, upgradeUrl)
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

        // 1b. An Access Gate refusal (magic link, or a sign-in that resolved to "no
        //     plan") — there is deliberately no code, since the backend withholds it
        //     rather than let the app decide. WHICH screen the person is owed was
        //     decided server-side; this SDK only carries the URL, and only after
        //     confirming it's one of THIS app's own hosted surfaces (any app on the
        //     device can fire our scheme, and this value is about to be loaded in a
        //     WebView). Before this, a magic-link refusal was silently dropped here
        //     and the "Check your inbox" screen never moved (2026-08-20 — same
        //     failure already fixed on Electron/Swift/RN/Flutter).
        val gate = uri.getQueryParameter("gate")
        if (!gate.isNullOrEmpty()) {
            if (auth.isOneloHostedSurface(gate)) {
                OneloAuthActivity.pendingGateReturn?.let { it(gate); return true }
                // No "Check your inbox" screen is alive to receive it — Android
                // routinely kills OneloAuthActivity while it's backgrounded behind
                // the mail app / browser during a magic-link round trip, which nulls
                // pendingGateReturn in onDestroy. Without this the refusal only ever
                // reached Logcat and the app was left sitting on plain "Sign In" with
                // zero feedback (2026-08-20). Relaunch the activity directly with the
                // gate URL, same as any fresh loadAuthView() launch.
                launchGateActivity(gate)
            } else {
                Log.w("Onelo", "refused a gate URL from an unknown origin")
            }
            return true
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

    /** Present [gate] (a validated Onelo hosted-surface URL) in a fresh [OneloAuthActivity]
     *  when no such Activity is currently alive to receive it via [OneloAuthActivity.pendingGateReturn].
     *  Best-effort — a device with no launchable activity for it degrades to a warning. */
    private fun launchGateActivity(gate: String) {
        try {
            appContext.startActivity(
                Intent(appContext, OneloAuthActivity::class.java)
                    .putExtra(OneloAuthActivity.EXTRA_URL, gate)
                    .putExtra(OneloAuthActivity.EXTRA_SCHEME, config.callbackScheme)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w("Onelo", "failed to present gate refusal: ${e.message}")
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

package com.onelo.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.onelo.android.internal.HttpClient
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Wire types
// ---------------------------------------------------------------------------

/**
 * Legal-consent enforcement level. `BLOCK` gates the app until accepted;
 * `NOTIFY` is informational. Unknown future values decode to `UNKNOWN`
 * (forward-compat — never throws), mirroring Swift's `OneloConsentEnforcement`
 * and Electron's `'unknown'`.
 */
enum class OneloConsentEnforcement { BLOCK, NOTIFY, UNKNOWN }

/**
 * One legal document the signed-in user has not yet accepted. Shape mirrors
 * Swift's `OneloConsentRequirement` / Electron's `OneloConsentRequirement`
 * (wire keys are snake_case; see [OneloConsent.mapRequirement]).
 */
data class OneloConsentRequirement(
    /** Document type: "terms" | "privacy" | "dpa" | "cookies" (open set). */
    val docType: String,
    /** The version id to POST back on accept (`document_version_id`). */
    val versionId: String,
    /** Human version label, e.g. "2026-06-01-v2". */
    val version: String,
    /** BLOCK | NOTIFY | UNKNOWN. */
    val enforcement: OneloConsentEnforcement,
    /**
     * True iff this document HARD-blocks the app right now (server-computed:
     * enforcement=block AND effective_at<=now). The single gate signal.
     */
    val blocking: Boolean,
    /** Read-only document URL (may be null for platform-scope docs). */
    val url: String?,
    /**
     * Gate-mode URL (document + accept/decline buttons); loaded in the gate
     * Activity. Null for platform-scope docs (which cannot be gated).
     */
    val consentUrl: String?,
)

/**
 * Outcome of a consent-gate session, delivered to the [OneloConsent.registerLauncher]
 * callback once the gate flow reaches a terminal state.
 *
 * @param accepted  True when every outstanding blocking document was accepted
 *                  (the gate — including any stacked documents — fully cleared).
 * @param declined  True when the user declined. The SDK has ALREADY signed the
 *                  user out (equivalent to `auth.signOut()`) before this fires.
 * @param dismissed True when the gate closed without a decision (main-frame load
 *                  error → fail-open). Session left intact; the next call to
 *                  [OneloConsent.presentGateIfNeeded] re-presents.
 */
data class ConsentResult(
    val accepted: Boolean = false,
    val declined: Boolean = false,
    val dismissed: Boolean = false,
)

// ---------------------------------------------------------------------------
// OneloConsent
// ---------------------------------------------------------------------------

/**
 * Legal-consent gate for signed-in users — the Android port of Swift's
 * `OneloAuthView` blocking consent cover, and Electron/Flutter/RN's auto-gate.
 *
 * At sign-in, consent is enforced server-side inside the hosted sign-in page.
 * This class covers the OTHER moment: a user who is ALREADY signed in when you
 * publish a new blocking legal version (e.g. a Terms update). If a blocking
 * document is outstanding the hosted gate Activity is shown. Accept records
 * consent (and re-checks, since documents stack); decline signs the user out.
 * Fail-open on network errors — the gate surfaces real blocking updates, not a blip.
 *
 * ## Auto-presentation (parity with the other SDKs)
 * By default ([OneloConfig.autoPresentConsentGate] = true) the SDK presents the
 * gate AUTOMATICALLY — the host app only registers a launcher once. The SDK
 * drives presentation at three moments, matching Swift/Electron/Flutter/RN:
 *   • on sign-in (via [Onelo]'s auth-state observer → [notifySignedIn]),
 *   • on the live `legal.consent_required` realtime push ([onRemoteConsentRequired]),
 *   • on host-Activity `ON_RESUME` (covers app foreground + any push that arrived
 *     while backgrounded — Android forbids background Activity starts).
 *
 * Works for BOTH Terms and Privacy — the gate is doc-type-agnostic; it fires for
 * any document the server marks `blocking` (enforcement=block, in effect). NOTIFY
 * docs are never gated in-app (parity — informational only).
 *
 * ## Android constraint: the launcher
 * An Activity cannot self-launch; it needs an [ActivityResultLauncher] the HOST
 * APP registers in `onCreate` (same as [OneloCustomerPortal]). That single call
 * is ALL the app must do — the SDK handles every trigger from there:
 *
 * ```kotlin
 * // In Activity/Fragment onCreate, BEFORE any UI interaction — that's it:
 * onelo.consent.registerLauncher(this, auth = onelo.auth) { result ->
 *     when {
 *         result.declined  -> { /* SDK already signed out — navigate to sign-in */ }
 *         result.accepted  -> { /* all blocking docs accepted — continue */ }
 *         result.dismissed -> { /* load error — the SDK re-presents on next resume */ }
 *     }
 * }
 * ```
 *
 * To drive the gate yourself instead, set [OneloConfig.autoPresentConsentGate] =
 * false and call [presentGateIfNeeded] (e.g. from a [consentRevisionFlow] collector).
 */
class OneloConsent(
    private val config: OneloConfig,
    context: Context,
) {
    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Bound at registerLauncher so the launcher callback can drive the
    // accept/decline/stack flow (which needs auth + onResult + the launcher
    // itself for re-presenting the next stacked document).
    private var boundAuth: OneloAuth? = null
    private var boundOnResult: ((ConsentResult) -> Unit)? = null
    private var boundLauncher: ActivityResultLauncher<Intent>? = null

    // Weak ref to the host Activity so the SDK can (a) verify it is RESUMED before
    // launching the gate Activity — Android forbids background Activity starts —
    // and (b) auto-present on ON_RESUME. Weak so the SDK never leaks the Activity.
    private var boundActivityRef: WeakReference<ComponentActivity>? = null

    // True while an OneloConsentActivity is in-flight — stops a resume or a second
    // trigger from stacking a duplicate gate (the Android analogue of the other
    // SDKs' single-owner gate claim). Reserved via atomic compareAndSet in
    // presentGateIfNeeded BEFORE its network await (airtight even off the main
    // dispatcher), cleared when the gate Activity returns or on any non-launch exit.
    private val isGatePresented = AtomicBoolean(false)

    // The versionId of the document currently shown in the gate — POSTed back on
    // accept. Set synchronously in presentGateIfNeeded before the Activity launches.
    private var pendingVersionId: String? = null

    private var consentRevision = 0
    private val _consentRevisionFlow = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 1)

    /**
     * Emits whenever the backend pushes `legal.consent_required` over the realtime
     * stream (a new BLOCKING legal version took effect while the user is signed in).
     * With [OneloConfig.autoPresentConsentGate] = true (default) the SDK already
     * re-presents the gate on this push — you do NOT need to collect this. It exists
     * for apps that opted OUT of auto-present to drive custom UI. Mirrors
     * Swift/Flutter's `consentRevision` push.
     */
    val consentRevisionFlow: SharedFlow<Int> = _consentRevisionFlow.asSharedFlow()

    /** Called by the realtime stream on `legal.consent_required`. Bumps the
     *  revision (for [consentRevisionFlow] collectors) AND auto-presents the gate
     *  now if enabled + a launcher is registered + the host is resumed. */
    internal fun onRemoteConsentRequired() {
        consentRevision++
        scope.launch { _consentRevisionFlow.emit(consentRevision) }
        autoPresent()
    }

    /** Called by [Onelo]'s auth-state observer right after sign-in so the SDK
     *  auto-presents any outstanding blocking gate without the app lifting a finger. */
    internal fun notifySignedIn() {
        autoPresent()
    }

    // ── Launcher registration ────────────────────────────────────────────────

    /**
     * Register the [ActivityResultLauncher] required to open the consent-gate
     * Activity. Call this in your Activity or Fragment `onCreate`, before any
     * user interaction.
     *
     * @param activity  The [ComponentActivity] that hosts the gate.
     * @param auth      The [OneloAuth] instance. Required — on Decline the SDK
     *                  signs the user out; on Accept it records consent using
     *                  the current session's access token.
     * @param onResult  Callback invoked on the main thread when the gate flow
     *                  reaches a terminal state (see [ConsentResult]).
     */
    fun registerLauncher(
        activity: ComponentActivity,
        auth: OneloAuth,
        onResult: (ConsentResult) -> Unit,
    ): ActivityResultLauncher<Intent> {
        val launcher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            handleActivityResult(result)
        }
        boundAuth = auth
        boundOnResult = onResult
        boundLauncher = launcher
        boundActivityRef = WeakReference(activity)
        // Auto-present on resume — covers sign-in, app foreground, and any consent
        // push that arrived while the app was backgrounded. The host app registers
        // the launcher; the SDK drives every presentation from here (parity with
        // Swift/Electron/Flutter/RN, where the app only provides a host).
        activity.lifecycle.addObserver(
            LifecycleEventObserver { source, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> autoPresent()
                    Lifecycle.Event.ON_DESTROY -> {
                        // Release the strong refs to the host so the Activity can be
                        // GC'd — `boundOnResult` usually captures it, which would
                        // otherwise defeat the WeakReference. Identity-guarded: after a
                        // rotation the NEW Activity has already re-registered, so the
                        // OLD one's ON_DESTROY must not clear the fresh binding.
                        if (boundActivityRef?.get() === source) {
                            boundOnResult = null
                            boundLauncher = null
                            boundActivityRef = null
                        }
                    }
                    else -> {}
                }
            }
        )
        autoPresent()
        return launcher
    }

    private fun handleActivityResult(
        result: androidx.activity.result.ActivityResult,
    ) {
        // Do NOT release isGatePresented here. The gate Activity closing RESUMES the
        // host, firing the ON_RESUME auto-present — releasing now would let it re-check
        // and re-show the doc BEFORE acceptConsent/signOut is recorded (the "accepts
        // twice" / "gate flashes on sign-out" bug). The slot stays reserved across the
        // whole terminal action and is released only when it truly resolves.
        val auth = boundAuth ?: run { isGatePresented.set(false); return }
        val launcher = boundLauncher ?: run { isGatePresented.set(false); return }
        val onResult = boundOnResult

        if (result.resultCode == Activity.RESULT_OK) {
            val action = result.data?.getStringExtra(OneloConsentActivity.EXTRA_ACTION)
            val versionId = pendingVersionId
            pendingVersionId = null
            if (action == "accept") {
                scope.launch {
                    if (versionId != null) {
                        // Swallow accept errors (parity with Swift's `try?` /
                        // Electron's catch) so a transient failure lets the user
                        // retry rather than bouncing them out.
                        try { acceptConsent(auth, versionId) } catch (_: Exception) { /* allow retry */ }
                    }
                    // Documents stack — re-check for the NEXT blocking document WITHOUT
                    // re-CASing (we already hold the slot), so the resume-fired
                    // auto-present can't race in. Releases the slot when none remain.
                    val shown = launchGateOrRelease(auth, launcher)
                    if (!shown) onResult?.invoke(ConsentResult(accepted = true))
                }
            } else {
                // Decline (or any non-accept) → sign out. No server decline
                // endpoint; parity with Swift/Electron.
                scope.launch {
                    try { auth.signOut() } catch (_: Exception) { /* best-effort */ }
                    // Release only AFTER sign-out completes, so the resume-fired
                    // auto-present can't flash the gate during the signed-in window.
                    isGatePresented.set(false)
                    onResult?.invoke(ConsentResult(declined = true))
                }
            }
        } else {
            // RESULT_CANCELED: the gate cannot be dismissed by Back, so this only
            // happens on a main-frame load error (fail-open). Leave the session
            // intact; the next trigger re-presents.
            pendingVersionId = null
            isGatePresented.set(false)
            onResult?.invoke(ConsentResult(dismissed = true))
        }
    }

    // ── Data API (mirrors Swift requiredConsents / acceptConsent) ────────────

    /**
     * Fetch the signed-in user's outstanding legal documents. Requires a
     * session; returns `emptyList()` when signed out. Fail-open: any
     * network/non-200/parse failure returns `emptyList()` (never throws) —
     * parity with Swift/Electron `requiredConsents()`.
     */
    suspend fun requiredConsents(auth: OneloAuth): List<OneloConsentRequirement> {
        return try {
            // getSession() is INSIDE the try: near token expiry it triggers a refresh
            // that can THROW (revoked/expired) — outside the try that would propagate
            // out of presentGateIfNeeded and strand the gate reservation. Fail-open.
            val session = auth.getSession() ?: return emptyList()
            val resp = HttpClient.get(
                url = "${config.apiUrl}/v1/sdk/consent/required",
                headers = consentHeaders(session.accessToken),
            )
            if (resp.status != 200) return emptyList()
            // WRINKLE: HttpClient.jsonObjectToMap maps a JSON array's elements to
            // RAW org.json.JSONObject (NOT Map) — see HttpClient.kt. So the
            // `required` rows are JSONObject and MUST be parsed as such.
            val rows = resp.body["required"] as? List<*> ?: return emptyList()
            rows.mapNotNull { mapRequirement(it) }
        } catch (_: Exception) {
            // Fail-open — the gate surfaces real blocking updates, not a blip.
            emptyList()
        }
    }

    /**
     * Record acceptance of a legal document version. Requires a session. Throws
     * [OneloError] on no-session or a non-2xx response. Server-side idempotent.
     * Mirrors Swift/Electron `acceptConsent(versionId)`.
     */
    suspend fun acceptConsent(auth: OneloAuth, versionId: String) {
        val session = auth.getSession() ?: throw OneloError.notAuthenticated()
        val resp = HttpClient.post(
            url = "${config.apiUrl}/v1/sdk/consent/accept",
            // snake_case `document_version_id` — the backend ConsentActionIn model.
            body = mapOf("document_version_id" to versionId),
            headers = consentHeaders(session.accessToken),
        )
        if (resp.status == 401) throw OneloError.notAuthenticated()
        if (resp.status < 200 || resp.status >= 300) {
            throw OneloError.server("Failed to record consent: HTTP ${resp.status}")
        }
    }

    // ── Gate presentation ────────────────────────────────────────────────────

    /**
     * Check for an outstanding BLOCKING consent and, if present, launch the hosted
     * gate Activity via [launcher]. Returns `true` if a gate was shown. No-op
     * (returns `false`) when: no session, no blocking document with a gate URL,
     * [config.apiUrl] is blank, or a gate is already in-flight.
     *
     * With [OneloConfig.autoPresentConsentGate] = true (default) the SDK calls this
     * for you (on sign-in, the `legal.consent_required` push, and resume). Call it
     * manually only when you opted OUT of auto-present. The [launcher] MUST be one
     * returned by [registerLauncher].
     */
    suspend fun presentGateIfNeeded(
        auth: OneloAuth,
        launcher: ActivityResultLauncher<Intent>,
    ): Boolean {
        if (config.apiUrl.isBlank()) return false
        // Reserve the single-owner slot (atomic CAS) so two near-simultaneous triggers
        // (sign-in + SSE push, resume + push) can't both launch a gate. The slot is
        // held from here until the ENTIRE flow resolves (all stacked docs accepted, or
        // declined/dismissed — see handleActivityResult), so the ON_RESUME auto-present
        // that fires when a gate closes can't race in and re-show a just-actioned doc.
        if (!isGatePresented.compareAndSet(false, true)) return false
        return launchGateOrRelease(auth, launcher)
    }

    /**
     * Present the next outstanding blocking gate, or release the slot when nothing is
     * left. ASSUMES [isGatePresented] is ALREADY reserved by the caller — used by
     * [presentGateIfNeeded] (right after its CAS) AND by the accept path, which keeps
     * the SAME reservation across acceptConsent → next-doc so a resume can't double-show.
     */
    private suspend fun launchGateOrRelease(
        auth: OneloAuth,
        launcher: ActivityResultLauncher<Intent>,
    ): Boolean {
        try {
            val items = requiredConsents(auth)
            val blocker = items.firstOrNull { it.blocking && it.consentUrl != null }
            if (blocker == null) {
                isGatePresented.set(false)
                return false
            }
            // Re-check RESUMED right before launch: requiredConsents() awaited the
            // network, during which the app may have backgrounded. Android SILENTLY
            // DROPS a background Activity start (no throw, no result), which would
            // strand the reservation forever. Not resumed → release + defer; the next
            // ON_RESUME re-triggers.
            val resumed = boundActivityRef?.get()
                ?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
            if (!resumed) {
                isGatePresented.set(false)
                return false
            }
            // Set synchronously BEFORE launching so the launcher callback POSTs back
            // the correct versionId on accept.
            pendingVersionId = blocker.versionId
            launcher.launch(
                Intent(appContext, OneloConsentActivity::class.java)
                    .putExtra(OneloConsentActivity.EXTRA_URL, blocker.consentUrl)
            )
            return true
        } catch (e: Exception) {
            // Any post-reservation failure (e.g. launcher unregistered →
            // IllegalStateException) must release the slot, or the gate is disabled
            // for the app's lifetime. Fail-open — a blip must not lock the gate.
            isGatePresented.set(false)
            return false
        }
    }

    /**
     * Auto-present entrypoint used by the sign-in / SSE-push / resume triggers.
     * No-op unless auto-present is enabled, a launcher is registered, and the host
     * Activity is RESUMED (Android forbids background Activity starts — a push that
     * arrives while backgrounded is re-tried on the next `ON_RESUME`). The actual
     * dedup + "is there a blocker" check lives in [presentGateIfNeeded].
     */
    private fun autoPresent() {
        if (!config.autoPresentConsentGate) return
        if (isGatePresented.get()) return
        val auth = boundAuth ?: return
        val launcher = boundLauncher ?: return
        val activity = boundActivityRef?.get() ?: return
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        scope.launch { presentGateIfNeeded(auth, launcher) }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Map one wire row (a raw [JSONObject] per the HttpClient array-parsing
     * wrinkle) to the SDK shape. Forward-compat: unknown enforcement → UNKNOWN;
     * missing `blocking` → false (never gate on a field the server didn't send).
     * Drops rows with no `version_id` (the load-bearing POST-back key) rather
     * than surface a requirement we can't accept. Mirrors Swift's soft-decode.
     */
    private fun mapRequirement(raw: Any?): OneloConsentRequirement? {
        val obj = raw as? JSONObject ?: return null
        // version_id is load-bearing — drop malformed rows.
        val versionId = obj.optString("version_id").ifBlank { null } ?: return null
        val rawEnf = obj.optString("enforcement")
        val enforcement = when (rawEnf) {
            "block" -> OneloConsentEnforcement.BLOCK
            "notify" -> OneloConsentEnforcement.NOTIFY
            else -> OneloConsentEnforcement.UNKNOWN
        }
        return OneloConsentRequirement(
            docType = obj.optString("doc_type"),
            versionId = versionId,
            version = obj.optString("version"),
            enforcement = enforcement,
            blocking = obj.optBoolean("blocking", false),
            url = obj.optString("url").ifBlank { null },
            consentUrl = obj.optString("consent_url").ifBlank { null },
        )
    }

    private fun consentHeaders(accessToken: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $accessToken",
        "X-Publishable-Key" to config.publishableKey,
        "X-SDK-Version" to SDK_VERSION,
        "X-Onelo-Sdk-Platform" to "android",
    )
}

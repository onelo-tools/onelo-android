package com.onelo.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import com.onelo.android.internal.HttpClient
import com.onelo.android.internal.HttpResponse
import com.onelo.android.internal.UserTokenSource
import com.onelo.android.internal.bearerHeaders
import com.onelo.android.internal.sendWithUserToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.random.Random

enum class FeatureStatus {
    ENABLED, DISABLED, GREYED, HIDDEN, UPSELL, NEW, BETA, COMING_SOON
}

/** Decoded feature entry — status + upsell metadata (parity w/ Swift FeatureState). */
internal data class ResolvedFeature(
    val status: FeatureStatus,
    val reason: String? = null,
    val requiredPlan: String? = null,
    val requiredPlanLabel: String? = null,
    val upgradeCta: Boolean = false,
)

/** Plan-gate upgrade hint — non-null when a feature is blocked by plan and the backend
 *  said which plan unlocks it. Surface it in upgrade-prompt UI. 1:1 with Swift `UpgradeHint`
 *  (Types.swift). [currentStatus] tells you HOW it's gated (greyed/hidden/upsell/coming_soon). */
data class UpgradeHint(
    val requiredPlan: String,
    val currentStatus: FeatureStatus,
)

class OneloFeatureHandle internal constructor(private val resolved: ResolvedFeature) {
    val status: FeatureStatus get() = resolved.status

    /** Why the status resolved this way (`plan`/`override`/`static`), or null. */
    val reason: String? get() = resolved.reason
    /** For a locked/upsell feature, the plan SLUG that unlocks it (e.g. `pro`). */
    val requiredPlan: String? get() = resolved.requiredPlan
    /** Human label for [requiredPlan] (e.g. `Pro`). */
    val requiredPlanLabel: String? get() = resolved.requiredPlanLabel
    /** True when the backend marked this an upgrade opportunity. */
    val upgradeCta: Boolean get() = resolved.upgradeCta
    /** Best label for the gating plan — [requiredPlanLabel] ?: [requiredPlan]. Use
     *  for "Available in ${planLabel}". */
    val planLabel: String? get() = resolved.requiredPlanLabel ?: resolved.requiredPlan

    fun isEnabled(): Boolean = status == FeatureStatus.ENABLED || status == FeatureStatus.NEW || status == FeatureStatus.BETA
    fun isVisible(): Boolean = status != FeatureStatus.HIDDEN
    fun isGreyed(): Boolean = status == FeatureStatus.GREYED
    fun isUpsell(): Boolean = status == FeatureStatus.UPSELL
    fun isDisabled(): Boolean = status == FeatureStatus.DISABLED
    fun isNew(): Boolean = status == FeatureStatus.NEW
    fun isBeta(): Boolean = status == FeatureStatus.BETA
    fun isComingSoon(): Boolean = status == FeatureStatus.COMING_SOON
    /** Promo/lock badge (parity with Swift/JS/RN/Electron): "New"/"Beta"/"Coming Soon",
     *  "🔒" for GREYED (locked — the dashboard's "Visible with a padlock" fallback; NEVER
     *  hide it), and "Available in <plan>" for UPSELL. Null for the rest. Render it next
     *  to the feature so a gated user sees the padlock / hint the backend sent. Previously
     *  GREYED + UPSELL returned null, silently dropping the padlock + upgrade prompt. */
    val badgeLabel: String? get() = when (status) {
        FeatureStatus.NEW         -> "New"
        FeatureStatus.BETA        -> "Beta"
        FeatureStatus.COMING_SOON -> "Coming Soon"
        FeatureStatus.GREYED      -> "🔒"
        // Prefer the human product name; the bare slug only as a stopgap for older
        // backends. Empty label must NOT shadow a valid slug (Swift OneloSDKTypes.swift:76-78).
        FeatureStatus.UPSELL      ->
            (requiredPlanLabel?.takeIf { it.isNotBlank() } ?: requiredPlan?.takeIf { it.isNotBlank() })
                ?.let { "Available in $it" } ?: "Upgrade"
        else                      -> null
    }

    /** Non-null when the feature is blocked by plan AND the backend said which plan
     *  unlocks it — surface it in upgrade-prompt UI. 1:1 with Swift `FeatureState.upgradeHint`
     *  (OneloSDKTypes.swift:85-91): reason == "plan" and status ∈ {greyed, hidden, upsell,
     *  coming_soon} with a non-null requiredPlan. */
    val upgradeHint: UpgradeHint? get() {
        if (reason != "plan") return null
        val plan = requiredPlan ?: return null
        return if (status == FeatureStatus.GREYED || status == FeatureStatus.HIDDEN ||
            status == FeatureStatus.UPSELL || status == FeatureStatus.COMING_SOON
        ) UpgradeHint(plan, status) else null
    }
}

class OneloFeatures internal constructor(
    private val config: OneloConfig,
    private val monitor: OneloMonitor? = null,
    context: Context? = null,
    /**
     * The HTTP leg of [poll], defaulting to the real [HttpClient.get]. Tests swap
     * it to capture the exact URL + headers `/poll` is sent with — the same seam
     * [OneloMonitor] exposes as `httpOverride`. `HttpClient` is an `object` and
     * there is no mocking library in this module, so a function reference is the
     * smallest hole that lets a test see the wire request rather than its parts.
     */
    private val httpGet: suspend (url: String, headers: Map<String, String>) -> HttpResponse = HttpClient::get,
    /** The POST leg of resolve / batch-ping / module-token — same seam as [httpGet]. */
    private val httpPost: suspend (url: String, body: Map<String, Any?>, headers: Map<String, String>) -> HttpResponse = HttpClient::post,
    /**
     * The signed-in Onelo user's access token, sent as `Authorization: Bearer`
     * on every Features call so the server knows who the user really is (#519).
     * Wired by [Onelo] to [OneloAuth]; null sends no token.
     */
    private val userToken: UserTokenSource? = null,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Last-known-good cache on disk (plain prefs — flags aren't secret) so a cold
    // start renders instantly instead of flashing "everything hidden" until the
    // first resolve lands. Null in tests / when no context → in-memory only.
    private val prefs: SharedPreferences? =
        context?.applicationContext?.getSharedPreferences("onelo_features_cache", Context.MODE_PRIVATE)
    // Guards cache + configVersion + identity together. Never held across a suspension.
    private val lock = Any()
    // @Volatile so feature() reading `cache` off the UI thread has a happens-before
    // with the lock-guarded writer on Dispatchers.IO — without it the reader can
    // observe a stale snapshot indefinitely (missed live updates). Each write is a
    // whole-map SWAP, so publishing the new reference via volatile is sufficient.
    @Volatile private var cache: Map<String, ResolvedFeature> = emptyMap()
    @Volatile private var configVersion: Int = 0
    // Concurrent set: feature()/declare() add from any thread; batchPing() iterates.
    private val discovered: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // Reactive tick — bumped on every cache change (resolve / poll / SSE / disk cache).
    // Compose UIs collect [updates] (collectAsState) to RECOMPOSE when features change,
    // so a dashboard Deploy reflects live without a restart. This is the Compose-idiomatic
    // mirror of Swift's @Observable OneloFeatures (SwiftUI auto-recomposes on feature
    // changes). The Int value is meaningless — react to the CHANGE, then re-read feature().
    private val _updates = MutableStateFlow(0)
    val updates: StateFlow<Int> get() = _updates.asStateFlow()
    @Volatile var userId: String? = null
    @Volatile private var userTokenRefusedWarned = false

    private var pollJob: Job? = null
    private var pingJob: Job? = null
    @Volatile private var securityHeaders: Map<String, String> = emptyMap()
    @Volatile private var anonymousWarningLogged: Boolean = false

    /**
     * #737 — the identity the in-memory snapshot belongs to. [generation] bumps on
     * every identity change; a resolve / poll / stream answer captured under an
     * older generation is dropped. [confirmed] = the server answered for THIS
     * identity (not a persisted copy, not an anonymous fallback); [ready] waits on it.
     */
    private data class Identity(val generation: Int, val userId: String?, val confirmed: Boolean)
    private val identity = MutableStateFlow(Identity(0, null, false))
    @Volatile private var identityBound = false
    @Volatile private var lastRefreshAt: Long = 0L

    /** Normalized Features environment ("test"/"live") or null when unset. */
    internal val featureEnvironment: String? = when (config.featureEnvironment?.trim()?.lowercase()) {
        "test" -> "test"
        "live" -> "live"
        else   -> null
    }

    // #3 foreground-resync state (ActivityLifecycleCallbacks run on the main thread → no sync).
    private var startedActivities = 0
    private var hasBackgrounded = false

    init {
        // Force-refresh the snapshot when the app returns to the FOREGROUND — 1:1 with
        // Swift `_resyncOnLifecycle`, which for the SHARED event stream (Android's only
        // mode) just force-refreshes over REST (the SSE reconnect is auth-owned). Uses
        // the framework ActivityLifecycleCallbacks (no extra dependency); a 0→1 started-
        // activity transition = the app came to the foreground. We deliberately SKIP the
        // initial launch (first 0→1 with hasBackgrounded=false): load() already resolves
        // on launch, and refreshing there could race load() before userId is set and
        // clobber the snapshot with an anonymous one.
        if (config.autoLifecycleRefresh) {
            (context?.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityStarted(activity: Activity) {
                        if (startedActivities++ == 0 && hasBackgrounded) {
                            scope.launch { runCatching { refresh(force = true) } }
                        }
                    }
                    override fun onActivityStopped(activity: Activity) {
                        if (startedActivities > 0 && --startedActivities == 0) hasBackgrounded = true
                    }
                    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                    override fun onActivityResumed(activity: Activity) {}
                    override fun onActivityPaused(activity: Activity) {}
                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                    override fun onActivityDestroyed(activity: Activity) {}
                }
            )
        }
    }

    /** Builds the resolve POST body, adding `environment`/`userId` only when set.
     *  `userId` rides along with the Bearer: the server ignores it when the token
     *  is valid, and a server without #517 still resolves by it. */
    internal fun resolveBody(userId: String?): Map<String, Any?> {
        val body = mutableMapOf<String, Any?>("publishableKey" to config.publishableKey)
        userId?.let { body["userId"] = it }
        featureEnvironment?.let { body["environment"] = it }
        return body
    }

    /** Builds the batch-ping POST body. */
    internal fun batchPingBody(names: List<String>, userId: String? = null): Map<String, Any?> {
        val body = mutableMapOf<String, Any?>(
            "publishableKey" to config.publishableKey,
            "features" to names
        )
        userId?.let { body["userId"] = it }
        featureEnvironment?.let { body["environment"] = it }
        return body
    }

    /**
     * Query string for `/poll`. Sends `since_version` (the backend cursor param —
     * the old `version` was ignored, so the up_to_date fast-path was dead).
     * The key is NOT here — it travels as the
     * `X-Publishable-Key` request header ([pollHeaders], ADR-0003, #105),
     * exactly as OneloEventStream sends it on the stream. /poll fires on a
     * timer, so a raw key in the URL landed in the reverse proxy's access.log
     * on every tick; from 2027-03-31 the backend refuses `?key=` outright.
     */
    internal fun pollParams(userId: String?, version: Int): String = buildString {
        append("since_version=$version")
        userId?.let { append("&userId=$it") }
        featureEnvironment?.let { append("&environment=$it") }
    }

    /** Request headers for `/poll`: the credential plus the security headers the
     *  stream also carries, and the signed-in user's Bearer when there is one. */
    internal fun pollHeaders(token: String? = null): Map<String, String> =
        securityHeaders + mapOf("X-Publishable-Key" to config.publishableKey) + bearerHeaders(token)

    companion object {
        private const val POLL_INTERVAL_MS = 60_000L
        private const val PING_DEBOUNCE_MS = 1_000L
        private const val REFRESH_DEBOUNCE_MS = 1_000L
        private const val LOG_TAG = "Onelo"
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** True once the server has CONFIRMED the snapshot for the CURRENT identity
     *  (the restored user at launch, the new user after a sign-in or switch, or the
     *  signed-out identity). Resets on every identity change (#737). */
    val isReady: Boolean get() = identity.value.confirmed

    /** Awaits the current identity's confirmed snapshot, at most [timeoutMs]. Await it
     *  once before showing gated UI — at launch and after each sign-in. A waiter that
     *  outlives an identity change waits for the NEW identity. A network failure does
     *  not confirm: after the timeout feature() serves the persisted/default state and
     *  [updates] ticks when the confirmed one lands. Never throws. Mirrors Swift `ready`. */
    suspend fun ready(timeoutMs: Long = 1500) {
        withTimeoutOrNull(timeoutMs) { identity.first { it.confirmed } }
    }

    fun declare(names: List<String>) {
        discovered.addAll(names)
        scheduleBatchPing()
    }

    fun feature(name: String): OneloFeatureHandle {
        val isNew = discovered.add(name)
        if (isNew) scheduleBatchPing()
        if (isNew) monitor?.trackFeatureCall(name)
        val resolved = cache[name] ?: ResolvedFeature(config.featureDefaultStatus)
        // Feed flag state to Monitor so errors are enriched with the flags that
        // were live when they happened (matches Swift/Flutter recordFlag).
        monitor?.recordFlag(name, resolved.status.name.lowercase())
        return OneloFeatureHandle(resolved)
    }

    fun getActiveFeatures(): List<String> =
        cache.entries
            .filter { (_, f) -> f.status == FeatureStatus.ENABLED || f.status == FeatureStatus.NEW || f.status == FeatureStatus.BETA }
            .map { it.key }

    fun invalidateCache() {
        synchronized(lock) {
            cache = emptyMap()
            configVersion = 0
            identity.value = identity.value.copy(confirmed = false)
        }
    }

    /** A comparable picture of the current snapshot — the plan-change refresher
     *  (#659) re-checks until it differs. */
    internal fun snapshotKey(): String = cache.toSortedMap().toString()

    /** Force a REST snapshot reconcile. Debounced to one call/sec unless [force].
     *  Never throws — the cached state stays valid on failure. */
    suspend fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshAt < REFRESH_DEBOUNCE_MS) return
        lastRefreshAt = now
        resolve()
    }

    /**
     * Mint a short-lived, feature-scoped module token so a CDN serves gated module
     * code only to entitled users. Requires a signed-in user (throws [OneloError]
     * NOT_AUTHENTICATED otherwise, and when their access token is refused even
     * after a refresh); a backend 403 → NOT_ENTITLED. Mirrors Swift `moduleToken(for:)`.
     */
    suspend fun moduleToken(slug: String): String {
        val uid = userId ?: throw OneloError.notAuthenticated()
        val body = mapOf<String, Any?>(
            "publishableKey" to config.publishableKey,
            "userId" to uid,
            "slug" to slug,
        )
        val resp = try {
            // A module token is always for a user, so a refused token ends here
            // (NOT_AUTHENTICATED) instead of retrying anonymously.
            sendWithUserToken(userToken, anonymousFallback = false, onRefused = ::warnUserTokenRefused) { attempt ->
                httpPost("${config.apiUrl}/api/sdk/features/module-token", body, securityHeaders + bearerHeaders(attempt.token))
            }
        } catch (e: OneloError) {
            throw e
        } catch (e: Exception) {
            throw OneloError.network("module-token request failed: ${e.message}")
        }
        return when (resp.status) {
            200 -> (resp.body["token"] as? String)?.takeIf { it.isNotEmpty() }
                ?: throw OneloError.network("module-token response missing token")
            // Any 401 — including a user token refused even after a refresh — means
            // the user is not proven (Secure Mode's codes are retired, #519).
            401 -> throw OneloError.notAuthenticated()
            403 -> throw OneloError.notEntitled()
            else -> throw OneloError.network("module-token failed: HTTP ${resp.status}")
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /** [userIdHash] is ignored: Secure Mode is retired, and the signed-in Onelo
     *  user is proven with their access token automatically. */
    internal suspend fun load(
        userId: String?,
        securityHeaders: Map<String, String> = emptyMap(),
        @Suppress("UNUSED_PARAMETER") userIdHash: String? = null,
    ) {
        this.securityHeaders = securityHeaders
        bindIdentity(userId)
        stopPolling()
        // Discovery ping on load is gated: always in test env (reliable dev
        // discovery), else a 1% sample on live — always-pinging thousands of prod
        // launches would DDoS the registry. Mirrors Swift/Flutter. Fire-and-forget:
        // it must not delay this identity's resolve (#737).
        if (shouldBatchPingOnLoad()) scope.launch { batchPing() }
        resolve()
        startPolling()
    }

    /**
     * Bind the Features identity (#737). A CHANGE of identity starts a new
     * generation: in-flight answers for the old one are dropped, readiness resets,
     * the in-memory snapshot becomes the new identity's own persisted snapshot (an
     * instant, still-unconfirmed render) or EMPTY — never the previous identity's —
     * and the version cursor restarts at 0 so `/poll` never claims a snapshot the
     * server has not confirmed for this identity. Re-binding the same identity is a
     * no-op. Synchronous: [Onelo] calls it at construction with the stored user.
     */
    internal fun bindIdentity(uid: String?) {
        synchronized(lock) {
            val cur = identity.value
            if (identityBound && cur.userId == uid) { userId = uid; return }
            identityBound = true
            userId = uid
            cache = readDiskCache(uid) ?: emptyMap()
            configVersion = 0
            identity.value = Identity(cur.generation + 1, uid, false)
            _updates.update { it + 1 }
        }
    }

    /** Apply a server-confirmed snapshot captured under generation [gen] for [uid].
     *  Dropped (false) when the identity moved on meanwhile. */
    private fun applyConfirmed(gen: Int, uid: String?, features: Map<String, Any?>?, version: Int?): Boolean {
        synchronized(lock) {
            if (identity.value.generation != gen) return false
            if (features != null) {
                cache = features.mapNotNull { (k, v) -> parseFeature(v)?.let { k to it } }.toMap()
                _updates.update { it + 1 }
            }
            version?.let { configVersion = it }
            if (features != null) writeDiskCache(uid)
            identity.value = identity.value.copy(confirmed = true)
            return true
        }
    }

    internal fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        pingJob?.cancel()
        pingJob = null
    }

    /**
     * Apply a realtime `{config_version, features}` snapshot delivered over SSE.
     *  • `features_updated` and the `connected` frame carry a FULL snapshot →
     *    replace the cache. • `up_to_date` carries only `config_version` → advance
     *    the cursor without touching the cache.
     *
     * [streamUserId] is the user the delivering connection is actually bound to
     * (null for an anonymous connection, including the refused-token fallback).
     * A frame from a connection bound to another identity is ignored (#737), and
     * `up_to_date` confirms nothing the server has not already confirmed here.
     */
    internal fun applySseSnapshot(data: Map<String, Any?>, streamUserId: String?) {
        val current = identity.value
        if (streamUserId != current.userId) return
        val version = (data["config_version"] as? Number)?.toInt()
        @Suppress("UNCHECKED_CAST")
        val features = data["features"] as? Map<String, Any?>
        if (features == null) {
            // up_to_date: only meaningful for a snapshot already confirmed for this identity.
            synchronized(lock) {
                if (identity.value.generation == current.generation && identity.value.confirmed) version?.let { configVersion = it }
            }
            return
        }
        applyConfirmed(current.generation, current.userId, features, version)
    }

    /** SSE `discovery_requested` — re-announce declared slugs to the registry.
     *  Debounced via [scheduleBatchPing] (1s) so a burst of pushes coalesces into one
     *  ping (1:1 with Swift routing `discovery_requested` through `_scheduleBatchPing`). */
    internal fun onDiscoveryRequested() {
        scheduleBatchPing()
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun shouldBatchPingOnLoad(): Boolean {
        if (featureEnvironment == "test" || config.publishableKey.contains("_test_")) return true
        return Random.nextInt(100) == 0
    }

    private fun scheduleBatchPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            delay(PING_DEBOUNCE_MS)
            batchPing()
        }
    }

    // `internal` (not private) so the user-token tests can drive one ping.
    internal suspend fun batchPing() {
        val names = discovered.toList()
        if (names.isEmpty()) return
        val uid = userId
        try {
            // batch-ping answers a throttled 204 BEFORE it looks at the token, so
            // its 2xx proves nothing about the token and must not end a refusal
            // episode (`provesAcceptance = false`).
            sendWithUserToken(userToken, provesAcceptance = false, onRefused = ::warnUserTokenRefused) { attempt ->
                httpPost(
                    "${config.apiUrl}/api/sdk/features/batch-ping",
                    batchPingBody(names, if (attempt.anonymous) null else uid),
                    securityHeaders + bearerHeaders(attempt.token),
                )
            }
        } catch (e: Exception) {
            // No silent swallow (project rule + parity w/ Swift's hint). A rejected
            // batch-ping usually means this instance isn't authorized under Features →
            // Registry → Deploy access.
            runCatching { Log.w(LOG_TAG, "features batch-ping rejected — check Features → Deploy access: ${e.message}") }
        }
    }

    /** The server refused the signed-in user's access token even after a refresh,
     *  so the call went out anonymous. Features has no error callback; this
     *  one-time warning is its error channel (like the anonymous-mode warning). */
    private fun warnUserTokenRefused() {
        if (userTokenRefusedWarned) return
        userTokenRefusedWarned = true
        runCatching {
            Log.w(
                LOG_TAG,
                "The signed-in user's access token was refused by Features (invalid_user_token) even after " +
                    "a refresh, so the request was made without a user. Check that the publishableKey belongs " +
                    "to the app the user signed in to.",
            )
        }
    }

    // `internal` (not private) so the user-token tests can drive one resolve.
    internal suspend fun resolve() {
        try {
            val gen = identity.value.generation
            val uid = userId
            // Signed in: a refused token never falls back to an anonymous answer —
            // that would replace the user's snapshot with the global one (#737).
            // Signed out: no token, so the user's Bearer can't answer for "anon".
            val response = sendWithUserToken(
                if (uid == null) null else userToken,
                anonymousFallback = false,
                onRefused = ::warnUserTokenRefused,
            ) { attempt ->
                httpPost(
                    "${config.apiUrl}/api/sdk/features/resolve",
                    resolveBody(if (attempt.anonymous) null else uid),
                    securityHeaders + bearerHeaders(attempt.token),
                )
            }
            if (response.status !in 200..299) return
            @Suppress("UNCHECKED_CAST")
            val features = response.body["features"] as? Map<String, Any?> ?: return
            if (!applyConfirmed(gen, uid, features, (response.body["config_version"] as? Number)?.toInt())) return
            maybeWarnAnonymous(response.body)
        } catch (_: Exception) {
            // Offline: keep the current snapshot; ready() is bounded by its timeout.
        }
    }

    /**
     * Logs a one-time warning when the backend reports anonymous mode (no userId)
     * AND at least one targeted feature was hidden purely because of it.
     */
    private fun maybeWarnAnonymous(body: Map<String, Any?>) {
        if (config.suppressIdentifyWarning || anonymousWarningLogged) return
        val anonymous = body["anonymous"] as? Boolean ?: return
        if (!anonymous) return
        val misses = (body["targeting_misses"] as? Number)?.toInt() ?: 0
        if (misses <= 0) return
        anonymousWarningLogged = true
        Log.w(
            LOG_TAG,
            "$misses feature(s) hidden because no user is identified.\n" +
                "If you handle auth yourself, call onelo.identify(userId) after login so per-user/per-plan targeting can apply.\n" +
                "If your app is intentionally anonymous, set suppressIdentifyWarning = true in OneloConfig to silence this."
        )
    }

    // `internal` (not private) so OneloFeaturesPollWiringTest can drive one tick
    // through the real URL + header assembly without waiting on the 60s timer.
    internal suspend fun poll() {
        try {
            val gen = identity.value.generation
            val uid = userId
            val response = sendWithUserToken(
                if (uid == null) null else userToken,
                anonymousFallback = false,
                onRefused = ::warnUserTokenRefused,
            ) { attempt ->
                val params = pollParams(if (attempt.anonymous) null else uid, configVersion)
                httpGet("${config.apiUrl}/api/sdk/features/poll?$params", pollHeaders(attempt.token))
            }
            if (response.status != 200) return
            val data = response.body
            val version = (data["config_version"] as? Number)?.toInt()
            // Backend returns `up_to_date` (not `changed`) — no `features` payload
            // when our since_version matches. configVersion is non-zero only for a
            // snapshot confirmed for this identity, so up_to_date only keeps it.
            @Suppress("UNCHECKED_CAST")
            val features = if (data["up_to_date"] == true) null else data["features"] as? Map<String, Any?>
            if (features != null) {
                applyConfirmed(gen, uid, features, version)
            } else if (data["up_to_date"] == true) {
                synchronized(lock) {
                    if (identity.value.generation == gen && identity.value.confirmed) version?.let { configVersion = it }
                }
            }
            if (data["discovery_requested"] == true) batchPing()
        } catch (_: Exception) {}
    }

    private fun startPolling() {
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                poll()
            }
        }
    }

    /** Single source of truth for decoding one wire feature entry (resolve/poll/SSE
     *  + disk cache), mirroring Swift's FeatureStateWire / Flutter's fromWire. */
    /** Returns null when the entry is malformed OR carries an UNKNOWN wire status —
     *  the caller then DROPS it so [feature] falls back to [OneloConfig.featureDefaultStatus]
     *  (1:1 with Swift dropping unrecognized statuses via `FeatureStatus(rawValue:)==nil`
     *  → `continue`, rather than pinning them to hidden and leaking their metadata). */
    private fun parseFeature(v: Any?): ResolvedFeature? {
        val m = v as? Map<*, *> ?: return null
        val status = parseStatus((m["status"] as? String) ?: "") ?: return null
        return ResolvedFeature(
            status = status,
            reason = m["reason"] as? String,
            requiredPlan = m["required_plan"] as? String,
            requiredPlanLabel = m["required_plan_label"] as? String,
            upgradeCta = m["upgrade_cta"] == true,
        )
    }

    private fun parseStatus(s: String): FeatureStatus? = when (s.lowercase()) {
        "enabled"     -> FeatureStatus.ENABLED
        "disabled"    -> FeatureStatus.DISABLED
        "greyed"      -> FeatureStatus.GREYED
        "hidden"      -> FeatureStatus.HIDDEN
        "upsell"      -> FeatureStatus.UPSELL
        "new"         -> FeatureStatus.NEW
        "beta"        -> FeatureStatus.BETA
        "coming_soon" -> FeatureStatus.COMING_SOON
        else          -> null   // unknown/newer-backend status → dropped by the caller
    }

    // ── Disk cache (last-known-good, per user) ────────────────────────────────

    private fun cacheKey(userId: String?): String = "features_${config.publishableKey}_${userId ?: "anon"}"

    /** [userId]'s persisted snapshot (instant, unconfirmed cold-start render), or null
     *  when there's no context / no stored entry / a corrupt entry. Its stored
     *  config_version is deliberately not used as a cursor (#737). */
    private fun readDiskCache(userId: String?): Map<String, ResolvedFeature>? {
        val raw = prefs?.getString(cacheKey(userId), null) ?: return null
        return try {
            val featuresObj = JSONObject(raw).optJSONObject("features") ?: return null
            val next = mutableMapOf<String, ResolvedFeature>()
            for (key in featuresObj.keys()) {
                val entry = featuresObj.optJSONObject(key) ?: continue
                parseFeature(jsonObjToMap(entry))?.let { next[key] = it }
            }
            next
        } catch (_: Exception) { null /* corrupt cache → start empty; network fills it in */ }
    }

    /** Persist the current cache for [userId]. Call INSIDE [lock] (cache stable). */
    private fun writeDiskCache(userId: String?) {
        val p = prefs ?: return
        try {
            val featuresObj = JSONObject()
            cache.forEach { (k, v) -> featuresObj.put(k, JSONObject(featureToWire(v))) }
            p.edit().putString(
                cacheKey(userId),
                JSONObject().put("config_version", configVersion).put("features", featuresObj).toString()
            ).apply()
        } catch (_: Exception) {}
    }

    /** Wire form for one feature — status.name.lowercase() round-trips via parseStatus
     *  ("coming_soon"/"new"/…); upsell fields ride along so the label survives restart. */
    private fun featureToWire(rf: ResolvedFeature): Map<String, Any?> = buildMap {
        put("status", rf.status.name.lowercase())
        rf.reason?.let { put("reason", it) }
        rf.requiredPlan?.let { put("required_plan", it) }
        rf.requiredPlanLabel?.let { put("required_plan_label", it) }
        if (rf.upgradeCta) put("upgrade_cta", true)
    }

    private fun jsonObjToMap(o: JSONObject): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>()
        for (k in o.keys()) m[k] = o.get(k)
        return m
    }
}

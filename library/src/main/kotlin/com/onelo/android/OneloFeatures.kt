package com.onelo.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import com.onelo.android.internal.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Last-known-good cache on disk (plain prefs — flags aren't secret) so a cold
    // start renders instantly instead of flashing "everything hidden" until the
    // first resolve lands. Null in tests / when no context → in-memory only.
    private val prefs: SharedPreferences? =
        context?.applicationContext?.getSharedPreferences("onelo_features_cache", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    // @Volatile so feature() reading `cache` off the UI thread has a happens-before
    // with the mutex-guarded writer on Dispatchers.IO — without it the reader can
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
    @Volatile private var userIdHash: String? = null

    private var pollJob: Job? = null
    private var pingJob: Job? = null
    @Volatile private var securityHeaders: Map<String, String> = emptyMap()
    @Volatile private var anonymousWarningLogged: Boolean = false

    @Volatile private var isReadyFlag: Boolean = false
    private val readyDeferred = CompletableDeferred<Unit>()
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

    /** Builds the resolve POST body, adding `environment`/`userId`/`userIdHash` only when set. */
    internal fun resolveBody(userId: String?, userIdHash: String? = null): Map<String, Any?> {
        val body = mutableMapOf<String, Any?>("publishableKey" to config.publishableKey)
        userId?.let { body["userId"] = it }
        userIdHash?.let { body["userIdHash"] = it }
        featureEnvironment?.let { body["environment"] = it }
        return body
    }

    /** Builds the batch-ping POST body. */
    internal fun batchPingBody(names: List<String>, userId: String? = null, userIdHash: String? = null): Map<String, Any?> {
        val body = mutableMapOf<String, Any?>(
            "publishableKey" to config.publishableKey,
            "features" to names
        )
        userId?.let { body["userId"] = it }
        userIdHash?.let { body["userIdHash"] = it }
        featureEnvironment?.let { body["environment"] = it }
        return body
    }

    /** Builds the poll query string. Sends `since_version` (the backend cursor
     *  param — the old `version` was ignored, so the up_to_date fast-path was dead). */
    internal fun pollParams(userId: String?, version: Int, userIdHash: String? = null): String = buildString {
        append("key=${config.publishableKey}&since_version=$version")
        userId?.let { append("&userId=$it") }
        userIdHash?.let { append("&userIdHash=$it") }
        featureEnvironment?.let { append("&environment=$it") }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 60_000L
        private const val PING_DEBOUNCE_MS = 1_000L
        private const val REFRESH_DEBOUNCE_MS = 1_000L
        private const val LOG_TAG = "Onelo"
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** True once the first resolve (or SSE snapshot) has completed. */
    val isReady: Boolean get() = isReadyFlag

    /** Awaits the first resolve (or [timeoutMs]) so the UI renders without a
     *  cold-start "everything hidden" flash. Never throws. Mirrors Swift `ready`. */
    suspend fun ready(timeoutMs: Long = 1500) {
        if (isReadyFlag) return
        withTimeoutOrNull(timeoutMs) { readyDeferred.await() }
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
        cache = emptyMap()
        configVersion = 0
    }

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
     * code only to entitled users. Requires an identified user (throws
     * [OneloError] NOT_AUTHENTICATED otherwise); a backend 403 → NOT_ENTITLED.
     * Mirrors Swift `moduleToken(for:)`.
     */
    suspend fun moduleToken(slug: String): String {
        val uid = userId ?: throw OneloError.notAuthenticated()
        val body = mutableMapOf<String, Any?>(
            "publishableKey" to config.publishableKey,
            "userId" to uid,
            "slug" to slug,
        )
        userIdHash?.let { body["userIdHash"] = it }
        val resp = try {
            HttpClient.post("${config.apiUrl}/api/sdk/features/module-token", body, securityHeaders)
        } catch (e: OneloError) {
            throw e
        } catch (e: Exception) {
            throw OneloError.network("module-token request failed: ${e.message}")
        }
        return when (resp.status) {
            200 -> (resp.body["token"] as? String)?.takeIf { it.isNotEmpty() }
                ?: throw OneloError.network("module-token response missing token")
            // 401 carries a `detail.error` distinguishing secure-mode failures from a
            // plain missing session (1:1 with Swift OneloFeatures.swift:280-291).
            401 -> {
                @Suppress("UNCHECKED_CAST")
                val errCode = (resp.body["detail"] as? Map<String, Any?>)?.get("error") as? String
                when (errCode) {
                    "secure_mode_required" -> throw OneloError.secureModeRequired()
                    "invalid_user_hash"    -> throw OneloError.invalidUserHash()
                    else                   -> throw OneloError.notAuthenticated()
                }
            }
            403 -> throw OneloError.notEntitled()
            else -> throw OneloError.network("module-token failed: HTTP ${resp.status}")
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    internal suspend fun load(userId: String?, securityHeaders: Map<String, String> = emptyMap(), userIdHash: String? = null) {
        this.userId = userId
        this.userIdHash = userIdHash
        this.securityHeaders = securityHeaders
        stopPolling()
        loadDiskCache(userId) // instant last-known-good render before the network lands
        // Discovery ping on load is gated: always in test env (reliable dev
        // discovery), else a 1% sample on live — always-pinging thousands of prod
        // launches would DDoS the registry. Mirrors Swift/Flutter.
        if (shouldBatchPingOnLoad()) batchPing()
        resolve()
        startPolling()
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
     *    the cursor without touching the cache. Signals ready() either way.
     */
    internal fun applySseSnapshot(data: Map<String, Any?>) {
        scope.launch {
            mutex.withLock {
                (data["config_version"] as? Number)?.let { configVersion = it.toInt() }
                @Suppress("UNCHECKED_CAST")
                (data["features"] as? Map<String, Any?>)?.let { features ->
                    cache = features.mapNotNull { (k, v) -> parseFeature(v)?.let { k to it } }.toMap()
                    _updates.update { it + 1 }
                    writeDiskCache(userId)
                }
            }
            signalReady()
        }
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

    private suspend fun batchPing() {
        val names = discovered.toList()
        if (names.isEmpty()) return
        try {
            HttpClient.post(
                "${config.apiUrl}/api/sdk/features/batch-ping",
                batchPingBody(names, userId, userIdHash),
                securityHeaders
            )
        } catch (e: Exception) {
            // No silent swallow (project rule + parity w/ Swift's hint). A rejected
            // batch-ping usually means this instance isn't authorized under Features →
            // Registry → Deploy access.
            Log.w(LOG_TAG, "features batch-ping rejected — check Features → Deploy access: ${e.message}")
        }
    }

    private suspend fun resolve() {
        try {
            val response = HttpClient.post("${config.apiUrl}/api/sdk/features/resolve", resolveBody(userId, userIdHash), securityHeaders)
            if (response.status !in 200..299) { signalReady(); return }
            mutex.withLock {
                @Suppress("UNCHECKED_CAST")
                val features = response.body["features"] as? Map<String, Any?> ?: run { signalReady(); return }
                cache = features.mapNotNull { (k, v) -> parseFeature(v)?.let { k to it } }.toMap()
                _updates.update { it + 1 }
                (response.body["config_version"] as? Number)?.let { configVersion = it.toInt() }
                writeDiskCache(userId)
            }
            maybeWarnAnonymous(response.body)
            signalReady()
        } catch (_: Exception) {
            signalReady() // don't block a ready() waiter forever on an offline start
        }
    }

    private fun signalReady() {
        if (isReadyFlag) return
        isReadyFlag = true
        if (!readyDeferred.isCompleted) readyDeferred.complete(Unit)
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

    private suspend fun poll() {
        try {
            val params = pollParams(userId, configVersion, userIdHash)
            val response = HttpClient.get("${config.apiUrl}/api/sdk/features/poll?$params", securityHeaders)
            if (response.status != 200) return
            val data = response.body
            mutex.withLock {
                (data["config_version"] as? Number)?.let { configVersion = it.toInt() }
                // Backend returns `up_to_date` (not `changed`) — no `features` payload
                // when our since_version matches; just keep the cursor moving.
                if (data["up_to_date"] == true) return@withLock
                @Suppress("UNCHECKED_CAST")
                (data["features"] as? Map<String, Any?>)?.let { features ->
                    cache = features.mapNotNull { (k, v) -> parseFeature(v)?.let { k to it } }.toMap()
                    _updates.update { it + 1 }
                    writeDiskCache(userId)
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

    /** Populate the in-memory cache from disk (instant cold-start render). No-op
     *  when there's no context / no stored entry / a corrupt entry. */
    private suspend fun loadDiskCache(userId: String?) {
        val raw = prefs?.getString(cacheKey(userId), null) ?: return
        try {
            val root = JSONObject(raw)
            val featuresObj = root.optJSONObject("features") ?: return
            val next = mutableMapOf<String, ResolvedFeature>()
            for (key in featuresObj.keys()) {
                val entry = featuresObj.optJSONObject(key) ?: continue
                parseFeature(jsonObjToMap(entry))?.let { next[key] = it }
            }
            mutex.withLock {
                configVersion = root.optInt("config_version", configVersion)
                cache = next
                _updates.update { it + 1 }
            }
        } catch (_: Exception) { /* corrupt cache → start empty; network fills it in */ }
    }

    /** Persist the current cache for [userId]. Call INSIDE the mutex (cache stable). */
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

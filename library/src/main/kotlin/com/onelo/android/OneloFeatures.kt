package com.onelo.android

import android.util.Log
import com.onelo.android.internal.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class FeatureStatus {
    ENABLED, DISABLED, GREYED, HIDDEN, UPSELL, NEW, BETA, COMING_SOON
}

class OneloFeatureHandle(val status: FeatureStatus) {
    fun isEnabled(): Boolean = status == FeatureStatus.ENABLED || status == FeatureStatus.NEW || status == FeatureStatus.BETA
    fun isVisible(): Boolean = status != FeatureStatus.HIDDEN
    fun isGreyed(): Boolean = status == FeatureStatus.GREYED
    fun isUpsell(): Boolean = status == FeatureStatus.UPSELL
    fun isDisabled(): Boolean = status == FeatureStatus.DISABLED
    fun isNew(): Boolean = status == FeatureStatus.NEW
    fun isBeta(): Boolean = status == FeatureStatus.BETA
    fun isComingSoon(): Boolean = status == FeatureStatus.COMING_SOON
    val badgeLabel: String? get() = when (status) {
        FeatureStatus.NEW         -> "New"
        FeatureStatus.BETA        -> "Beta"
        FeatureStatus.COMING_SOON -> "Coming Soon"
        else                      -> null
    }
}

class OneloFeatures internal constructor(private val config: OneloConfig, private val monitor: OneloMonitor? = null) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private var cache: Map<String, FeatureStatus> = emptyMap()
    private var configVersion: Int = 0
    private val discovered: MutableSet<String> = mutableSetOf()
    @Volatile var userId: String? = null

    private var pollJob: Job? = null
    private var pingJob: Job? = null
    @Volatile private var securityHeaders: Map<String, String> = emptyMap()
    @Volatile private var anonymousWarningLogged: Boolean = false

    /** Normalized Features environment ("test"/"live") or null when unset. */
    internal val featureEnvironment: String? = when (config.featureEnvironment?.trim()?.lowercase()) {
        "test" -> "test"
        "live" -> "live"
        else   -> null
    }

    /**
     * Builds the resolve POST body, adding `environment` only when explicitly set.
     * Extracted for unit testing the present/absent contract.
     */
    internal fun resolveBody(userId: String?): Map<String, Any?> {
        val body = mutableMapOf<String, Any?>("publishableKey" to config.publishableKey)
        userId?.let { body["userId"] = it }
        featureEnvironment?.let { body["environment"] = it }
        return body
    }

    /**
     * Builds the batch-ping POST body, adding `environment` only when explicitly set.
     * Extracted for unit testing the present/absent contract.
     */
    internal fun batchPingBody(names: List<String>): Map<String, Any?> {
        val body = mutableMapOf<String, Any?>(
            "publishableKey" to config.publishableKey,
            "features" to names
        )
        featureEnvironment?.let { body["environment"] = it }
        return body
    }

    /**
     * Builds the poll query string, adding `&environment=` only when explicitly set.
     * Extracted for unit testing the present/absent contract.
     */
    internal fun pollParams(userId: String?, version: Int): String = buildString {
        append("key=${config.publishableKey}&version=$version")
        userId?.let { append("&userId=$it") }
        featureEnvironment?.let { append("&environment=$it") }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 60_000L
        private const val PING_DEBOUNCE_MS = 1_000L
        private const val LOG_TAG = "Onelo"
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun declare(names: List<String>) {
        discovered.addAll(names)
        scheduleBatchPing()
    }

    fun feature(name: String): OneloFeatureHandle {
        val isNew = discovered.add(name)
        if (isNew) scheduleBatchPing()
        monitor?.trackFeatureCall(name)
        return OneloFeatureHandle(cache[name] ?: FeatureStatus.HIDDEN)
    }

    fun getActiveFeatures(): List<String> =
        cache.entries
            .filter { (_, status) -> status == FeatureStatus.ENABLED || status == FeatureStatus.NEW || status == FeatureStatus.BETA }
            .map { it.key }

    fun invalidateCache() {
        cache = emptyMap()
        configVersion = 0
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    internal suspend fun load(userId: String?, securityHeaders: Map<String, String> = emptyMap()) {
        this.userId = userId
        this.securityHeaders = securityHeaders
        stopPolling()
        batchPing()
        resolve()
        startPolling()
    }

    internal fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        pingJob?.cancel()
        pingJob = null
    }

    // ── Private ───────────────────────────────────────────────────────────────

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
                batchPingBody(names),
                securityHeaders
            )
        } catch (_: Exception) {}
    }

    private suspend fun resolve() {
        try {
            val response = HttpClient.post("${config.apiUrl}/api/sdk/features/resolve", resolveBody(userId), securityHeaders)
            if (response.status !in 200..299) return
            mutex.withLock {
                @Suppress("UNCHECKED_CAST")
                val features = response.body["features"] as? Map<String, Any?> ?: return
                cache = features.mapValues { (_, v) ->
                    val s = ((v as? Map<String, Any?>)?.get("status") as? String) ?: ""
                    parseStatus(s)
                }
                (response.body["config_version"] as? Number)?.let { configVersion = it.toInt() }
            }
            maybeWarnAnonymous(response.body)
        } catch (_: Exception) {}
    }

    /**
     * Logs a one-time warning when the backend reports anonymous mode (no userId)
     * AND at least one targeted feature was hidden purely because of it. Helps
     * developers using their own auth system catch missing identify() calls.
     * Suppressed via [OneloConfig.suppressIdentifyWarning].
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
            val params = pollParams(userId, configVersion)
            val response = HttpClient.get("${config.apiUrl}/api/sdk/features/poll?$params", securityHeaders)
            if (response.status != 200) return
            val data = response.body
            if (data["changed"] == false) return
            mutex.withLock {
                @Suppress("UNCHECKED_CAST")
                val features = data["features"] as? Map<String, Any?>
                if (features != null) {
                    cache = features.mapValues { (_, v) ->
                        val s = ((v as? Map<String, Any?>)?.get("status") as? String) ?: ""
                        parseStatus(s)
                    }
                }
                (data["config_version"] as? Number)?.let { configVersion = it.toInt() }
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

    private fun parseStatus(s: String): FeatureStatus = when (s.lowercase()) {
        "enabled"     -> FeatureStatus.ENABLED
        "disabled"    -> FeatureStatus.DISABLED
        "greyed"      -> FeatureStatus.GREYED
        "hidden"      -> FeatureStatus.HIDDEN
        "upsell"      -> FeatureStatus.UPSELL
        "new"         -> FeatureStatus.NEW
        "beta"        -> FeatureStatus.BETA
        "coming_soon" -> FeatureStatus.COMING_SOON
        else          -> FeatureStatus.HIDDEN
    }
}

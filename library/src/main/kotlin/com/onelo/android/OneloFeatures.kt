package com.onelo.android

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

class OneloFeatures internal constructor(private val config: OneloConfig) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private var cache: Map<String, FeatureStatus> = emptyMap()
    private var configVersion: Int = 0
    private val discovered: MutableSet<String> = mutableSetOf()
    var userId: String? = null

    private var pollJob: Job? = null
    private var pingJob: Job? = null

    companion object {
        private const val POLL_INTERVAL_MS = 60_000L
        private const val PING_DEBOUNCE_MS = 1_000L
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun declare(names: List<String>) {
        discovered.addAll(names)
        scheduleBatchPing()
    }

    fun feature(name: String): OneloFeatureHandle {
        val isNew = discovered.add(name)
        if (isNew) scheduleBatchPing()
        return OneloFeatureHandle(cache[name] ?: FeatureStatus.HIDDEN)
    }

    fun invalidateCache() {
        cache = emptyMap()
        configVersion = 0
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    internal suspend fun load(userId: String?) {
        this.userId = userId
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
                mapOf("publishableKey" to config.publishableKey, "features" to names)
            )
        } catch (_: Exception) {}
    }

    private suspend fun resolve() {
        try {
            val body = mutableMapOf<String, Any?>("publishableKey" to config.publishableKey)
            userId?.let { body["userId"] = it }
            val response = HttpClient.post("${config.apiUrl}/api/sdk/features/resolve", body)
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
        } catch (_: Exception) {}
    }

    private suspend fun poll() {
        try {
            val params = buildString {
                append("key=${config.publishableKey}&version=$configVersion")
                userId?.let { append("&userId=$it") }
            }
            val response = HttpClient.get("${config.apiUrl}/api/sdk/features/poll?$params")
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

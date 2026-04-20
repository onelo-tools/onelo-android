package com.onelo.android

import com.onelo.android.internal.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

enum class FeatureStatus {
    ENABLED, DISABLED, GREYED, HIDDEN, UPSELL, NEW, BETA, COMING_SOON, UNKNOWN
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
    private val mutex = Mutex()
    private var cache: Map<String, FeatureStatus> = emptyMap()
    private var cacheExpiry: Long = 0L
    var userId: String? = null

    suspend fun resolve() {
        mutex.withLock {
            val now = System.currentTimeMillis()
            if (cache.isNotEmpty() && now < cacheExpiry) return
            val body = mutableMapOf<String, Any?>("publishableKey" to config.publishableKey)
            userId?.let { body["userId"] = it }
            val response = HttpClient.post("${config.apiUrl}/api/sdk/features/resolve", body)
            if (response.status !in 200..299) return
            val raw = response.body
            @Suppress("UNCHECKED_CAST")
            val features = raw["features"] as? Map<String, Any?> ?: return
            val ttl = (raw["ttl"] as? Number)?.toLong() ?: 300L
            cache = features.mapValues { (_, v) ->
                val statusStr = ((v as? Map<String, Any?>)?.get("status") as? String) ?: ""
                parseStatus(statusStr)
            }
            cacheExpiry = System.currentTimeMillis() + (ttl * 1000L)
        }
    }

    fun feature(name: String): OneloFeatureHandle = OneloFeatureHandle(cache[name] ?: FeatureStatus.UNKNOWN)

    fun invalidateCache() {
        cache = emptyMap()
        cacheExpiry = 0L
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
        else          -> FeatureStatus.UNKNOWN
    }
}

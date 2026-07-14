package com.onelo.android

import android.content.Context
import android.os.Build
import com.onelo.android.internal.MonitorScrubber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Breadcrumb category — the `.name.lowercase()` is sent verbatim as `category`. */
enum class MonitorBreadcrumbCategory { NAVIGATION, HTTP, FEATURE, LIFECYCLE, USER, INFO, ERROR }

/**
 * A single breadcrumb — a lightweight trail entry attached to error/crash payloads
 * so a failure carries the recent user/app activity that led to it.
 */
class MonitorBreadcrumb(
    val category: MonitorBreadcrumbCategory,
    val message: String,
    /** Unix seconds (fractional). */
    val timestamp: Double = System.currentTimeMillis() / 1000.0,
    val data: Map<String, String>? = null,
) {
    internal fun toMap(): Map<String, Any?> = buildMap {
        put("category", category.name.lowercase())
        put("message", message)
        put("ts", timestamp)
        if (data != null) put("data", data)
    }
}

/**
 * Error / performance monitoring — Swift/Flutter-parity implementation.
 *
 * Buffers events in memory (max [maxBufferSize], oldest dropped on overflow) and
 * ships them in a single HTTP batch every 15 s; error events flush immediately.
 * Failed batches are dropped — NOT retried or persisted in this version. On the
 * error path it auto-attaches the stack, error type, recent breadcrumbs, active
 * feature flags and device context. Sensitive values are redacted on-device
 * before send ([MonitorScrubber]). Install [registerGlobalHandlers] to capture
 * uncaught exceptions as `global_error` events.
 */
class OneloMonitor internal constructor(
    private val config: OneloConfig,
    context: Context? = null,
    // X-Bundle-Id (+ X-Integrity-Token + instance id) provider. The backend security
    // gate 403s a LIVE app with registered bundle ids on /monitor/events/batch without
    // it. Defaults to none for standalone/test construction. Wired from Onelo.
    private val securityHeaders: () -> Map<String, String> = { emptyMap() },
) {
    private val appContext: Context? = context?.applicationContext
    private val sessionId: String = UUID.randomUUID().toString()
    private val platform = "android"

    private val maxBufferSize = 200
    private val breadcrumbCapacity = 100
    private val flagCapacity = 100
    // Payload clamps — the backend rejects the WHOLE batch (422) when an event's
    // error > 8 KB. Keep each event under that so one fat error can't drop the batch.
    private val maxErrorLen = 8_000
    private val maxStackLen = 6_000
    private val maxBreadcrumbsInPayload = 40
    private val maxFlagsInPayload = 40

    private val buffer = mutableListOf<Map<String, Any?>>()
    private val summaryBuffer = mutableMapOf<String, Int>()
    private val breadcrumbs = ArrayDeque<MonitorBreadcrumb>()
    private val flagBuffer = LinkedHashMap<String, String>()
    @Volatile private var currentUserId: String? = null
    @Volatile private var globalHandlersInstalled = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var flushTimer: Job? = null

    // Lazily-resolved, cached enrichment sources (best-effort — never crash the app).
    private val appInfo: Map<String, Any?>? by lazy { loadAppInfo() }

    init {
        flushTimer = scope.launch {
            while (isActive) {
                delay(15_000)
                flush()
            }
        }
    }

    // ── Identity ────────────────────────────────────────────────────────────────

    fun setUserId(userId: String?) {
        currentUserId = userId
    }

    // ── Events ──────────────────────────────────────────────────────────────────

    /** Manual event. Use for things with no duration (a click, a tab switch). */
    fun event(featureName: String, ok: Boolean, durationMs: Long? = null, error: String? = null, meta: Map<String, Any>? = null) {
        push(featureName, ok, durationMs, error, meta, "event")
    }

    /** Times [block], recording an ok/error event with its duration. Rethrows. */
    suspend fun <T> track(featureName: String, meta: Map<String, Any>? = null, block: suspend () -> T): T {
        val start = System.currentTimeMillis()
        return try {
            val result = block()
            push(featureName, true, System.currentTimeMillis() - start, null, meta, "track")
            result
        } catch (e: Throwable) {
            push(featureName, false, System.currentTimeMillis() - start, e.message, meta, "track", e)
            throw e
        }
    }

    /** Manually capture a caught exception with its type + stack (parity w/ Swift `capture`). */
    fun capture(error: Throwable, featureName: String = "manual", meta: Map<String, Any>? = null) {
        push(featureName, false, null, error.message, meta, "event", error)
    }

    internal fun trackFeatureCall(featureName: String) {
        synchronized(summaryBuffer) {
            summaryBuffer[featureName] = (summaryBuffer[featureName] ?: 0) + 1
        }
    }

    // ── Breadcrumbs ─────────────────────────────────────────────────────────────

    fun breadcrumb(crumb: MonitorBreadcrumb) {
        synchronized(breadcrumbs) {
            if (breadcrumbs.size >= breadcrumbCapacity) breadcrumbs.removeFirst()
            breadcrumbs.addLast(crumb)
        }
    }

    fun breadcrumbInfo(message: String) =
        breadcrumb(MonitorBreadcrumb(MonitorBreadcrumbCategory.INFO, MonitorScrubber.scrubText(message) ?: message))

    fun breadcrumbNavigation(view: String) =
        breadcrumb(MonitorBreadcrumb(MonitorBreadcrumbCategory.NAVIGATION, view))

    fun breadcrumbHttp(method: String, url: String, statusCode: Int? = null) =
        breadcrumb(MonitorBreadcrumb(
            MonitorBreadcrumbCategory.HTTP,
            "$method ${MonitorScrubber.scrubText(url) ?: url}",
            data = statusCode?.let { mapOf("status" to it.toString()) },
        ))

    // ── Feature flags (error enrichment) ─────────────────────────────────────────

    /** Record the current value of a feature flag. Kept in an LRU buffer and
     *  attached to error/crash payloads so a failure carries the flag state that
     *  produced it. Called by OneloFeatures on every evaluation (like Swift). */
    fun recordFlag(key: String, value: String) {
        synchronized(flagBuffer) {
            flagBuffer.remove(key)
            flagBuffer[key] = value
            if (flagBuffer.size > flagCapacity) {
                val oldest = flagBuffer.keys.firstOrNull()
                if (oldest != null) flagBuffer.remove(oldest)
            }
        }
    }

    // ── Global crash capture ──────────────────────────────────────────────────────

    /**
     * Installs a [Thread.setDefaultUncaughtExceptionHandler] that captures uncaught
     * exceptions as `global_error` events, does a BLOCKING flush (the process is
     * dying), then CHAINS to whatever handler was installed before — so Crashlytics
     * etc. still run. Idempotent. Called automatically at SDK init.
     */
    fun registerGlobalHandlers() {
        if (globalHandlersInstalled) return
        globalHandlersInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                push("unhandled", false, null, throwable.message, null, "global_error", throwable)
                flushBlocking(2_000)
            } catch (_: Throwable) {
                // never let our capture mask the original crash
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    // ── Buffering ──────────────────────────────────────────────────────────────────

    private fun push(
        featureName: String,
        ok: Boolean,
        durationMs: Long?,
        error: String?,
        meta: Map<String, Any>?,
        source: String,
        throwable: Throwable? = null,
    ) {
        val isErrorContext = !ok || source == "global_error"
        val scrubbedMeta = MonitorScrubber.scrubMeta(meta)
        val enriched = enrichMeta(scrubbedMeta, includeContext = isErrorContext).toMutableMap()

        if (isErrorContext) {
            throwable?.let {
                // Scrub the stack too — its first line is the exception's toString()
                // (Class: message), which can carry a secret the dev put in the message.
                val frames = clamp(MonitorScrubber.scrubText(it.stackTraceToString().trim()), maxStackLen)
                if (!frames.isNullOrEmpty()) enriched["stack"] = frames
                enriched["errorType"] = it.javaClass.name
            }
            val crumbs = encodeBreadcrumbs()
            if (crumbs.isNotEmpty()) enriched["breadcrumbs"] = crumbs
            val flags = encodeFlags()
            if (flags.isNotEmpty()) enriched["flags"] = flags
        }

        synchronized(buffer) {
            if (buffer.size >= maxBufferSize) buffer.removeAt(0)
            buffer.add(buildMap {
                put("featureName", featureName)
                put("ok", ok)
                if (durationMs != null) put("durationMs", durationMs)
                clamp(MonitorScrubber.scrubText(error), maxErrorLen)?.let { put("error", it) }
                put("source", source)
                put("platform", platform)
                currentUserId?.let { put("userId", it) }
                put("sessionId", sessionId)
                put("meta", enriched)
            })
        }
        if (!ok) scope.launch { flush() }
    }

    private fun enrichMeta(meta: Map<String, Any?>?, includeContext: Boolean): Map<String, Any?> {
        val out = (meta ?: emptyMap()).toMutableMap()
        out["sdk"] = mapOf("name" to "onelo-android", "version" to SDK_VERSION)
        appInfo?.let { out["app"] = it }
        config.environment?.takeIf { it.isNotEmpty() }?.let { if (!out.containsKey("environment")) out["environment"] = it }
        val device = mutableMapOf<String, Any?>(
            "os" to "android",
            "osVersion" to Build.VERSION.RELEASE,
            "locale" to localeTag(),
            "timezone" to TimeZone.getDefault().id,
        )
        if (includeContext) device["model"] = Build.MODEL
        out["device"] = device
        return out
    }

    private fun encodeBreadcrumbs(): List<Map<String, Any?>> = synchronized(breadcrumbs) {
        val list = if (breadcrumbs.size > maxBreadcrumbsInPayload) breadcrumbs.toList().takeLast(maxBreadcrumbsInPayload) else breadcrumbs.toList()
        list.map { it.toMap() }
    }

    private fun encodeFlags(): List<Map<String, String>> = synchronized(flagBuffer) {
        val entries = flagBuffer.entries.toList()
        val recent = if (entries.size > maxFlagsInPayload) entries.takeLast(maxFlagsInPayload) else entries
        recent.map { mapOf("key" to it.key, "value" to it.value) }
    }

    private fun localeTag(): String = try { Locale.getDefault().toLanguageTag() } catch (_: Exception) { "und" }

    private fun loadAppInfo(): Map<String, Any?>? {
        val ctx = appContext ?: return null
        return try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            @Suppress("DEPRECATION")
            val build = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode.toString() else pi.versionCode.toString()
            mapOf(
                "version" to (pi.versionName ?: ""),
                "build" to build,
                "bundleId" to ctx.packageName,
            )
        } catch (_: Exception) { null }
    }

    private fun clamp(s: String?, max: Int): String? =
        if (s != null && s.length > max) s.substring(0, max) else s

    // ── Flush / lifecycle ────────────────────────────────────────────────────────

    /** Async fire-and-forget flush. */
    suspend fun flush() {
        drainSummary()
        val events = synchronized(buffer) {
            if (buffer.isEmpty()) return
            val copy = buffer.toList()
            buffer.clear()
            copy
        }
        postBatch(events)
    }

    /**
     * BLOCKING flush that waits up to [timeoutMs] for the POST to land — use before
     * a known-risky moment or a crash. Safe to call from any thread.
     */
    fun flushBlocking(timeoutMs: Long) {
        drainSummary()
        val events = synchronized(buffer) {
            if (buffer.isEmpty()) return
            val copy = buffer.toList()
            buffer.clear()
            copy
        }
        runBlocking { withTimeoutOrNull(timeoutMs) { postBatch(events) } }
    }

    private fun drainSummary() {
        val snapshot = synchronized(summaryBuffer) {
            if (summaryBuffer.isEmpty()) return
            val copy = summaryBuffer.toMap()
            summaryBuffer.clear()
            copy
        }
        synchronized(buffer) {
            snapshot.forEach { (name, count) ->
                if (count <= 0) return@forEach
                buffer.add(buildMap {
                    put("featureName", name)
                    put("ok", true)
                    put("source", "feature_call_summary")
                    put("platform", platform)
                    currentUserId?.let { put("userId", it) }
                    put("sessionId", sessionId)
                    put("meta", enrichMeta(mapOf("calls" to count), includeContext = false))
                })
            }
        }
    }

    private suspend fun postBatch(events: List<Map<String, Any?>>) = withContext(Dispatchers.IO) {
        if (events.isEmpty()) return@withContext
        try {
            val body = JSONObject().apply {
                put("publishableKey", config.publishableKey)
                put("events", JSONArray(events.map { toJsonObject(it) }))
            }
            val conn = (URL("${config.apiUrl}/api/sdk/monitor/events/batch").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Sdk-Version", SDK_VERSION)
                // X-Bundle-Id (+ X-Integrity-Token / instance id) — the backend security
                // gate 403s a live app with a registered bundle without it.
                securityHeaders().forEach { (k, v) -> setRequestProperty(k, v) }
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {
            // silently drop — monitoring must never crash the app (no retry/persist)
        }
    }

    internal fun destroy() {
        flushTimer?.cancel()
        flushTimer = null
        CoroutineScope(Dispatchers.IO).launch { flush() }
    }

    // ── JSON (recursive — org.json's Map ctor doesn't deep-convert reliably) ──────

    private fun toJsonObject(map: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, toJsonValue(v))
        return obj
    }

    private fun toJsonValue(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> {
            val o = JSONObject()
            for ((mk, mv) in v) o.put(mk.toString(), toJsonValue(mv))
            o
        }
        is List<*> -> JSONArray(v.map { toJsonValue(it) })
        else -> v
    }
}

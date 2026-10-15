package com.onelo.android

import android.content.Context
import android.os.Build
import android.util.Log
import com.onelo.android.internal.MonitorScrubber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.util.concurrent.atomic.AtomicBoolean

/** Per-thread ISO-8601 UTC formatter for an event's `ts` (see `eventTs`). */
private val eventTsFormat = ThreadLocal.withInitial {
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
}

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
 * Buffers events in memory (max [maxBufferSize], oldest dropped on overflow — every
 * drop is logged) and ships them in a single HTTP batch every 15 s; error events
 * flush immediately.
 *
 * Delivery is ONE attempt per flush (parity with `@onelo/js`): 2xx is done; 429
 * re-queues and its `Retry-After` (clamped) becomes a hold-off before the next
 * flush; 5xx / network / timeout re-queue; other 4xx are dropped with their
 * status logged (a bad key will not fix itself). The 15s flush timer IS the
 * retry, so a backend outage costs the SAME request volume as healthy
 * operation — an in-flight retry loop tripled it at the worst moment. The buffer
 * is only considered clear once the batch settles — an undelivered batch is
 * re-queued at the FRONT and the cap is re-applied, so under a sustained outage
 * NEWEST EVENTS WIN and the oldest re-queued events are trimmed first. There is
 * deliberately NO disk persistence: a batch does not survive process death.
 *
 * Every event carries a `ts` stamped when it was CREATED, so a batch that waited
 * out an outage is still recorded at the time it happened.
 *
 * On the error path it auto-attaches the stack, error type, recent breadcrumbs, active
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
    // Test seam: replaces the HTTP leg of one delivery attempt (NOT the policy —
    // classification, retry, hold-off and re-queue all still run for real). `null`
    // (the only value production ever uses) means the real HttpURLConnection POST.
    private val httpOverride: (suspend (String) -> RawResponse?)? = null,
) {
    /**
     * What ONE delivery attempt means for the batch.
     *
     * - [DONE]    — the server ACCEPTED it. Settled; never send again.
     * - [REQUEUE] — the server did NOT take it (429, 5xx, network, timeout). Put
     *               it back and let the next flush carry it.
     * - [DROP]    — permanently rejected (4xx other than 429: bad key, malformed
     *               payload). Re-sending would fail identically forever, so we
     *               discard it — but LOUDLY, never silently.
     */
    internal enum class SendOutcome { DONE, REQUEUE, DROP }

    /** What the server said. A `null` RawResponse means "no response at all"
     *  (network / DNS / TLS failure), which is retryable. */
    internal class RawResponse(val status: Int, val retryAfter: String? = null)

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

    // Delivery policy — 1:1 with @onelo/js: ONE attempt per flush. A batch the
    // server did not accept goes back in the buffer and rides the next 15s tick —
    // the flush timer IS the retry, so a backend outage never multiplies inbound
    // request volume. It also makes the crash path's budget REAL: with a retry
    // loop, `flushBlocking` could wedge the calling thread (often main, from the
    // uncaught-exception handler) for ~30s, because `HttpURLConnection`'s blocking
    // socket read is not a cancellation point and `withTimeoutOrNull` therefore
    // cannot cut it short. One attempt bounded by the socket timeouts below can.
    private val maxRetryAfterMs = 3_600_000L
    // A crash-path flush has ~2s of budget in total (see registerGlobalHandlers),
    // so 15s socket timeouts were pure fiction: the connection alone could outlast
    // the whole flush. These are what a dying process can actually afford, and
    // they are what makes [flushBlocking]'s deadline enforceable rather than
    // advisory — `withTimeoutOrNull` cannot interrupt the blocking read, so the
    // read timeout is the real ceiling.
    private val connectTimeoutMs = 3_000
    private val readTimeoutMs = 3_000
    /** Upper bound on the final flush in [destroy] so teardown can never be delayed. */
    private val destroyFlushTimeoutMs = 3_000L

    private val buffer = mutableListOf<Map<String, Any?>>()
    /** feature name → (call count, ISO-8601 UTC time of the FIRST call in this
     *  window). The stamp travels with the summary so it reports when the calls
     *  happened, not when the drain that shipped them ran. */
    private val summaryBuffer = mutableMapOf<String, Pair<Int, String>>()
    private val breadcrumbs = ArrayDeque<MonitorBreadcrumb>()
    private val flagBuffer = LinkedHashMap<String, String>()
    @Volatile private var currentUserId: String? = null
    @Volatile private var globalHandlersInstalled = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var flushTimer: Job? = null

    /**
     * Serialises every drain. A retried send now spans seconds, so the 15 s timer,
     * an error auto-flush and an explicit [flush] must never drain concurrently —
     * two overlapping drains would interleave batches and the second would observe
     * an already-emptied buffer and report success while the first is still in flight.
     */
    private val flushMutex = Mutex()
    /** Epoch ms until which sending is held off (set from a 429 `Retry-After`). */
    @Volatile private var retryAfterUntil = 0L
    /** At most ONE pending error auto-flush coroutine, so a burst of errors during
     *  an outage cannot pile up thousands of coroutines waiting on [flushMutex]. */
    private val errorFlushPending = AtomicBoolean(false)
    /** Cumulative count of events dropped because the buffer was full. */
    @Volatile private var droppedCount = 0L

    // Lazily-resolved, cached enrichment sources (best-effort — never crash the app).
    private val appInfo: Map<String, Any?>? by lazy { loadAppInfo() }

    init {
        flushTimer = scope.launch {
            while (isActive) {
                delay(15_000)
                flush()
            }
        }
        // Auto-emit one unconditional event per construction. An app that gates
        // every instrumented operation behind sign-in/paywall/consent would
        // otherwise emit NOTHING on a cold start — the dashboard would look
        // "not integrated" even though every line of the snippet was followed
        // correctly. This event needs no gate to exist: it fires the moment the
        // SDK is constructed, so Feature Health always has at least one row.
        // Goes through the same `push` pathway as the public `event()` method,
        // so it gets identical enrichment/buffering (source "event" — parity
        // with @onelo/js's `session_opened`).
        push("session_opened", true, null, null, null, "event")
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
        val now = eventTs()
        synchronized(summaryBuffer) {
            val existing = summaryBuffer[featureName]
            summaryBuffer[featureName] =
                if (existing == null) 1 to now else (existing.first + 1) to existing.second
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
                // meta.origin: whose error this is (#548) — see errorOrigin().
                push("unhandled", false, null, throwable.message, mapOf("origin" to errorOrigin(throwable)), "global_error", throwable)
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

        // Stamp the event time HERE — when it HAPPENED, not when a later flush
        // manages to ship it.
        val ts = eventTs()

        var dropped = 0
        synchronized(buffer) {
            // Newest-wins: the oldest event makes way. Bounded dropping is fine;
            // silent dropping is not — during an incident the events we throw away
            // are often the earliest, root-cause ones.
            while (buffer.size >= maxBufferSize) { buffer.removeAt(0); dropped++ }
            buffer.add(buildMap {
                put("ts", ts)
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
        if (dropped > 0) reportDropped(dropped)
        if (!ok && errorFlushPending.compareAndSet(false, true)) {
            scope.launch {
                try { flush() } finally { errorFlushPending.set(false) }
            }
        }
    }

    /** Buffer-overflow reporting. Logs the newly-dropped count plus the running total. */
    private fun reportDropped(newly: Int) {
        droppedCount += newly
        warn("dropped $newly event(s) — buffer full (max $maxBufferSize); $droppedCount dropped in total")
    }

    /**
     * Warning log that also works off-device. `android.util.Log` is a stub that
     * throws in plain JVM unit tests, so fall back to stdout rather than let
     * monitoring's own diagnostics take the host down.
     */
    private fun warn(message: String) {
        try { Log.w("OneloMonitor", message) } catch (_: Throwable) { println("[onelo.monitor] $message") }
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

    /**
     * ISO-8601 UTC stamp for an event's `ts` — the moment the event HAPPENED.
     *
     * The backend clamps this to a 1h staleness window and falls back to ingest
     * time when it is absent (`_resolve_event_ts` in sdk_monitor.py), so without
     * it a batch that waited out an outage is recorded as having happened the
     * moment the outage ENDED — calm during the failure, phantom spike after.
     * Parity with onelo-python / node / php, which have always sent it.
     *
     * NOTE: unrelated to the `ts` on a BREADCRUMB (unix seconds, inside meta).
     *
     * `SimpleDateFormat`, not `java.time`: this module's minSdk is 24 and core
     * library desugaring is not enabled, so `Instant`/`DateTimeFormatter` would
     * crash on API 24–25. It is not thread-safe and this is called from any
     * thread, hence the [ThreadLocal]. The pattern is byte-identical to
     * JavaScript's `toISOString()`, which is what the other SDKs send.
     */
    private fun eventTs(): String = eventTsFormat.get()!!.format(java.util.Date())

    // ── Flush / lifecycle ────────────────────────────────────────────────────────

    /** Async flush. Serialised against every other drain via [flushMutex]. */
    suspend fun flush() {
        flushMutex.withLock { drain() }
    }

    /**
     * BLOCKING flush that waits up to [timeoutMs] for the batch to land — used from
     * the uncaught-exception handler, so it typically blocks the MAIN thread of a
     * dying process. Safe to call from any thread. Anything not delivered when the
     * deadline expires is re-queued, not lost.
     *
     * The deadline is REAL, not advisory. [withTimeoutOrNull] alone could not
     * deliver it: `HttpURLConnection`'s blocking socket read is not a cancellation
     * point, so a coroutine sitting in `realPost` keeps the calling thread parked
     * until the SOCKET gives up regardless of any coroutine deadline. With the old
     * 3-attempt loop and 15s timeouts that was up to ~30s of frozen UI on a crash.
     * So the remaining budget is passed down and becomes the connect/read timeouts
     * of the single attempt — the only mechanism that can actually stop the wait.
     */
    fun flushBlocking(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        runBlocking {
            withTimeoutOrNull(timeoutMs) {
                flushMutex.withLock {
                    // Whatever the lock wait consumed is gone; the send gets the rest.
                    drain(budgetMs = deadline - System.currentTimeMillis())
                }
            }
        }
    }

    /**
     * One drain cycle. MUST be called holding [flushMutex].
     *
     * The buffer is emptied optimistically and the batch is only truly gone once
     * delivery settles — the `finally` re-queues on failure AND on cancellation
     * (a blown [flushBlocking] deadline, a cancelled scope), so no path can lose
     * a batch by taking it out of the buffer and then walking away.
     */
    private suspend fun drain(budgetMs: Long? = null) {
        drainSummary()
        // The server told us to back off (429 / Retry-After). Leave everything
        // buffered; it goes out on the first flush after the hold-off expires.
        if (System.currentTimeMillis() < retryAfterUntil) return
        val events = synchronized(buffer) {
            if (buffer.isEmpty()) return
            val copy = buffer.toList()
            buffer.clear()
            copy
        }
        var delivered = false
        try {
            delivered = sendOnce(events, budgetMs)
        } finally {
            if (!delivered) requeue(events)
        }
    }

    /**
     * Put an undelivered batch back at the FRONT of the buffer so the next flush
     * retries it, then re-apply the cap.
     *
     * Priority policy under a sustained outage: NEWEST EVENTS WIN. The buffer is
     * trimmed from the front, so the oldest re-queued events go first. This matches
     * [push]'s eviction, keeps memory bounded at [maxBufferSize] no matter how long
     * the backend is down, and stops a stuck batch starving live telemetry.
     */
    private fun requeue(events: List<Map<String, Any?>>) {
        if (events.isEmpty()) return
        var dropped = 0
        synchronized(buffer) {
            buffer.addAll(0, events)
            while (buffer.size > maxBufferSize) { buffer.removeAt(0); dropped++ }
        }
        warn(
            "batch not delivered — ${events.size} event(s) re-queued" +
                if (dropped > 0) ", $dropped oldest dropped (buffer cap $maxBufferSize)" else ""
        )
        if (dropped > 0) droppedCount += dropped
    }

    /**
     * Deliver one batch with exactly ONE attempt. Returns true when the batch has
     * SETTLED (accepted, or rejected in a way re-sending cannot fix); false when
     * the server did not take it and the caller should re-queue — the next 15s
     * flush is the retry.
     */
    private suspend fun sendOnce(events: List<Map<String, Any?>>, budgetMs: Long? = null): Boolean {
        if (events.isEmpty()) return true
        // A body we cannot even serialise will never serialise — settle it rather
        // than re-queue a batch that would poison every future flush.
        val body = try {
            JSONObject().apply {
                put("publishableKey", config.publishableKey)
                put("events", JSONArray(events.map { toJsonObject(it) }))
            }.toString()
        } catch (e: Exception) {
            warn("dropping ${events.size} event(s) — payload could not be encoded: ${e.message}")
            return true
        }

        return when (postOnce(body, budgetMs)) {
            SendOutcome.DONE -> true
            SendOutcome.DROP -> true // already logged with its status in classify()
            // 429 / 5xx / network — the caller's `finally` re-queues the batch.
            SendOutcome.REQUEUE -> false
        }
    }

    /**
     * ONE POST. Never throws — classifies the result instead. This classification is
     * the only thing standing between a 503 and a silently destroyed batch.
     */
    private suspend fun postOnce(body: String, budgetMs: Long? = null): SendOutcome {
        val override = httpOverride
        val raw = if (override != null) {
            try { override(body) } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
        } else {
            realPost(body, budgetMs)
        }
        return classify(raw)
    }

    /** Maps a response (or its absence) onto the delivery policy. Also arms the
     *  429 hold-off. Pure apart from that one assignment — the whole point is
     *  that a 503 can never again be mistaken for a success. */
    private fun classify(raw: RawResponse?): SendOutcome = when {
        raw == null -> SendOutcome.REQUEUE // network / DNS / TLS / timeout
        raw.status in 200..299 -> SendOutcome.DONE
        raw.status == 429 -> {
            // Rate limited — hammering it now only makes it worse.
            val waitMs = parseRetryAfter(raw.retryAfter)
            if (waitMs > 0) {
                retryAfterUntil = maxOf(retryAfterUntil, System.currentTimeMillis() + waitMs)
            }
            // The server did NOT accept these events — re-queue them so they go
            // out once the hold-off expires.
            SendOutcome.REQUEUE
        }
        // 401 bad key / 403 / 422 validation — identical every time, so re-queueing
        // would wedge the buffer forever. Drop, but say so.
        raw.status in 400..499 -> {
            warn("batch rejected with HTTP ${raw.status} — dropping it (retrying would fail identically)")
            SendOutcome.DROP
        }
        // 5xx and anything unrecognised — server-side, try the next flush.
        else -> SendOutcome.REQUEUE
    }

    /** The real POST. Returns null when there was no response at all. Never throws
     *  (except on cancellation, which must propagate).
     *
     *  [budgetMs], when given, is the caller's REMAINING wall-clock allowance (the
     *  crash path). It is split across connect and read because those socket
     *  timeouts are the only thing that can actually end a blocking read — a
     *  coroutine deadline cannot. Floored so a nearly-spent budget still gets a
     *  real chance rather than failing instantly. */
    private suspend fun realPost(body: String, budgetMs: Long? = null): RawResponse? {
        val connectMs: Int
        val readMs: Int
        if (budgetMs != null) {
            val half = (budgetMs / 2).coerceIn(250L, connectTimeoutMs.toLong()).toInt()
            connectMs = half
            readMs = half
        } else {
            connectMs = connectTimeoutMs
            readMs = readTimeoutMs
        }
        return withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("${config.apiUrl}/api/sdk/monitor/events/batch").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    instanceFollowRedirects = false  // see HttpClient.get() — headers follow the redirect
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-Sdk-Version", SDK_VERSION)
                    // X-Bundle-Id (+ X-Integrity-Token / instance id) — the backend security
                    // gate 403s a live app with a registered bundle without it.
                    securityHeaders().forEach { (k, v) -> setRequestProperty(k, v) }
                    connectTimeout = connectMs
                    readTimeout = readMs
                }
                OutputStreamWriter(conn.outputStream).use { it.write(body) }
                // Reading responseCode is what actually issues the request — and,
                // unlike before, its VALUE now decides the batch's fate.
                RawResponse(conn.responseCode, conn.getHeaderField("Retry-After"))
            } catch (c: CancellationException) {
                throw c // teardown/deadline — must not be reclassified as a retry
            } catch (_: Exception) {
                null // no response at all
            } finally {
                try { conn?.disconnect() } catch (_: Exception) { /* already closed */ }
            }
        }
    }

    /** Seconds-form `Retry-After` → ms, clamped to [maxRetryAfterMs]. Never throws. */
    private fun parseRetryAfter(raw: String?): Long {
        val seconds = raw?.trim()?.toDoubleOrNull() ?: return 0L
        if (!seconds.isFinite()) return 0L
        return (seconds * 1000).toLong().coerceIn(0L, maxRetryAfterMs)
    }

    private fun drainSummary() {
        val snapshot = synchronized(summaryBuffer) {
            if (summaryBuffer.isEmpty()) return
            val copy = summaryBuffer.toMap()
            summaryBuffer.clear()
            copy
        }
        synchronized(buffer) {
            snapshot.forEach { (name, entry) ->
                val (count, firstTs) = entry
                if (count <= 0) return@forEach
                buffer.add(buildMap {
                    // When the first call of this window happened — NOT drain time.
                    put("ts", firstTs)
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

    /**
     * Teardown. Stops the timer, makes ONE bounded final attempt to ship what is
     * buffered, then cancels [scope] so no in-flight retry (or pending error
     * auto-flush) outlives this monitor. The final flush runs on a detached scope
     * so cancelling [scope] cannot kill it, and its deadline guarantees teardown
     * is never delayed by a retrying send.
     */
    internal fun destroy() {
        flushTimer?.cancel()
        flushTimer = null
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(destroyFlushTimeoutMs) { flush() }
            } finally {
                scope.cancel()
            }
        }
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

private val PLATFORM_FRAME_PREFIXES = listOf(
    "java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.", "android.", "androidx.",
    "dalvik.", "com.android.", "libcore.", "org.jetbrains.",
)

/**
 * Whose error is an uncaught exception — the app's or the Onelo SDK's (#548)?
 * Sent as `meta.origin` so the backend keeps the SDK's own errors out of the
 * developer's Monitor data. A [OneloError] is the SDK's by construction;
 * otherwise the first frame outside the platform/runtime (where the exception
 * was thrown from code someone wrote) decides: `com.onelo.android.` → SDK.
 */
internal fun errorOrigin(t: Throwable): String {
    if (t is OneloError) return "onelo_sdk"
    val owner = t.stackTrace.firstOrNull { f -> PLATFORM_FRAME_PREFIXES.none { f.className.startsWith(it) } }
    return if (owner?.className?.startsWith("com.onelo.android.") == true) "onelo_sdk" else "app"
}

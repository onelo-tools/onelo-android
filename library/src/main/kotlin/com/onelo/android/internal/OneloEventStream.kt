package com.onelo.android.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

internal typealias OneloEventHandler = (Map<String, Any?>) -> Unit

/**
 * Minimal Server-Sent-Events client for the Onelo realtime stream
 * (`GET /api/sdk/features/stream`). Auth, consent and features MULTIPLEX over this
 * ONE connection (disjoint event names, so they never clobber each other):
 *   - `session.revoked`        — remote logout (ban / deletion / refresh-reuse),
 *   - `legal.consent_required` — a blocking legal version was published,
 *   - `features_updated` / `up_to_date` / `connected` — realtime feature snapshots,
 *   - `discovery_requested`    — re-announce declared slugs.
 *
 * Mirrors the Swift/Flutter `OneloEventStream`:
 *   • bounded exponential backoff `[1,2,5,10,30]s` + full jitter on reconnect,
 *   • unknown event names are ignored (forward-compatible),
 *   • refuses to silently downgrade a user-bound stream back to anonymous,
 *   • a generation counter so a connect superseded mid-await never lingers.
 *
 * The stream authenticates via `key` + `userId` + `instance_id` query params
 * (no bearer on the stream itself); each event carries an `app_user_id` the
 * handler filters against the current user.
 */
internal class OneloEventStream(
    private val apiUrl: String,
    private val publishableKey: String,
    private val instanceId: String,
    private val sdkVersion: String,
    private val environment: String?,
    // Supplies the security headers (X-Bundle-Id + X-Integrity-Token + instance id)
    // for the stream connect. The backend `validate_sdk_request_security` gate 403s
    // a LIVE app with registered bundle ids on GET /features/stream without
    // X-Bundle-Id (Android is also an integrity platform → X-Integrity-Token) — the
    // stream would then reconnect-loop forever and realtime (features_updated /
    // session.revoked / legal.consent_required) would be silently DEAD. Called fresh
    // per connect so a token fetched after the first connect rides later reconnects.
    private val securityHeaders: () -> Map<String, String> = { emptyMap() },
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handlers = mutableMapOf<String, OneloEventHandler>()

    @Volatile private var userId: String? = null
    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var attempt = 0
    private var connectJob: Job? = null
    // The live HTTP connection. Disconnected on supersede/stop so a blocking
    // readLine() unblocks immediately (coroutine cancel alone can't interrupt it).
    @Volatile private var activeConn: HttpURLConnection? = null

    private val backoffSeconds = intArrayOf(1, 2, 5, 10, 30)
    // Larger than the backend keep-alive so a live connection never times out, but
    // a ZOMBIE (server gone without closing TCP) trips SocketTimeout → reconnect.
    private val readTimeoutMs = 60_000

    /** Register a handler for a named SSE event (e.g. `session.revoked`). */
    fun on(event: String, handler: OneloEventHandler) {
        handlers[event] = handler
    }

    /**
     * Open (or re-target) the stream for [userId]. No-op if already connected for
     * the same user. Refuses an empty userId (won't downgrade a user-bound stream
     * to anonymous — matches Swift/Flutter).
     */
    @Synchronized
    fun start(userId: String) {
        if (userId.isEmpty()) return
        if (running && this.userId == userId) return
        this.userId = userId
        running = true
        open()
    }

    /**
     * Ensure the stream is connected — used by the features module so realtime
     * works even without Onelo Auth (an anonymous connection). Never downgrades a
     * live user-bound stream to anonymous.
     */
    @Synchronized
    fun ensureStarted(userId: String?) {
        val uid = userId?.takeIf { it.isNotEmpty() }
        if (running) {
            if (uid != null && uid != this.userId) {
                this.userId = uid
                open()
            }
            return
        }
        this.userId = uid
        running = true
        open()
    }

    /** Stop the stream and invalidate any in-flight connect. */
    @Synchronized
    fun stop() {
        generation++
        running = false
        userId = null
        connectJob?.cancel()
        connectJob = null
        disconnectActive()
    }

    /** (Re)open: supersede any in-flight connect (bump generation), reset backoff. */
    private fun open() {
        generation++
        attempt = 0
        connectJob?.cancel()
        disconnectActive() // unblock the old readLine so the old socket dies now
        connectJob = scope.launch { connect(generation) }
    }

    private fun disconnectActive() {
        try { activeConn?.disconnect() } catch (_: Exception) {}
        activeConn = null
    }

    private suspend fun connect(gen: Int) {
        if (!running || gen != generation) return
        var conn: HttpURLConnection? = null
        try {
            val q = buildString {
                append("key=").append(enc(publishableKey))
                userId?.let { append("&userId=").append(enc(it)) }
                append("&instance_id=").append(enc(instanceId))
                append("&sdk_platform=android")
                append("&sdk_version=").append(enc(sdkVersion))
                environment?.let { append("&environment=").append(enc(it)) }
            }
            conn = withContext(Dispatchers.IO) {
                (URL("$apiUrl/api/sdk/features/stream?$q").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "text/event-stream")
                    setRequestProperty("X-Sdk-Version", sdkVersion)
                    // X-Bundle-Id (+ X-Integrity-Token / instance id) — without it the
                    // backend security gate 403s a live app and realtime goes dead.
                    securityHeaders().forEach { (k, v) -> setRequestProperty(k, v) }
                    connectTimeout = 15_000
                    readTimeout = readTimeoutMs
                }
            }
            if (gen != generation) return
            activeConn = conn // now cancellable by disconnectActive()
            val status = withContext(Dispatchers.IO) { conn.responseCode }
            if (status != 200) { scheduleReconnect(gen); return }
            attempt = 0 // connected — reset backoff
            val reader = withContext(Dispatchers.IO) { BufferedReader(InputStreamReader(conn.inputStream)) }
            var event: String? = null
            val data = StringBuilder()
            reader.use { r ->
                while (running && gen == generation) {
                    val line = withContext(Dispatchers.IO) { r.readLine() } ?: break // stream closed
                    when {
                        line.isEmpty() -> {
                            // blank line = end of one event frame → dispatch
                            if (event != null && data.isNotEmpty()) dispatch(event!!, data.toString())
                            event = null
                            data.setLength(0)
                        }
                        line.startsWith(":") -> { /* keep-alive comment */ }
                        line.startsWith("event:") -> event = line.substring(6).trim()
                        line.startsWith("data:") -> data.append(line.substring(5).trim())
                    }
                }
            }
            scheduleReconnect(gen)
        } catch (_: CancellationException) {
            // superseded / stopped — swallow, no reconnect
        } catch (_: Exception) {
            scheduleReconnect(gen)
        } finally {
            if (activeConn === conn) activeConn = null
            withContext(NonCancellable + Dispatchers.IO) { try { conn?.disconnect() } catch (_: Exception) {} }
        }
    }

    private fun dispatch(event: String, raw: String) {
        val handler = handlers[event] ?: return // unknown event → ignore (forward-compat)
        try {
            handler(jsonToMap(JSONObject(raw)))
        } catch (_: Exception) {
            // malformed payload — drop this event, keep the stream alive
        }
    }

    private fun scheduleReconnect(gen: Int) {
        if (!running || gen != generation) return
        val base = backoffSeconds[minOf(attempt, backoffSeconds.size - 1)]
        attempt++
        // Full jitter in [0.25s, base]s so a mass-disconnect doesn't thundering-herd.
        val delayMs = 250L + (base * 1000 * Random.nextDouble()).toLong()
        scope.launch {
            delay(delayMs)
            if (running && gen == generation) connect(gen)
        }
    }

    /** Test-only: synchronously feed a raw event through the dispatch path. */
    internal fun debugEmit(event: String, data: Map<String, Any?>) =
        dispatch(event, JSONObject(data).toString())

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun jsonToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (key in obj.keys()) {
            map[key] = when (val v = obj.get(key)) {
                is JSONObject -> jsonToMap(v)
                JSONObject.NULL -> null
                else -> v
            }
        }
        return map
    }
}

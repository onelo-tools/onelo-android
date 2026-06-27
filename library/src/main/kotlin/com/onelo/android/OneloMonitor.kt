package com.onelo.android

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class OneloMonitor internal constructor(
    private val config: OneloConfig,
) {
    private val sessionId: String = UUID.randomUUID().toString()
    private val platform = "android"
    private val maxBufferSize = 200
    private val buffer = mutableListOf<Map<String, Any?>>()
    private var currentUserId: String? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch {
            while (isActive) {
                delay(15_000)
                flush()
            }
        }
    }

    fun setUserId(userId: String?) {
        currentUserId = userId
    }

    suspend fun <T> track(featureName: String, meta: Map<String, Any>? = null, block: suspend () -> T): T {
        val start = System.currentTimeMillis()
        return try {
            val result = block()
            push(featureName, true, System.currentTimeMillis() - start, null, meta, "track")
            result
        } catch (e: Exception) {
            push(featureName, false, System.currentTimeMillis() - start, e.message, meta, "track")
            throw e
        }
    }

    fun event(featureName: String, ok: Boolean, durationMs: Long? = null, error: String? = null, meta: Map<String, Any>? = null) {
        push(featureName, ok, durationMs, error, meta, "event")
    }

    internal fun trackFeatureCall(featureName: String) {
        push(featureName, true, null, null, null, "feature_call")
    }

    internal fun destroy() {
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch { flush() }
    }

    private fun push(featureName: String, ok: Boolean, durationMs: Long?, error: String?, meta: Map<String, Any>?, source: String) {
        synchronized(buffer) {
            if (buffer.size >= maxBufferSize) buffer.removeAt(0)
            buffer.add(mapOf(
                "featureName" to featureName,
                "ok" to ok,
                "durationMs" to durationMs,
                "error" to error,
                "meta" to meta,
                "source" to source,
                "userId" to currentUserId,
                "platform" to platform,
                "sessionId" to sessionId,
            ))
        }
        if (!ok || source == "global_error") {
            scope.launch { flush() }
        }
    }

    private suspend fun flush() = withContext(Dispatchers.IO) {
        val events = synchronized(buffer) {
            if (buffer.isEmpty()) return@withContext
            val copy = buffer.toList()
            buffer.clear()
            copy
        }
        try {
            val eventsArray = JSONArray()
            for (event in events) {
                val obj = JSONObject()
                for ((k, v) in event) {
                    when (v) {
                        null -> obj.put(k, JSONObject.NULL)
                        is Map<*, *> -> {
                            val nested = JSONObject()
                            @Suppress("UNCHECKED_CAST")
                            for ((mk, mv) in v as Map<String, Any?>) {
                                nested.put(mk, mv ?: JSONObject.NULL)
                            }
                            obj.put(k, nested)
                        }
                        else -> obj.put(k, v)
                    }
                }
                eventsArray.put(obj)
            }
            val body = JSONObject()
            body.put("publishableKey", config.publishableKey)
            body.put("events", eventsArray)

            val conn = (URL("${config.apiUrl}/api/sdk/monitor/events/batch").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {
        }
    }
}

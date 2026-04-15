package com.onelo.android.internal

import com.onelo.android.OneloError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

internal data class HttpResponse(val status: Int, val body: Map<String, Any?>)

internal object HttpClient {

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            readResponse(conn)
        }

    suspend fun post(url: String, body: Map<String, Any?>, headers: Map<String, String> = emptyMap()): HttpResponse =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            OutputStreamWriter(conn.outputStream).use { it.write(JSONObject(body).toString()) }
            readResponse(conn)
        }

    private fun readResponse(conn: HttpURLConnection): HttpResponse {
        return try {
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = if (stream != null) BufferedReader(InputStreamReader(stream)).use { it.readText() } else ""
            val body = parseJson(text)
            HttpResponse(status, body)
        } catch (e: Exception) {
            throw OneloError.network(e.message ?: "request failed")
        }
    }

    private fun parseJson(text: String): Map<String, Any?> = try {
        jsonObjectToMap(JSONObject(text))
    } catch (_: Exception) {
        emptyMap()
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (key in obj.keys()) {
            map[key] = when (val v = obj.get(key)) {
                is JSONObject -> jsonObjectToMap(v)
                is JSONArray -> (0 until v.length()).map { v.get(it) }
                JSONObject.NULL -> null
                else -> v
            }
        }
        return map
    }

    fun checkHostedFlowRequired(body: Map<String, Any?>) {
        val errorCode = (body["error"] as? String)
            ?: ((body["detail"] as? Map<*, *>)?.get("error") as? String)
        if (errorCode == "hosted_flow_required") {
            throw OneloError.hostedFlowRequired()
        }
    }
}

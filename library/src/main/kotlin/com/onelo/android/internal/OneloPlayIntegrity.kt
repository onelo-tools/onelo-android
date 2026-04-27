package com.onelo.android.internal

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal class OneloPlayIntegrity(
    private val context: Context,
    private val apiUrl: String,
    private val publishableKey: String,
    private val packageName: String,
) {
    companion object {
        private const val PREFS_FILE = "io.onelo.integrity_prefs"
        private const val KEY_TOKEN = "io.onelo.integrity_token"
        private const val KEY_EXPIRY_MS = "io.onelo.integrity_token_expiry_ms"
        private const val NONCE = "onelo-sdk-integrity-check-v1"
        private const val EXPIRY_BUFFER_MS = 5 * 60 * 1000L // 5 minutes
    }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    suspend fun getIntegrityToken(): String? = withContext(Dispatchers.IO) {
        val cached = prefs.getString(KEY_TOKEN, null)
        val expiryMs = prefs.getLong(KEY_EXPIRY_MS, 0L)
        if (cached != null && System.currentTimeMillis() + EXPIRY_BUFFER_MS < expiryMs) {
            return@withContext cached
        }
        fetchAndVerify()
    }

    private suspend fun fetchAndVerify(): String? = withContext(Dispatchers.IO) {
        try {
            val manager = IntegrityManagerFactory.create(context)
            val tokenResponse = manager.requestIntegrityToken(
                IntegrityTokenRequest.builder().setNonce(NONCE).build()
            ).await()
            val googleToken = tokenResponse.token()

            val url = URL("$apiUrl/api/sdk/auth/play-integrity")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            val body = JSONObject().apply {
                put("integrity_token", googleToken)
                put("package_name", packageName)
                put("publishable_key", publishableKey)
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray()) }

            if (conn.responseCode != 200) return@withContext null

            val response = conn.inputStream.readBytes().decodeToString()
            val json = JSONObject(response)
            val token = json.optString("integrity_token").takeIf { it.isNotEmpty() }
                ?: return@withContext null
            val expiresAt = json.optString("expires_at")

            // Parse ISO-8601 expiry to epoch millis for storage (minSdk 24 safe)
            val expiryMs = parseIso8601ToMillis(expiresAt) ?: (System.currentTimeMillis() + 60 * 60 * 1000L)

            prefs.edit()
                .putString(KEY_TOKEN, token)
                .putLong(KEY_EXPIRY_MS, expiryMs)
                .apply()

            token
        } catch (e: Exception) {
            null // fail open — SDK continues without integrity token
        }
    }

    private fun parseIso8601ToMillis(iso: String): Long? =
        runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(iso)?.time
        }.getOrElse {
            runCatching {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", Locale.US).parse(iso)?.time
            }.getOrNull()
        }
}

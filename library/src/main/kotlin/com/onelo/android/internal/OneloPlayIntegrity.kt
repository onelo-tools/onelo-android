package com.onelo.android.internal

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Play Integrity via Google's **Standard Integrity API** (the recommended path
 * for per-request/per-session attestation): lower latency, and — crucially —
 * Google Play automatically prevents integrity-token replay, so we don't manage
 * a nonce store.
 *
 * Flow:
 *   1. prepareIntegrityToken(cloudProjectNumber) once → a token provider that
 *      Google pre-warms on-device (rate-limited to ~5/min, so we cache it).
 *   2. request(requestHash) per exchange → a fresh integrity token, which we
 *      POST to the Onelo backend (/api/sdk/auth/play-integrity). The backend
 *      decodes it with THIS app's own service account and returns a short-lived
 *      Onelo integrity JWT, cached here.
 *
 * `cloudProjectNumber` comes from the server (`/api/sdk/config`), sourced from
 * the credential the developer uploads in the dashboard. When it is unset
 * (<= 0) the Standard API cannot run, so we fail open (return null) — the
 * backend enforces separately for live apps.
 */
internal class OneloPlayIntegrity(
    private val context: Context,
    private val apiUrl: String,
    private val publishableKey: String,
    private val packageName: String,
    private val cloudProjectNumber: Long,
) {
    companion object {
        private const val PREFS_FILE = "io.onelo.integrity_prefs"
        private const val KEY_TOKEN = "io.onelo.integrity_token"
        private const val KEY_EXPIRY_MS = "io.onelo.integrity_token_expiry_ms"
        private const val EXPIRY_BUFFER_MS = 5 * 60 * 1000L // 5 minutes
    }

    private val prefs by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            PREFS_FILE,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // The prepared provider is reused across requests for the SDK's lifetime.
    // prepareIntegrityToken() is rate-limited (~5/min), so we never re-prepare
    // per call.
    @Volatile private var tokenProvider: StandardIntegrityTokenProvider? = null

    suspend fun getIntegrityToken(): String? = withContext(Dispatchers.IO) {
        val cached = prefs.getString(KEY_TOKEN, null)
        val expiryMs = prefs.getLong(KEY_EXPIRY_MS, 0L)
        if (cached != null && System.currentTimeMillis() + EXPIRY_BUFFER_MS < expiryMs) {
            return@withContext cached
        }
        fetchAndVerify()
    }

    private suspend fun prepareProvider(): StandardIntegrityTokenProvider? {
        tokenProvider?.let { return it }
        // No configured Google Cloud project number → Standard API can't run.
        // Fail open; the backend still enforces for live apps.
        if (cloudProjectNumber <= 0L) return null
        return try {
            val manager = IntegrityManagerFactory.createStandard(context)
            val provider = manager.prepareIntegrityToken(
                PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(cloudProjectNumber)
                    .build()
            ).await()
            tokenProvider = provider
            provider
        } catch (e: Exception) {
            null // fail open — SDK continues without an integrity token
        }
    }

    private suspend fun fetchAndVerify(): String? = withContext(Dispatchers.IO) {
        try {
            val provider = prepareProvider() ?: return@withContext null

            // requestHash tamper-binds the verdict to this app's request contents.
            // Replay of the Google token itself is prevented automatically by Play.
            val requestHash = sha256Hex("$publishableKey|$packageName")
            val tokenResponse = provider.request(
                StandardIntegrityTokenRequest.builder()
                    .setRequestHash(requestHash)
                    .build()
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

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun parseIso8601ToMillis(iso: String): Long? =
        runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(iso)?.time
        }.getOrElse {
            runCatching {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", Locale.US).parse(iso)?.time
            }.getOrNull()
        }
}

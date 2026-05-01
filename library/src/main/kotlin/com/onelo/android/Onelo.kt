package com.onelo.android

import android.content.Context
import android.util.Log
import com.onelo.android.internal.HttpClient
import com.onelo.android.internal.OneloPlayIntegrity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class Onelo(config: OneloConfig, context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val monitor: OneloMonitor = OneloMonitor(config)
    val auth: OneloAuth = OneloAuth(config, context)
    val features: OneloFeatures = OneloFeatures(config, monitor)
    val feedback: OneloFeedback = OneloFeedback(config, features)
    val paywall: OneloPaywall = OneloPaywall()
    val forms: OneloForms = OneloForms(config)
    val waitlist: OneloWaitlist = OneloWaitlist(config)

    @Volatile private var attestedBundleId: String = context.packageName
    @Volatile private var integrityToken: String? = null

    fun securityHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        if (attestedBundleId.isNotEmpty()) headers["X-Bundle-Id"] = attestedBundleId
        integrityToken?.let { headers["X-Integrity-Token"] = it }
        return headers
    }

    init {
        scope.launch {
            val required = fetchAttestRequired(config)
            if (required) {
                val integrity = OneloPlayIntegrity(context, config.apiUrl, config.publishableKey, context.packageName)
                integrityToken = try { integrity.getIntegrityToken() } catch (e: Exception) { null }
            }
            features.load(null, securityHeaders())
        }
        scope.launch {
            auth.onAuthStateChange().collect { session ->
                features.load(session?.user?.id, securityHeaders())
            }
        }
    }

    suspend fun identify(userId: String) {
        features.load(userId, securityHeaders())
    }

    private suspend fun fetchAttestRequired(config: OneloConfig): Boolean {
        return try {
            val url = "${config.apiUrl}/api/sdk/config?key=${config.publishableKey}"
            val resp = HttpClient.get(url)
            resp.body["attest_required"] as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }
}

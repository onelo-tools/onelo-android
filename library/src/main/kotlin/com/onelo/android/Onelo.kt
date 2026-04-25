package com.onelo.android

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class Onelo(config: OneloConfig, context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val auth: OneloAuth = OneloAuth(config, context)
    val features: OneloFeatures = OneloFeatures(config)
    val feedback: OneloFeedback = OneloFeedback(config, features)

    init {
        Log.d("OneloBridge", "SDK initialized — features.load(null)") // TODO: remove debug
        scope.launch { features.load(null) }
        scope.launch {
            auth.onAuthStateChange().collect { session ->
                val userId = session?.user?.id
                Log.d("OneloBridge", "Auth state changed → userId: ${userId ?: "null"}") // TODO: remove debug
                Log.d("OneloBridge", "features.load(userId: ${userId ?: "null"})") // TODO: remove debug
                features.load(userId)
            }
        }
    }

    suspend fun identify(userId: String) {
        Log.d("OneloBridge", "identify(userId: $userId) → features.load") // TODO: remove debug
        features.load(userId)
    }
}

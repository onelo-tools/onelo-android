package com.onelo.android

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class Onelo(config: OneloConfig, context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val auth: OneloAuth = OneloAuth(config, context)
    val features: OneloFeatures = OneloFeatures(config)

    init {
        scope.launch { features.load(null) }
    }

    suspend fun identify(userId: String) {
        features.load(userId)
    }
}

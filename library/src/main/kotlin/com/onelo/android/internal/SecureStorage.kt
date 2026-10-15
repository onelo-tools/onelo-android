package com.onelo.android.internal

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.util.UUID

internal class SecureStorage(context: Context) : KeyValueStore {

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val prefs = EncryptedSharedPreferences.create(
        "onelo_secure_prefs",
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clear() {
        TOKEN_KEYS.forEach { remove(it) }
    }

    /**
     * Stable per-install identifier (UUID v4), persisted so it survives restarts
     * AND sign-out (deliberately NOT in [TOKEN_KEYS], so [clear] keeps it). Sent as
     * `X-Onelo-Instance-Id` — the identity the realtime SSE stream binds to and the
     * Features discovery TOFU key (without it, test-env batch-ping 403s).
     */
    fun instanceId(): String {
        prefs.getString("onelo_instance_id", null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString("onelo_instance_id", id).apply()
        return id
    }

    companion object {
        val TOKEN_KEYS = listOf(
            "onelo_access_token",
            "onelo_refresh_token",
            "onelo_expires_at",
            "onelo_user",
            // Sign-out drops the hosted-flow verifier too (#541).
            FlowPkce.VERIFIER_KEY,
            FlowPkce.ISSUED_AT_KEY,
        )
    }
}

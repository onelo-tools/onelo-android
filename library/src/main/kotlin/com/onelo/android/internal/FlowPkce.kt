package com.onelo.android.internal

/** The few storage calls [FlowPkce] needs, so it can be tested without a Context. */
internal interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

/**
 * The PKCE verifier bound to the hosted flow (`/flow/init`, `/auth/initiate`).
 * The mirror of Swift's `_beginFlowPKCE` (#541).
 *
 * The challenge goes on the flow token, which is what pairs a magic link the
 * hosted page sends: opened on another device, it shows a code screen, and the
 * sign-in completes back in this app (#540). The verifier must still be here when
 * the code comes back, which is often after Android killed the process that sat
 * behind the mail app. So it is PERSISTED (EncryptedSharedPreferences), not held
 * in memory like the rotating `/api/sdk/config` verifier.
 *
 * One verifier per 15-minute window (the magic-link TTL), reused by every flow in
 * it. `loadAuthView` re-resolves the flow on retries and expiries; minting a new
 * pair each time would overwrite the verifier an outstanding link is bound to,
 * and that link would then fail its PKCE check.
 */
internal class FlowPkce(
    private val store: KeyValueStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var verifier: String? = null

    /** The challenge for the flow being started. */
    @Synchronized
    fun begin(): String {
        val existing = pendingVerifier()
        if (existing != null && isFresh()) {
            verifier = existing
            return Pkce.generateCodeChallenge(existing)
        }
        val fresh = Pkce.generateCodeVerifier()
        verifier = fresh
        store.set(VERIFIER_KEY, fresh)
        store.set(ISSUED_AT_KEY, nowMs().toString())
        return Pkce.generateCodeChallenge(fresh)
    }

    /** In memory while the process lived, else the stored copy. */
    fun pendingVerifier(): String? = verifier ?: store.get(VERIFIER_KEY)

    /** After a successful exchange and on sign-out: a stale verifier can only
     *  mismatch the next flow's challenge. The stamp goes with it. */
    @Synchronized
    fun clear() {
        verifier = null
        store.remove(VERIFIER_KEY)
        store.remove(ISSUED_AT_KEY)
    }

    /** Fails closed: an unreadable stamp or a clock that moved backwards is stale. */
    private fun isFresh(): Boolean {
        val issued = store.get(ISSUED_AT_KEY)?.toLongOrNull() ?: return false
        val age = nowMs() - issued
        return age in 0 until LIFETIME_MS
    }

    companion object {
        const val VERIFIER_KEY = "onelo_flow_code_verifier"
        const val ISSUED_AT_KEY = "onelo_flow_code_verifier_issued_at"
        const val LIFETIME_MS = 15 * 60 * 1000L
    }
}

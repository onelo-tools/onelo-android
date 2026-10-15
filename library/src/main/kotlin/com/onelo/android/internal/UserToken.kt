package com.onelo.android.internal

import com.onelo.android.extractErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Proof of the signed-in end user on Features calls (#510, #519).
 *
 * The Features endpoints (resolve, poll, batch-ping, module-token and the Event
 * Stream) accept `Authorization: Bearer <Onelo access token>` from a
 * publishable-key caller and take the user from the token instead of the
 * `userId` the SDK names (server: `backend/app/lib/features/end_user.py`). A
 * token the server refuses answers `401 {"detail":{"error":"invalid_user_token"}}`
 * and never falls back to the `userId`.
 *
 * This file is the ONE place that decides what a Features call sends — the same
 * rules as the JS SDKs (`packages/onelo-js/src/core/user-token.ts`):
 *  - signed in: the current access token, read fresh per call and per reconnect
 *    (`OneloAuth.getSession()` refreshes one near expiry);
 *  - refused: refresh once and retry once (one refresh per refusal episode);
 *  - refused again: the call is made anonymous (no token, no `userId`) and the
 *    caller is told, so it can surface the refusal. It never loops.
 *
 * Why the refusal episode exists: a refresh saves the session, which emits on
 * `OneloAuth.onAuthStateChange()`, and `Onelo` answers every emission with
 * `features.load()` — a NEW Features call. If the server keeps refusing even
 * fresh tokens (a key whose app is not the one the user signed in to), a
 * per-call "refresh once" would refresh → load → be refused → refresh … without
 * end. So a refusal-triggered refresh happens at most once per episode:
 * concurrent refusals share the refresh in flight, and after it no further
 * refresh is made until a token is accepted again or the user changes ([reset]).
 */
internal class UserTokenSource(
    /** The current access token, or null when no Onelo user is signed in. */
    private val readCurrent: suspend () -> String?,
    /** Refresh the session and return the new access token (null when that fails). */
    private val readRefreshed: suspend () -> String?,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Mutex()
    @Volatile private var spent = false
    private var inFlight: Deferred<String?>? = null

    /** The token to send now, or null for "no token". A failing source counts as none. */
    suspend fun current(): String? = normalized(runCatching { readCurrent() }.getOrNull())

    /**
     * The refreshed token to retry a refused call with — or null when this
     * refusal episode already used its one refresh (the caller then goes anonymous).
     */
    suspend fun refreshAfterRefusal(): String? {
        val running = lock.withLock {
            inFlight ?: if (spent) {
                null
            } else {
                spent = true
                // Its own scope: the refresh must not die with whichever call
                // happened to start it while others wait on it.
                scope.async { normalized(runCatching { readRefreshed() }.getOrNull()) }.also { inFlight = it }
            }
        } ?: return null
        val token = running.await()
        lock.withLock { if (inFlight === running) inFlight = null }
        return token
    }

    /** A call that carried a token was accepted: the next refusal may refresh again. */
    fun accepted() { spent = false }

    /** A different user signed in (or signed out): start a new refusal episode. */
    fun reset() { spent = false }

    /** Test probe: true while this refusal episode has used its one refresh. */
    internal val refreshSpentForTesting: Boolean get() = spent

    private fun normalized(token: String?): String? = token?.takeIf { it.isNotEmpty() }
}

/**
 * Decides, per auth emission, whether a new refusal episode starts: only when the
 * user changes. `Onelo` reloads features on EVERY `onAuthStateChange()` emission,
 * and a refresh emits the same user again — resetting there would re-arm the
 * refresh and loop refresh → load → refused → refresh against a server that
 * keeps refusing (review trap R1-1 of #530).
 */
internal class UserTokenEpisode(private val source: UserTokenSource) {
    private var boundUserId: String? = null

    @Synchronized
    fun onSession(userId: String?) {
        if (userId != boundUserId) source.reset()
        boundUserId = userId
    }
}

/** How one attempt of a Features call proves (or does not prove) the user. */
internal data class UserTokenAttempt(
    /** The Bearer to send, or null for no `Authorization` header. */
    val token: String?,
    /** True for the final fallback after a refused token: send no `userId` either. */
    val anonymous: Boolean,
)

internal const val INVALID_USER_TOKEN = "invalid_user_token"

/** `Authorization` header for an attempt — empty when it carries no token. */
internal fun bearerHeaders(token: String?): Map<String, String> =
    if (token == null) emptyMap() else mapOf("Authorization" to "Bearer $token")

/** True for the server's `401 invalid_user_token` (FastAPI `{detail:{error}}`, or a flat `{error}`). */
internal fun isUserTokenRefusal(status: Int, body: Map<String, Any?>): Boolean =
    status == 401 && extractErrorCode(body) == INVALID_USER_TOKEN

/**
 * Send one Features call with the signed-in user's token.
 *
 * [send] performs a single request for the given attempt. When the server
 * refuses the token, the session is refreshed (at most once per refusal episode)
 * and the call retried once; if that is refused too (or there is no refreshed
 * token), [onRefused] runs and, unless [anonymousFallback] is false, the call is
 * sent one last time as anonymous. The response of the last attempt made is
 * returned.
 *
 * [provesAcceptance] is false for an endpoint whose 2xx does not prove the token
 * was checked (batch-ping answers a throttled 204 before looking at it); such a
 * call never ends a refusal episode.
 */
internal suspend fun sendWithUserToken(
    source: UserTokenSource?,
    anonymousFallback: Boolean = true,
    provesAcceptance: Boolean = true,
    onRefused: () -> Unit,
    send: suspend (UserTokenAttempt) -> HttpResponse,
): HttpResponse {
    val token = source?.current()
    val first = send(UserTokenAttempt(token, anonymous = false))
    if (source == null || token == null) return first
    if (!isUserTokenRefusal(first.status, first.body)) {
        if (provesAcceptance && first.status in 200..299) source.accepted()
        return first
    }

    var last = first
    val fresh = source.refreshAfterRefusal()
    if (fresh != null) {
        last = send(UserTokenAttempt(fresh, anonymous = false))
        if (!isUserTokenRefusal(last.status, last.body)) {
            if (provesAcceptance && last.status in 200..299) source.accepted()
            return last
        }
    }
    onRefused()
    if (!anonymousFallback) return last
    return send(UserTokenAttempt(null, anonymous = true))
}

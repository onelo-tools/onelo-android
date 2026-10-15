package com.onelo.android.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Runs at most one [run] block at a time: a caller that arrives while one is in
 * flight awaits that same result instead of starting another. The block runs in
 * [scope], so it is not cancelled with whichever caller happened to start it.
 *
 * Used for the session refresh (#519): two overlapping refreshes would present
 * the same rotating refresh token, and the second — refused — clears the session.
 */
internal class SingleFlight<T>(private val scope: CoroutineScope) {
    private val guard = Any()
    private var inFlight: Deferred<T>? = null

    suspend fun run(block: suspend () -> T): T {
        val running = synchronized(guard) {
            inFlight ?: scope.async(start = CoroutineStart.LAZY) { block() }.also { created ->
                inFlight = created
                created.invokeOnCompletion {
                    synchronized(guard) { if (inFlight === created) inFlight = null }
                }
                created.start()
            }
        }
        return running.await()
    }
}

package com.onelo.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A Store Hand-back that moved the plan (#659): a purchase, or a plan switch with
 * the store's reason (`plan_switched` | `switch_scheduled` | `plan_switch_pending`).
 *
 * The hosted store / Customer Portal only closes its Activity. Waiting for the
 * Event Stream left the app on the old plan for about a minute, so every
 * container reports the change to [OneloAuth.reportPlanChange], and [Onelo]
 * reconciles at once through [PlanChangeRefresher]. The live complement is the
 * stream's `paywall.access_changed` (#635).
 */
internal sealed class PlanChange {
    object Purchase : PlanChange()
    data class Switched(val reason: String) : PlanChange()
}

/** The store's reason for a paid switch Stripe has not applied yet (#615). */
internal const val PLAN_SWITCH_PENDING = "plan_switch_pending"

/** Gaps between re-checks after `plan_switch_pending`: ~30 s in all. */
internal val PENDING_RECHECK_DELAYS_MS: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 15_000)

/**
 * The ONE place the SDK reacts to "the buyer just bought or switched a plan": a
 * forced REST re-resolve of the features (past the 1 s debounce) and a re-read
 * of the entitlement. A `plan_switch_pending` switch is re-checked on a bounded
 * backoff until the features snapshot differs from the one before the
 * hand-back, or the delays run out. Same shape as `@onelo/js`
 * `core/plan-change.ts`.
 */
internal class PlanChangeRefresher(
    private val scope: CoroutineScope,
    private val refreshFeatures: suspend () -> Unit,
    private val revalidateEntitlement: suspend () -> Unit,
    private val snapshot: () -> String,
    private val delaysMs: List<Long> = PENDING_RECHECK_DELAYS_MS,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    private var job: Job? = null

    /** Reconcile after a hand-back. A newer hand-back supersedes a running one. */
    @Synchronized
    fun handBack(change: PlanChange): Job {
        job?.cancel()
        val next = scope.launch { run(change) }
        job = next
        return next
    }

    /** Stop any pending re-checks. */
    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
    }

    /** Best-effort: a failed refresh leaves the cached state; cancellation still propagates. */
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun run(change: PlanChange) {
        val before = snapshot()
        quietly { refreshFeatures() }
        quietly { revalidateEntitlement() }
        if (change !is PlanChange.Switched || change.reason != PLAN_SWITCH_PENDING) return
        for (d in delaysMs) {
            if (snapshot() != before) return
            sleep(d)
            quietly { refreshFeatures() }
            if (snapshot() != before) {
                quietly { revalidateEntitlement() }
                return
            }
        }
    }
}

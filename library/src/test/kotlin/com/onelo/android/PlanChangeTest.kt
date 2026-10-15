package com.onelo.android

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #659 — after a store purchase / plan switch the app kept the old plan until the
 * Event Stream caught up. Every hosted Activity now reports the change, and one
 * refresher re-resolves features + entitlement at once; a pending switch is
 * re-checked on a bounded backoff until the plan moves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanChangeTest {

    private class Probe(val applyAfter: Int) {
        var refreshes = 0
        var revalidations = 0
        var plan = "pro"
        val slept = mutableListOf<Long>()
    }

    private fun refresher(scope: TestScope, p: Probe) = PlanChangeRefresher(
        scope = scope,
        refreshFeatures = {
            p.refreshes++
            if (p.refreshes >= p.applyAfter) p.plan = "business"
        },
        revalidateEntitlement = { p.revalidations++ },
        snapshot = { p.plan },
        sleep = { p.slept.add(it) },
    )

    @Test
    fun `a finished switch refreshes once, at once`() = runTest(StandardTestDispatcher()) {
        val p = Probe(applyAfter = 1)
        refresher(this, p).handBack(PlanChange.Switched("plan_switched"))
        advanceUntilIdle()
        assertEquals(1, p.refreshes)
        assertEquals(1, p.revalidations)
        assertEquals(emptyList<Long>(), p.slept)
    }

    @Test
    fun `a pending switch is re-checked until the plan moves`() = runTest(StandardTestDispatcher()) {
        val p = Probe(applyAfter = 3)
        refresher(this, p).handBack(PlanChange.Switched(PLAN_SWITCH_PENDING))
        advanceUntilIdle()
        assertEquals(3, p.refreshes)
        assertEquals(listOf(1_000L, 2_000L), p.slept)
        assertEquals("once at the hand-back, once when the plan moved", 2, p.revalidations)
    }

    @Test
    fun `a pending switch gives up after the bounded delays`() = runTest(StandardTestDispatcher()) {
        val p = Probe(applyAfter = Int.MAX_VALUE)
        refresher(this, p).handBack(PlanChange.Switched(PLAN_SWITCH_PENDING))
        advanceUntilIdle()
        assertEquals(1 + PENDING_RECHECK_DELAYS_MS.size, p.refreshes)
        assertEquals(PENDING_RECHECK_DELAYS_MS, p.slept)
    }

    @Test
    fun `a purchase does not poll`() = runTest(StandardTestDispatcher()) {
        val p = Probe(applyAfter = Int.MAX_VALUE)
        refresher(this, p).handBack(PlanChange.Purchase)
        advanceUntilIdle()
        assertEquals(1, p.refreshes)
    }

}

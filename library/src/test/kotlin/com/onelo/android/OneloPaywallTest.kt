package com.onelo.android

import org.junit.Assert.*
import org.junit.Test

class OneloPaywallTest {

    private val paywall = OneloPaywall()

    @Test
    fun `allows access when user plan meets requirement`() {
        assertTrue(paywall.check("free", "free"))
        assertTrue(paywall.check("pro", "business"))
        assertTrue(paywall.check("free", "pro"))
    }

    @Test
    fun `blocks access when user plan is below requirement`() {
        assertFalse(paywall.check("pro", "free"))
        assertFalse(paywall.check("enterprise", "business"))
    }

    @Test
    fun `defaults userPlan to free`() {
        assertTrue(paywall.check("free"))
        assertFalse(paywall.check("pro"))
    }

    @Test
    fun `returns false for unknown plans`() {
        assertFalse(paywall.check("unknown", "free"))
        assertFalse(paywall.check("pro", "vip"))
    }
}

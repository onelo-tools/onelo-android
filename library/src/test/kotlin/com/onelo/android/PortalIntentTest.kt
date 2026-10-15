package com.onelo.android

import org.junit.Assert.assertEquals
import org.junit.Test

/** #606 — the change-plan intent rides on the hosted portal URL, like `@onelo/js`. */
class PortalIntentTest {
    @Test
    fun `change_plan is appended to the portal query`() {
        assertEquals(
            "https://onelo.tools/customer/portal?token=prt_1&intent=change_plan",
            withPortalIntent("https://onelo.tools/customer/portal?token=prt_1", PortalIntent.CHANGE_PLAN),
        )
    }

    @Test
    fun `no intent leaves the url alone`() {
        assertEquals(
            "https://onelo.tools/customer/portal?token=prt_1",
            withPortalIntent("https://onelo.tools/customer/portal?token=prt_1", null),
        )
    }

    @Test
    fun `the intent is set once and a fragment survives`() {
        assertEquals(
            "https://onelo.tools/customer/portal?token=prt_1&intent=change_plan#x",
            withPortalIntent("https://onelo.tools/customer/portal?intent=other&token=prt_1#x", PortalIntent.CHANGE_PLAN),
        )
    }

    @Test
    fun `a url without a query gets one`() {
        assertEquals(
            "https://onelo.tools/customer/portal?intent=change_plan",
            withPortalIntent("https://onelo.tools/customer/portal", PortalIntent.CHANGE_PLAN),
        )
    }
}

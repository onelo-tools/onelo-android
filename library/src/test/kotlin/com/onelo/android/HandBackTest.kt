package com.onelo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #615 — the one Store Hand-back decoder: every `<scheme>://callback` shape. */
class HandBackTest {
    @Test
    fun `every wire shape`() {
        assertEquals(HandBack.Purchase("oac_1"), classifyHandBack(null, null, "oac_1", null))
        assertEquals(HandBack.Switched("plan_switched"), classifyHandBack("store", "plan_switched", null, null))
        assertEquals(HandBack.Switched("plan_switch_pending"), classifyHandBack("store", "plan_switch_pending", null, null))
        assertEquals(HandBack.Switched("switch_scheduled"), classifyHandBack("store", "switch_scheduled", null, null))
        assertEquals(HandBack.Switched(""), classifyHandBack("store", null, null, null))
        assertEquals(HandBack.PortalClosed(null), classifyHandBack("portal", null, null, null))
        assertEquals(HandBack.PortalClosed("account_revoked"), classifyHandBack("portal", "account_revoked", null, null))
        assertEquals(HandBack.Error("token_expired"), classifyHandBack(null, null, null, "token_expired"))
    }

    @Test
    fun `nothing to decode`() {
        assertNull(classifyHandBack(null, null, null, null))
        assertNull(classifyHandBack(null, "plan_switched", "", ""))
    }

    // #619 — a dead store link restarts in place, over either transport.
    @Test
    fun `a dead store link restarts, a cancel does not`() {
        assertTrue(OneloAuthActivity.restartsOnHandBack(classifyHandBackMessage("onelo:cancel", "", "expired")!!))
        assertTrue(OneloAuthActivity.restartsOnHandBack(classifyHandBack(null, null, null, "token_expired")!!))
        assertFalse(OneloAuthActivity.restartsOnHandBack(classifyHandBackMessage("onelo:cancel", "", "")!!))
        assertFalse(OneloAuthActivity.restartsOnHandBack(HandBack.PortalClosed(null)))
        assertFalse(OneloAuthActivity.restartsOnHandBack(HandBack.Error("access_denied")))
    }

    // #627 — the store's postMessage hand-back goes through the same HandBack type.
    @Test
    fun `every postMessage shape`() {
        assertEquals(HandBack.Purchase("oac_1"), classifyHandBackMessage("onelo:code", "oac_1", ""))
        assertEquals(HandBack.Switched("switch_scheduled"), classifyHandBackMessage("onelo:done", "", "switch_scheduled"))
        assertEquals(HandBack.Error("token_expired"), classifyHandBackMessage("onelo:cancel", "", "expired"))
        assertEquals(HandBack.Error("cancelled"), classifyHandBackMessage("onelo:cancel", "", ""))
        assertNull(classifyHandBackMessage("onelo:code", "", ""))
        assertNull(classifyHandBackMessage("onelo:cancelled", "", ""))
        assertNull(classifyHandBackMessage("onelo:ready", "", ""))
    }

    @Test
    fun `the relay forwards exactly the three hand-back messages and decides nothing`() {
        val js = HAND_BACK_RELAY_SNIPPET
        assertTrue(js.contains("data.type !== 'onelo:code' && data.type !== 'onelo:done' && data.type !== 'onelo:cancel'"))
        assertTrue(js.contains("OneloAndroid.onHandBack(data.type"))
        assertFalse(js.contains("onelo:cancelled"))
    }
}

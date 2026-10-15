package com.onelo.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * #613 / #614 — the hosted portal asks the app for a fresh portal token when its
 * own (10 minutes) has died. The reply goes back into the page as a self-post.
 */
class PortalRenewalTest {

    @Test
    fun `renewed reply carries the request id and the fresh hosted url`() {
        val reply = PortalRenewal.renewed("r1", "https://onelo.tools/customer/portal?token=prt_new")
        assertEquals("onelo:renewed", reply.getString("type"))
        assertEquals("r1", reply.getString("request_id"))
        assertEquals("https://onelo.tools/customer/portal?token=prt_new", reply.getString("hosted_url"))
    }

    @Test
    fun `failed reply names the reason the page shows`() {
        val reply = PortalRenewal.failed("r2", "not_authenticated")
        assertEquals("onelo:renew_failed", reply.getString("type"))
        assertEquals("not_authenticated", reply.getString("reason"))
    }

    @Test
    fun `reply script is a self-post of an escaped JSON literal`() {
        val script = PortalRenewal.replyScript(PortalRenewal.failed("\"); alert(1); (\"", "x"))
        assertTrue(script.startsWith("window.postMessage({"))
        assertTrue(script.endsWith("}, '*');"))
        val literal = script.removePrefix("window.postMessage(").removeSuffix(", '*');")
        assertEquals("\"); alert(1); (\"", JSONObject(literal).getString("request_id"))
    }

    @Test
    fun `relay forwards only portal renew requests, and a request parked before it ran`() {
        val snippet = PortalRenewal.RELAY_SNIPPET
        assertTrue(snippet.contains("d.type === 'onelo:renew_request'"))
        assertTrue(snippet.contains("d.kind === 'portal'"))
        assertTrue(snippet.contains("OneloAndroid.renewPortal(d.request_id)"))
        assertTrue(snippet.contains("window.__oneloRenewPending"))
    }

    @Test
    fun `origin comparison ignores path and case, keeps port`() {
        assertEquals("https://onelo.tools", PortalRenewal.originOf("https://Onelo.tools/customer/portal?token=prt_x"))
        assertEquals("http://localhost:3000", PortalRenewal.originOf("http://localhost:3000/store/hosted"))
        assertNotEquals(PortalRenewal.originOf("https://onelo.tools/x"), PortalRenewal.originOf("https://checkout.stripe.com/x"))
        assertNull(PortalRenewal.originOf(null))
        assertNull(PortalRenewal.originOf("not a url"))
    }
}

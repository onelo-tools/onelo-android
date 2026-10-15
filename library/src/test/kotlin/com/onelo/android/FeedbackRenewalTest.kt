package com.onelo.android

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #622 — the hosted feedback form's reporter token `ft` lives 30 minutes. A form
 * left open longer asks the app for a fresh one on submit, as a self-post; the
 * relay forwards only that request to the `OneloFeedback` bridge's `renew`.
 */
class FeedbackRenewalTest {

    private val relay = OneloFeedbackActivity.RELAY_JS

    @Test
    fun `relay forwards a feedback renew request to the bridge`() {
        assertTrue(relay.contains("d.type === 'onelo:renew_request'"))
        assertTrue(relay.contains("d.kind === 'feedback'"))
        assertTrue(relay.contains("OneloFeedback.renew(d.request_id)"))
    }

    @Test
    fun `only the page's own self-post may ask, or a request parked before the relay ran`() {
        assertTrue(relay.contains("e.source === window && oneloFeedbackRenew(data)"))
        assertTrue(relay.contains("if (window.__oneloRenewPending) oneloFeedbackRenew(window.__oneloRenewPending)"))
    }

    @Test
    fun `relay installs once per document`() {
        assertTrue(relay.contains("if (window.__oneloFeedbackRelay) return;"))
    }

    @Test
    fun `the submit, close and ready signals are still relayed`() {
        assertTrue(relay.contains("OneloFeedback.onSubmitted()"))
        assertTrue(relay.contains("OneloFeedback.onClose()"))
        assertTrue(relay.contains("OneloFeedback.onReady()"))
    }
}

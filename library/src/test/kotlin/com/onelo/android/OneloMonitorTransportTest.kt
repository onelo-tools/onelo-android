package com.onelo.android

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Delivery-policy tests for [OneloMonitor]'s batch transport.
 *
 * These drive the REAL policy (classification → re-queue → cap); only the HTTP leg
 * is swapped out via the internal `httpOverride` seam, so a regression in the
 * classification — the bug where a bare `responseCode` read made a 503 look like a
 * success — fails here.
 *
 * Policy under test: ONE attempt per flush. 2xx settles. 429 / 5xx / network
 * re-queue and the NEXT flush carries the batch (the 15s timer IS the retry, so an
 * outage never multiplies request volume). Other 4xx drop. Every event carries a
 * `ts` stamped when it was created.
 */
class OneloMonitorTransportTest {

    private fun config() = OneloConfig(
        publishableKey = "onelo_pk_test_x",
        apiUrl = "https://api.example.com",
    )

    /** Reads the private buffer — the only way to assert "was the batch kept?". */
    @Suppress("UNCHECKED_CAST")
    private fun bufferOf(monitor: OneloMonitor): List<Map<String, Any?>> {
        val f = OneloMonitor::class.java.getDeclaredField("buffer")
        f.isAccessible = true
        val buf = f.get(monitor) as MutableList<Map<String, Any?>>
        return synchronized(buf) { buf.toList() }
    }

    /**
     * The constructor auto-emits ONE unconditional `session_opened` event (parity
     * with @onelo/js — see [OneloMonitor]'s init block) so Feature Health always has
     * a row even on a cold start gated behind sign-in. These transport tests assert
     * exact buffer contents/counts for events THEY push, so strip the auto-emitted
     * event immediately after construction — it is covered by its own test below,
     * not by weakening these assertions.
     */
    @Suppress("UNCHECKED_CAST")
    private fun clearAutoEmittedEvent(monitor: OneloMonitor) {
        val f = OneloMonitor::class.java.getDeclaredField("buffer")
        f.isAccessible = true
        val buf = f.get(monitor) as MutableList<Map<String, Any?>>
        synchronized(buf) { buf.clear() }
    }

    private fun monitor(responder: suspend (String) -> OneloMonitor.RawResponse?): OneloMonitor {
        val m = OneloMonitor(config(), null, { emptyMap() }, responder)
        clearAutoEmittedEvent(m)
        return m
    }

    /** Simulates the wall clock passing the 429 hold-off, without sleeping. */
    private fun expireHoldOff(monitor: OneloMonitor) {
        val f = OneloMonitor::class.java.getDeclaredField("retryAfterUntil")
        f.isAccessible = true
        f.setLong(monitor, 0L)
    }

    @Test
    fun `a 503 costs ONE request, the event stays buffered, the next flush delivers it`() = runTest {
        val attempts = AtomicInteger(0)
        var down = true
        val m = monitor {
            attempts.incrementAndGet()
            if (down) OneloMonitor.RawResponse(503) else OneloMonitor.RawResponse(204)
        }

        m.event("checkout", ok = true)
        m.flush()

        assertEquals("a 503 must cost exactly ONE request — no in-flight retry loop", 1, attempts.get())
        assertEquals("an undelivered batch must stay buffered", 1, bufferOf(m).size)

        // The NEXT flush is the retry.
        down = false
        m.flush()
        assertEquals(2, attempts.get())
        assertTrue("the re-queued batch goes out on the next flush", bufferOf(m).isEmpty())
    }

    /**
     * The load property this change exists for: an outage must cost the backend the
     * SAME number of requests as healthy operation. The old 3-attempt in-flight loop
     * turned N flushes into 3N requests at the exact moment the backend could least
     * afford it.
     */
    @Test
    fun `an outage over N flushes produces exactly N requests, never 3N`() = runTest {
        val attempts = AtomicInteger(0)
        val m = monitor { attempts.incrementAndGet(); OneloMonitor.RawResponse(503) }

        m.event("checkout", ok = true)
        repeat(5) { m.flush() }

        assertEquals("5 flushes must be 5 requests, not 15", 5, attempts.get())
    }

    @Test
    fun `2xx settles the batch in one attempt and clears the buffer`() = runTest {
        val attempts = AtomicInteger(0)
        val m = monitor { attempts.incrementAndGet(); OneloMonitor.RawResponse(204) }

        m.event("checkout", ok = true)
        m.flush()

        assertEquals(1, attempts.get())
        assertTrue("an accepted batch must not be re-queued", bufferOf(m).isEmpty())
    }

    @Test
    fun `400 is not retried — a bad request will not fix itself`() = runTest {
        val attempts = AtomicInteger(0)
        val m = monitor { attempts.incrementAndGet(); OneloMonitor.RawResponse(400) }

        m.event("checkout", ok = true)
        m.flush()

        assertEquals("4xx must settle on the first attempt", 1, attempts.get())
        assertTrue("a non-retryable rejection must not be re-queued forever", bufferOf(m).isEmpty())
    }

    @Test
    fun `401 is not retried either`() = runTest {
        val attempts = AtomicInteger(0)
        val m = monitor { attempts.incrementAndGet(); OneloMonitor.RawResponse(401) }

        m.event("checkout", ok = true)
        m.flush()

        assertEquals(1, attempts.get())
    }

    @Test
    fun `a network failure costs ONE request and re-queues`() = runTest {
        val attempts = AtomicInteger(0)
        // null = no response at all (DNS/TLS/connect failure).
        val m = monitor { attempts.incrementAndGet(); null }

        m.event("checkout", ok = true)
        m.flush()

        assertEquals("a network failure costs ONE request, not three", 1, attempts.get())
        assertEquals("events survive a network outage in memory", 1, bufferOf(m).size)
    }

    @Test
    fun `429 is not retried and holds off the next flush`() = runTest {
        val attempts = AtomicInteger(0)
        val m = monitor { attempts.incrementAndGet(); OneloMonitor.RawResponse(429, retryAfter = "30") }

        m.event("checkout", ok = true)
        m.flush()
        assertEquals("429 must not be retried", 1, attempts.get())
        // A 429 means the server did NOT take the batch. The rate-limited event
        // must be back in the buffer, not incinerated.
        assertEquals(
            "a rate-limited batch must be re-queued — 429 is not a delivery",
            listOf("checkout"),
            bufferOf(m).map { it["featureName"] },
        )

        // Hold-off armed: a second flush must not touch the network at all.
        m.event("checkout2", ok = true)
        m.flush()
        assertEquals("a Retry-After hold-off must suppress the next flush", 1, attempts.get())
        assertEquals(
            "held-off events stay buffered — the re-queued one AND the new one",
            listOf("checkout", "checkout2"),
            bufferOf(m).map { it["featureName"] },
        )
    }

    /**
     * Regression (data loss): a 429 used to classify as [OneloMonitor.SendOutcome.DONE],
     * so `sendWithRetry` returned true and the `finally` re-queue never ran — the
     * batch, already removed from the buffer before sending, was destroyed. Under
     * sustained rate limiting the SDK discarded 100% of telemetry while looking
     * healthy.
     *
     * Asserts on the PAYLOAD, not on call counts: the exact event must survive the
     * 429 and appear in the body of the send that follows the hold-off.
     */
    @Test
    fun `a rate-limited batch is re-delivered once the hold-off expires`() = runTest {
        val bodies = mutableListOf<String>()
        var rateLimited = true
        val m = monitor { body ->
            bodies += body
            if (rateLimited) OneloMonitor.RawResponse(429, retryAfter = "30")
            else OneloMonitor.RawResponse(204)
        }

        m.event("survives-429", ok = true)
        m.flush()

        assertEquals("429 must not be retried in-loop", 1, bodies.size)
        assertEquals(
            "the rate-limited event must still be buffered",
            listOf("survives-429"),
            bufferOf(m).map { it["featureName"] },
        )

        // Wall clock passes the hold-off; the backend now accepts.
        expireHoldOff(m)
        rateLimited = false
        m.flush()

        assertEquals("the hold-off must expire and the batch go out", 2, bodies.size)
        assertTrue(
            "the re-queued event must be in the delivered payload, got: ${bodies.last()}",
            bodies.last().contains("survives-429"),
        )
        assertTrue("an accepted batch is gone for good", bufferOf(m).isEmpty())
    }

    @Test
    fun `buffer does not grow past its cap under a sustained outage`() = runTest {
        val m = monitor { OneloMonitor.RawResponse(503) }

        // Far more events than the 200-event cap, with repeated failed drains
        // in between so the re-queue path is exercised (not just push eviction).
        repeat(10) { round ->
            repeat(100) { i -> m.event("f$round-$i", ok = true) }
            m.flush()
        }

        val size = bufferOf(m).size
        assertTrue("buffer must stay capped under a sustained outage, was $size", size <= 200)
        // Newest-wins: the most recent event must have survived the trimming.
        assertEquals("f9-99", bufferOf(m).last()["featureName"])
    }

    /**
     * A batch that waits out an outage must still report WHEN it happened. Without a
     * client `ts` the backend falls back to ingest time (`_resolve_event_ts`), so an
     * outage reads as calm during the failure and a phantom spike the moment it ENDS.
     *
     * Covers BOTH paths: a normal pushed event and a `feature_call_summary`.
     */
    @Test
    fun `ts is stamped when the event happened, not when the batch is finally sent`() = runTest {
        val bodies = mutableListOf<String>()
        var down = true
        val m = monitor { body ->
            bodies += body
            if (down) OneloMonitor.RawResponse(503) else OneloMonitor.RawResponse(204)
        }

        val happenedAt = System.currentTimeMillis()
        m.event("checkout", ok = true)
        m.trackFeatureCall("summarised")
        m.flush() // fails — the batch is held

        // The outage lasts. Real wall clock, so the stamp can be told apart from now.
        Thread.sleep(1_200)

        down = false
        m.flush()
        val sentAt = System.currentTimeMillis()

        val events = JSONObject(bodies.last()).getJSONArray("events")
        assertTrue("the delayed batch must actually be delivered", events.length() >= 2)

        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }

        var sawSummary = false
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            val name = e.optString("featureName")
            if (e.optString("source") == "feature_call_summary") sawSummary = true
            val raw = e.optString("ts", "")
            assertTrue(
                "event $name carries no ts — the backend would fall back to ingest time",
                raw.isNotEmpty(),
            )
            val parsed = iso.parse(raw)!!.time
            assertTrue(
                "ts for $name must be the event's own time, got $raw",
                parsed - happenedAt <= 500,
            )
            assertTrue(
                "ts for $name must be clearly EARLIER than the send, got $raw",
                sentAt - parsed >= 1_000,
            )
        }
        assertTrue("the summary-drain path must be covered too", sawSummary)
    }

    /**
     * Teardown must stay inside its budget against a server that never answers. This
     * is the hazard that motivated one attempt: `flushBlocking` runs on the crash
     * path (often the MAIN thread), and `HttpURLConnection`'s blocking read is not a
     * cancellation point, so `withTimeoutOrNull` alone could not stop it.
     */
    @Test
    fun `flushBlocking stays inside its budget against a server that never responds`() {
        val m = monitor { kotlinx.coroutines.delay(30_000); OneloMonitor.RawResponse(204) }
        m.event("checkout", ok = true)

        val start = System.currentTimeMillis()
        m.flushBlocking(1_000)
        val elapsed = System.currentTimeMillis() - start

        assertTrue("teardown took ${elapsed}ms — must stay near its 1s budget", elapsed < 3_000)
        assertEquals("an undelivered batch is re-queued, not lost", 1, bufferOf(m).size)
    }

    /**
     * The constructor must auto-emit ONE unconditional `session_opened` event —
     * before any application/test code runs — so an app that gates every
     * instrumented operation behind sign-in/paywall/consent still shows up in
     * Feature Health on a cold start. Constructed directly here (bypassing the
     * [monitor] helper, which strips it for the other tests).
     */
    @Test
    fun `construction auto-emits one unconditional session_opened event`() {
        val m = OneloMonitor(config(), null, { emptyMap() }) { OneloMonitor.RawResponse(204) }

        val events = bufferOf(m)
        assertEquals("constructor must emit exactly one auto event", 1, events.size)
        val auto = events.single()
        assertEquals("session_opened", auto["featureName"])
        assertEquals(true, auto["ok"])
        assertEquals("event", auto["source"])
        assertTrue("no error should be attached", auto["error"] == null)
    }

    @Test
    fun `an undelivered batch is delivered on a later flush once the server recovers`() = runTest {
        var down = true
        val m = monitor { if (down) OneloMonitor.RawResponse(503) else OneloMonitor.RawResponse(204) }

        m.event("checkout", ok = true)
        m.flush()
        assertEquals(1, bufferOf(m).size)

        down = false
        m.flush()
        assertTrue("the re-queued batch must go out once the backend recovers", bufferOf(m).isEmpty())
    }
}

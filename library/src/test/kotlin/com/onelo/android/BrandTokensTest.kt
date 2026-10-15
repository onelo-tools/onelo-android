package com.onelo.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #681 — the Activities paint their chrome (grounds, progress bar, "Try again"
 * button, skeletons) from the backend's `brand_tokens`: the parser, the colour
 * resolution the Activities use, and the neutral fallback (never orange).
 */
class BrandTokensTest {

    private val served: Map<String, Any?> = mapOf(
        "version" to 1, "page_bg" to "#f5f5f4", "brand" to "#e4007c", "card_bg" to "#ffffff",
        "text" to "#111111", "text_muted" to "rgba(17,17,17,0.65)", "border" to "#e4e4e7",
        "button_bg" to "#00aa55", "button_text" to "#010101", "button_radius" to 3,
        "field_radius" to 7, "card_radius" to 21, "body_font" to "'Poppins', sans-serif",
        "heading_font" to "'Lato', sans-serif", "spinner_track" to "rgba(0,0,0,0.12)", "light_bg" to true,
    )

    @After fun tearDown() = OneloBrand.reset()

    @Test fun parsesBrandTokensFromAConfigBody() {
        val t = OneloBrandTokens.fromResponse(mapOf("primary_color" to "#f97316", "brand_tokens" to served))!!
        assertEquals("#00aa55", t.buttonBg)
        assertEquals(3f, t.buttonRadius)
        assertEquals("rgba(17,17,17,0.65)", t.textMuted)
        assertTrue(t.lightBg)
    }

    @Test fun olderBackendSeedsFromLegacyColours() {
        val t = OneloBrandTokens.fromResponse(mapOf("primary_color" to "#0055ff", "checkout_bg_color" to "#ffffff"))!!
        assertEquals("#0055ff", t.buttonBg)
        assertEquals("#ffffff", t.pageBg)
        assertEquals("#111111", t.text)
        assertTrue(t.lightBg)
        assertNull(OneloBrandTokens.fromResponse(mapOf("hosted_url" to "https://x")))
    }

    @Test fun chromeColoursComeFromTheTokens() {
        OneloBrand.adopt(mapOf("brand_tokens" to served))
        val c = OneloBrandChrome.from(OneloBrand.current)
        assertEquals(0xFFF5F5F4.toInt(), c.pageBg)
        assertEquals(0xFF00AA55.toInt(), c.buttonBg)
        assertEquals(0xFF010101.toInt(), c.buttonText)
        assertEquals(0xFFE4007C.toInt(), c.brand)
        assertEquals(3f, c.buttonRadiusPx)
        // rgba(17,17,17,0.65) → alpha 165
        assertEquals((165 shl 24) or 0x111111, c.textMuted)
    }

    @Test fun neutralBeforeConfigIsNeverOrange() {
        val c = OneloBrandChrome.from(OneloBrand.current)
        assertEquals(OneloBrandTokens.NEUTRAL, OneloBrand.current)
        assertNotEquals(0xFFF97316.toInt(), c.buttonBg)
        assertNotEquals(0xFFF97316.toInt(), c.brand)
        assertFalse(OneloBrand.current.lightBg)
    }

    @Test fun malformedValuesKeepTheFallback() {
        val t = OneloBrandTokens.parse(served + mapOf("button_bg" to "red;x", "card_radius" to "big"))
        assertEquals(OneloBrandTokens.NEUTRAL.buttonBg, t.buttonBg)
        assertEquals(OneloBrandTokens.NEUTRAL.cardRadius, t.cardRadius)
    }

    @Test fun skeletonHtmlUsesTheTokens() {
        val t = OneloBrandTokens.parse(served)
        val sk = oneloPortalSkeletonHtml(t)
        assertTrue(sk.contains("body{background:#f5f5f4"))
        assertTrue(sk.contains("background-color:#ffffff"))
        assertTrue(sk.contains("border-radius:21px"))
        assertFalse(sk.contains("rgba(255,255,255,0.04)"))
        val fb = oneloFeedbackSkeletonHtml(t)
        assertTrue(fb.contains(".btn{width:100%;height:48px;border-radius:3px}"))
    }
}

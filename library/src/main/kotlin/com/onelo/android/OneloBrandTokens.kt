package com.onelo.android

import org.json.JSONObject

/**
 * The app's resolved brand tokens (#681) — the ONE model this SDK's native chrome
 * is painted from: the progress bar, retry / "Try again" buttons, skeleton
 * placeholders, error screens and the Activity / WebView grounds.
 *
 * The backend resolves the app's Branding once and sends it as `brand_tokens` on
 * `/api/sdk/config` and the store / portal / manage / upgrade initiate responses.
 * An older backend sends only `primary_color` + `checkout_bg_color`; those seed
 * the same shape with the registry's bg-aware defaults. Before any response
 * arrives the SDK paints [NEUTRAL] — a grey accent on the dark page default,
 * never Onelo orange. [OneloBrand.current] holds the value the Activities read.
 */
data class OneloBrandTokens(
    val version: Int = 1,
    val pageBg: String,
    val brand: String,
    val cardBg: String,
    val text: String,
    /** May be an `rgba(...)` string. */
    val textMuted: String,
    val border: String,
    val buttonBg: String,
    val buttonText: String,
    /** px */
    val buttonRadius: Float,
    val fieldRadius: Float,
    val cardRadius: Float,
    /** CSS font-family stack (WebView skeletons use it as CSS). */
    val bodyFont: String,
    val headingFont: String,
    /** May be an `rgba(...)` string. */
    val spinnerTrack: String,
    val lightBg: Boolean,
) {
    fun toJson(): String = JSONObject()
        .put("version", version).put("page_bg", pageBg).put("brand", brand)
        .put("card_bg", cardBg).put("text", text).put("text_muted", textMuted)
        .put("border", border).put("button_bg", buttonBg).put("button_text", buttonText)
        .put("button_radius", buttonRadius.toDouble()).put("field_radius", fieldRadius.toDouble())
        .put("card_radius", cardRadius.toDouble()).put("body_font", bodyFont)
        .put("heading_font", headingFont).put("spinner_track", spinnerTrack)
        .put("light_bg", lightBg).toString()

    companion object {
        private const val SYSTEM_FONT = "-apple-system, BlinkMacSystemFont, \"Segoe UI\", sans-serif"

        /** Before config arrives: the page default with the registry's unknown-app
         *  accent — neutral, never orange. */
        @JvmField
        val NEUTRAL = OneloBrandTokens(
            pageBg = "#111111", brand = "#a1a1aa", cardBg = "#1a1a1a", text = "#ffffff",
            textMuted = "rgba(255,255,255,0.6)", border = "#2a2a2a",
            buttonBg = "#a1a1aa", buttonText = "#111111",
            buttonRadius = 14f, fieldRadius = 14f, cardRadius = 14f,
            bodyFont = SYSTEM_FONT, headingFont = SYSTEM_FONT,
            spinnerTrack = "rgba(255,255,255,0.18)", lightBg = false,
        )

        private val HEX = Regex("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
        private val RGBA = Regex("^rgba?\\(\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*(?:,\\s*([0-9.]+)\\s*)?\\)$")
        private val FONT = Regex("^[A-Za-z0-9 ,\\\"'_-]{1,200}$")

        private fun hexOrNull(v: Any?): String? =
            (v as? String)?.trim()?.takeIf { HEX.matches(it) }

        private fun cssColorOrNull(v: Any?): String? =
            (v as? String)?.trim()?.takeIf { HEX.matches(it) || RGBA.matches(it) }

        /** The shape an older backend implies: its two colours + bg-aware defaults. */
        @JvmStatic
        fun fromLegacy(primaryColor: String?, pageBg: String?): OneloBrandTokens {
            val bg = hexOrNull(pageBg) ?: NEUTRAL.pageBg
            val light = (luminance(bg) ?: 0.0) > 0.5
            var t = NEUTRAL.copy(pageBg = bg, lightBg = light)
            if (light) t = t.copy(
                cardBg = "#ffffff", text = "#111111", textMuted = "rgba(17,17,17,0.65)",
                border = "#e4e4e7", spinnerTrack = "rgba(0,0,0,0.12)",
            )
            hexOrNull(primaryColor)?.let { t = t.copy(brand = it, buttonBg = it, buttonText = "#ffffff") }
            return t
        }

        /** Parse a `brand_tokens` object; a missing / malformed field keeps [fallback]'s. */
        @JvmStatic
        fun parse(raw: Map<String, Any?>, fallback: OneloBrandTokens = NEUTRAL): OneloBrandTokens {
            fun c(k: String, d: String) = cssColorOrNull(raw[k]) ?: d
            fun r(k: String, d: Float) = (raw[k] as? Number)?.toFloat()?.takeIf { it.isFinite() && it in 0f..100f } ?: d
            fun f(k: String, d: String): String {
                val v = (raw[k] as? String)?.trim() ?: return d
                return if (FONT.matches(v)) v else d
            }
            return OneloBrandTokens(
                version = (raw["version"] as? Number)?.toInt() ?: 1,
                pageBg = c("page_bg", fallback.pageBg), brand = c("brand", fallback.brand),
                cardBg = c("card_bg", fallback.cardBg), text = c("text", fallback.text),
                textMuted = c("text_muted", fallback.textMuted), border = c("border", fallback.border),
                buttonBg = c("button_bg", fallback.buttonBg), buttonText = c("button_text", fallback.buttonText),
                buttonRadius = r("button_radius", fallback.buttonRadius),
                fieldRadius = r("field_radius", fallback.fieldRadius),
                cardRadius = r("card_radius", fallback.cardRadius),
                bodyFont = f("body_font", fallback.bodyFont), headingFont = f("heading_font", fallback.headingFont),
                spinnerTrack = c("spinner_track", fallback.spinnerTrack),
                lightBg = raw["light_bg"] as? Boolean ?: fallback.lightBg,
            )
        }

        /**
         * The tokens a config / initiate response body carries: its `brand_tokens`
         * when present, else the shape its legacy colour fields imply, else null.
         */
        @JvmStatic
        fun fromResponse(body: Map<String, Any?>): OneloBrandTokens? {
            val primary = body["primary_color"] as? String
            val bg = body["checkout_bg_color"] as? String
            val legacy = fromLegacy(primary, bg)
            @Suppress("UNCHECKED_CAST")
            (body["brand_tokens"] as? Map<String, Any?>)?.let { return parse(it, legacy) }
            return if (hexOrNull(primary) != null || hexOrNull(bg) != null) legacy else null
        }

        /**
         * A token colour as an ARGB int — `#rgb` / `#rrggbb` / `#rrggbbaa` or
         * `rgb(a)(…)`. Pure Kotlin (no android.graphics.Color) so it is unit-tested
         * on the JVM. Null when malformed.
         */
        @JvmStatic
        fun argb(css: String): Int? {
            val v = css.trim()
            RGBA.matchEntire(v)?.let { m ->
                val r = m.groupValues[1].toDoubleOrNull() ?: return null
                val g = m.groupValues[2].toDoubleOrNull() ?: return null
                val b = m.groupValues[3].toDoubleOrNull() ?: return null
                val a = m.groupValues[4].takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 1.0
                return pack((a * 255).toInt(), r.toInt(), g.toInt(), b.toInt())
            }
            if (!HEX.matches(v)) return null
            var h = v.substring(1)
            if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
            val rgb = h.substring(0, 6).toLong(16).toInt()
            val a = if (h.length == 8) h.substring(6, 8).toInt(16) else 255
            return (a shl 24) or (rgb and 0xFFFFFF)
        }

        private fun pack(a: Int, r: Int, g: Int, b: Int): Int =
            (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

        private fun luminance(hex: String): Double? {
            val c = argb(hex) ?: return null
            fun lin(x: Int): Double {
                val s = x / 255.0
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
        }
    }
}

enum class OneloStatusTone { SUCCESS, DANGER, WARNING }

/** Registry status colours, selected for the page's light/dark ground. */
fun oneloStatusColor(tokens: OneloBrandTokens, tone: OneloStatusTone): String = when (tone) {
    OneloStatusTone.SUCCESS -> if (tokens.lightBg) "#15803d" else "#22c55e"
    OneloStatusTone.DANGER -> if (tokens.lightBg) "#dc2626" else "#f87171"
    OneloStatusTone.WARNING -> if (tokens.lightBg) "#b45309" else "#fbbf24"
}

/**
 * The colours / radii the Activities paint with, resolved from brand tokens.
 * Pure, so the mapping the Activities use is unit-tested directly.
 */
data class OneloBrandChrome(
    val pageBg: Int,
    val brand: Int,
    val cardBg: Int,
    val text: Int,
    val textMuted: Int,
    val border: Int,
    val buttonBg: Int,
    val buttonText: Int,
    val spinnerTrack: Int,
    val buttonRadiusPx: Float,
    val cardRadiusPx: Float,
) {
    companion object {
        @JvmStatic
        fun from(t: OneloBrandTokens): OneloBrandChrome {
            val n = OneloBrandTokens.NEUTRAL
            fun c(v: String, d: String) = OneloBrandTokens.argb(v) ?: OneloBrandTokens.argb(d)!!
            return OneloBrandChrome(
                pageBg = c(t.pageBg, n.pageBg), brand = c(t.brand, n.brand),
                cardBg = c(t.cardBg, n.cardBg), text = c(t.text, n.text),
                textMuted = c(t.textMuted, n.textMuted), border = c(t.border, n.border),
                buttonBg = c(t.buttonBg, n.buttonBg), buttonText = c(t.buttonText, n.buttonText),
                spinnerTrack = c(t.spinnerTrack, n.spinnerTrack),
                buttonRadiusPx = t.buttonRadius, cardRadiusPx = t.cardRadius,
            )
        }
    }
}

/**
 * The SDK's one in-memory brand-token model (#681). `/api/sdk/config` sets it,
 * store / portal / upgrade initiate responses refresh it; the Activities read
 * [current] when they build their chrome. [OneloBrandTokens.NEUTRAL] until then.
 */
object OneloBrand {
    @Volatile
    var current: OneloBrandTokens = OneloBrandTokens.NEUTRAL
        private set

    /** Adopt the branding a config / initiate body carries (no-op when none). */
    internal fun adopt(body: Map<String, Any?>) {
        OneloBrandTokens.fromResponse(body)?.let { current = it }
    }

    /** Test seam. */
    internal fun reset() {
        current = OneloBrandTokens.NEUTRAL
    }
}

/** Soft skeleton fill: card colour on a dark page; border colour on a light
 *  page, where the card equals the ground (#948, matches onelo-core). */
internal fun OneloBrandTokens.skeletonSoftFill(): String = if (lightBg) border else cardBg

/** Shimmer placeholder CSS for WebView skeletons (`.sk` soft, `.strong` field). */
internal fun OneloBrandTokens.skeletonCss(): String {
    val sweep = "background-image:linear-gradient(90deg,transparent 0%,$spinnerTrack 50%,transparent 100%);" +
        "background-size:60vw 100%;background-repeat:no-repeat;background-attachment:fixed;" +
        "animation:onelo-shimmer 2.4s linear infinite"
    return "@keyframes onelo-shimmer{0%{background-position:-60vw 0}100%{background-position:100vw 0}}\n" +
        ".sk{background-color:${skeletonSoftFill()};$sweep;border-radius:${fieldRadius.toInt()}px}\n" +
        ".strong{background-color:$border;$sweep;border-radius:${fieldRadius.toInt()}px}"
}

/**
 * Pre-connect Customer Portal skeleton. Layout mirrors
 * frontend/app/customer/portal/PortalSkeleton.tsx 1:1 (the shape every SDK
 * uses); painted from the brand tokens (#681).
 */
internal fun oneloPortalSkeletonHtml(t: OneloBrandTokens): String = """<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:${t.pageBg};font-family:${t.bodyFont};overflow:hidden}
${t.skeletonCss()}
.wrap{display:flex;flex-direction:column;align-items:center;width:100%;padding:48px 24px 0}
.col{width:100%;max-width:432px;display:flex;flex-direction:column;gap:14px}
.icon{width:64px;height:64px;border-radius:${t.cardRadius.toInt()}px;margin-bottom:14px;border:1.5px solid ${t.border}}
.title{width:200px;height:22px;margin-bottom:10px}
.sub{width:80px;height:13px;opacity:0.7;margin-bottom:32px}
.c1{height:240px;border-radius:${t.cardRadius.toInt()}px}
.c2{height:80px;border-radius:${t.cardRadius.toInt()}px}
.c3{height:140px;border-radius:${t.cardRadius.toInt()}px}
</style></head><body>
<div class="wrap">
<div class="sk icon"></div>
<div class="sk title"></div>
<div class="sk sub"></div>
<div class="col"><div class="strong c1"></div><div class="strong c2"></div><div class="strong c3"></div></div>
</div>
</body></html>"""

/** Feedback-form skeleton (icon, title, three type cards, two fields, CTA). */
/** #948: mirrors the hosted feedback page (see onelo-core feedbackSkeletonHtml). */
internal fun oneloFeedbackSkeletonHtml(t: OneloBrandTokens): String = """<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:${t.pageBg};font-family:${t.bodyFont};padding:24px 24px 0;overflow:hidden}
${t.skeletonCss()}
.icon{width:64px;height:64px;border-radius:${t.cardRadius.toInt()}px;margin:0 auto 14px}
.col{width:100%;max-width:360px;margin:0 auto;display:flex;flex-direction:column;gap:16px}
.title{width:220px;height:22px;margin:0 auto 4px}
.cards{display:flex;gap:8px}
.card{flex:1;height:56px;border-radius:${t.cardRadius.toInt()}px}
.field{display:flex;flex-direction:column;gap:6px}
.label{width:60px;height:13px}
.input{width:100%;height:44px}
.textarea{width:100%;height:100px}
.btn{width:100%;height:48px;border-radius:${t.buttonRadius.toInt()}px}
</style></head><body>
<div class="sk icon"></div>
<div class="col">
<div class="sk title"></div>
<div class="cards"><div class="sk card"></div><div class="sk card"></div><div class="sk card"></div></div>
<div class="field"><div class="sk label"></div><div class="strong input"></div></div>
<div class="field"><div class="sk label"></div><div class="strong textarea"></div></div>
<div class="strong btn"></div>
</div>
</body></html>"""

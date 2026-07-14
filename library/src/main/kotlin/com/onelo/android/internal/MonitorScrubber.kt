package com.onelo.android.internal

/**
 * Client-side PII scrubbing for Monitor payloads — Kotlin port of the Swift
 * `MonitorScrubber` / Flutter `MonitorScrubber` / backend `PII_VALUE_PATTERNS`.
 * Redacts sensitive VALUES (Bearer tokens, JWTs, Stripe/Onelo API keys, credit
 * cards) from free-text `error` strings, and whole VALUES held under sensitive
 * KEYS (`password`, `token`, `authorization`, …) from `meta` maps, BEFORE the
 * event leaves the device. The backend scrubs again server-side — this is
 * defense in depth, and it keeps secrets out of the on-device buffer too.
 */
internal object MonitorScrubber {
    const val REDACTED = "[REDACTED]"
    private const val MAX_DEPTH = 10

    /** Key names whose whole value is redacted, matched case-insensitively as a
     *  SUBSTRING (so `sessionToken`, `myPassword` all hit). */
    private val piiKeySubstrings = listOf(
        "password", "passwd", "secret", "token", "api_key", "apikey",
        "authorization", "cookie", "credential", "private_key", "client_secret",
        "cvv", "ssn",
    )

    /** The single short word that is PII only when it stands alone as a `-`/`_`
     *  delimited SEGMENT — so `x-api-key`, `x-groq-key` are redacted, but `monkey`
     *  is not. Byte-for-byte with the Swift + backend scrubbers. */
    private val piiKeySegments = setOf("key")

    /** Value patterns replaced with [REDACTED] — ported 1:1 from the backend
     *  PII_VALUE_PATTERNS / Swift MonitorScrubber. ASCII-only lookarounds
     *  (underscore is NOT a boundary, so `API_sk_live_…` still latches). */
    private val valuePatterns: List<Regex> = listOf(
        // Bearer <token> — case-insensitive keyword
        Regex("(?<![A-Za-z0-9])Bearer\\s+[A-Za-z0-9\\-._~+/]+=*", RegexOption.IGNORE_CASE),
        // JWT — three base64url segments beginning eyJ
        Regex("(?<![A-Za-z0-9])eyJ[A-Za-z0-9\\-_]+\\.[A-Za-z0-9\\-_]+\\.[A-Za-z0-9\\-_]+(?![A-Za-z0-9])"),
        // Stripe-style keys: sk_/pk_/rk_ live|test
        Regex("(?<![A-Za-z0-9])(?:sk|pk|rk)_(?:live|test)_[A-Za-z0-9]{16,}(?![A-Za-z0-9])"),
        // Onelo keys: onelo_pk|sk|rk_live|test
        Regex("(?<![A-Za-z0-9])onelo_(?:pk|sk|rk)_(?:live|test)_[A-Za-z0-9]{8,}(?![A-Za-z0-9])"),
        // Credit cards — separated (Visa/MC/Amex/Discover prefixes + grouped digits)
        Regex("(?<![A-Za-z0-9])(?:4[0-9]{3}|5[1-5][0-9]{2}|3[47][0-9]{2}|6(?:011|5[0-9]{2}))[\\s-][0-9]{4}[\\s-][0-9]{4}[\\s-][0-9]{4}(?![A-Za-z0-9])"),
        // Credit cards — unseparated greedy (Visa/MC/Discover)
        Regex("(?<![A-Za-z0-9])(?:4|5[1-5]|6(?:011|5))[0-9]{14,}(?![0-9])"),
        // Amex — unseparated greedy
        Regex("(?<![A-Za-z0-9])3[47][0-9]{13,}(?![0-9])"),
    )

    fun isPiiKey(key: String): Boolean {
        val lower = key.lowercase()
        if (piiKeySubstrings.any { lower.contains(it) }) return true
        return lower.split(Regex("[-_]")).any { it in piiKeySegments }
    }

    /** Redacts sensitive value patterns from free text. Returns null for null. */
    fun scrubText(text: String?): String? {
        if (text == null) return null
        var out: String = text
        for (re in valuePatterns) out = re.replace(out, REDACTED)
        return out
    }

    /**
     * Recursively scrubs a meta map. Redacts whole values under PII keys, scrubs
     * text values, and appends a sorted `_onelo_redacted` marker listing the keys
     * whose value was redacted (recomputed server-side, so purely informational).
     */
    fun scrubMeta(meta: Map<String, Any?>?): Map<String, Any?>? {
        if (meta == null) return null
        val redactedKeys = mutableListOf<String>()
        val scrubbed = scrubMap(meta, 0, redactedKeys).toMutableMap()
        if (redactedKeys.isNotEmpty()) {
            scrubbed["_onelo_redacted"] = redactedKeys.sorted()
        }
        return scrubbed
    }

    private fun scrubMap(input: Map<*, *>, depth: Int, redactedKeys: MutableList<String>): Map<String, Any?> {
        if (depth >= MAX_DEPTH) return mapOf("_onelo_depth_exceeded" to true)
        val out = mutableMapOf<String, Any?>()
        for ((k, v) in input) {
            val key = k.toString()
            if (isPiiKey(key)) {
                out[key] = REDACTED
                redactedKeys.add(key)
            } else {
                out[key] = scrubValue(v, depth, redactedKeys)
            }
        }
        return out
    }

    private fun scrubValue(v: Any?, depth: Int, redactedKeys: MutableList<String>): Any? = when (v) {
        is String -> scrubText(v)
        is Map<*, *> -> scrubMap(v, depth + 1, redactedKeys)
        is List<*> -> if (depth + 1 >= MAX_DEPTH) mapOf("_onelo_depth_exceeded" to true)
                      else v.map { scrubValue(it, depth + 1, redactedKeys) }
        else -> v
    }
}

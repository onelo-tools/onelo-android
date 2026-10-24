package com.onelo.android

import android.content.Context
import com.onelo.android.internal.HttpClient
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

/**
 * Affiliate referral capture for apps with the Onelo SDK (#929).
 *
 * An Affiliate link that opens your app carries `?ref=<code>` (the Affiliate
 * linking straight to the app) or `?onelo_click=<id>` (forwarded by Onelo's
 * tracked link after consent there). Pass that link with the user's answer:
 *
 * ```kotlin
 * lifecycleScope.launch { onelo.affiliates.capture(intent.dataString ?: return@launch, consent = agreed) }
 * ```
 *
 * - `consent = false` stores nothing on the device; a `?ref=` only adds one to
 *   the Affiliate's anonymous total.
 * - `consent = true` keeps the click (SharedPreferences `onelo_aff`). Which of
 *   two clicks stays is the program's winner rule, decided by Onelo.
 *
 * The kept click is tied to the user who signs in, so their purchase is
 * attributed whichever checkout opens.
 */
class OneloAffiliates internal constructor(
    private val publishableKey: String,
    private val store: Store,
    private val post: suspend (path: String, body: Map<String, Any?>) -> Pair<Int, Map<String, Any?>>?,
    private val session: suspend () -> Pair<String, String>?, // (accessToken, userId)
) {
    /** Where the kept click lives; SharedPreferences on a device. */
    internal interface Store {
        fun read(): Map<String, String>?
        fun write(value: Map<String, String>)
    }

    internal data class Kept(val clickId: String, val claimedFor: String? = null)

    internal fun kept(): Kept? {
        val v = store.read() ?: return null
        val id = v["click_id"] ?: return null
        return if (isClickId(id)) Kept(id, v["claimed_for"]) else null
    }

    private fun keep(k: Kept) {
        store.write(buildMap {
            put("click_id", k.clickId)
            k.claimedFor?.let { put("claimed_for", it) }
        })
    }

    /**
     * Capture the referral in [url] after the user's answer. Stores nothing
     * without consent. Returns whether a referral is now kept on this device.
     */
    suspend fun capture(url: String, consent: Boolean): Boolean {
        val query = queryOf(url)
        val code = query["ref"]?.takeIf(::isCode)?.lowercase()
        val forwarded = query["onelo_click"]?.takeIf(::isClickId)
        if (!consent) {
            if (code != null) post("/v1/sdk/affiliates/refused", mapOf("publishableKey" to publishableKey, "code" to code))
            return false
        }
        val existing = kept()
        if (code != null) {
            val body = mutableMapOf<String, Any?>("publishableKey" to publishableKey, "code" to code, "source" to "sdk")
            existing?.let { body["existing"] = it.clickId }
            val res = post("/v1/sdk/affiliates/click", body)
            val id = res?.second?.get("click_id") as? String
            if (res?.first == 200 && id != null && isClickId(id) && id != existing?.clickId) keep(Kept(id))
        } else if (forwarded != null && forwarded != existing?.clickId) {
            // Onelo's tracked link already recorded this click and applied the
            // program's winner rule; the id it forwards is the one to keep.
            keep(Kept(forwarded))
        }
        claimForSession()
        return kept() != null
    }

    /** Tie the kept click to the signed-in user once. Called on every sign-in. */
    suspend fun claimForSession() {
        val k = kept() ?: return
        val (token, userId) = session() ?: return
        if (k.claimedFor == userId) return
        val res = post(
            "/v1/sdk/affiliates/claim",
            mapOf("publishableKey" to publishableKey, "userToken" to token, "clickId" to k.clickId),
        )
        if (res?.first == 200) keep(k.copy(claimedFor = userId))
    }

    companion object {
        const val STORAGE_KEY = "onelo_aff"

        internal fun isClickId(s: String): Boolean =
            runCatching { UUID.fromString(s).toString().equals(s, ignoreCase = true) }.getOrDefault(false)

        internal fun isCode(s: String): Boolean = s.length in 6..32 && s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

        internal fun queryOf(url: String): Map<String, String> {
            val raw = runCatching { URI(url).rawQuery }.getOrNull() ?: return emptyMap()
            return raw.split('&').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null
                else runCatching { URLDecoder.decode(part.substring(0, i), "UTF-8") to URLDecoder.decode(part.substring(i + 1), "UTF-8") }.getOrNull()
            }.toMap()
        }

        internal fun create(
            config: OneloConfig,
            context: Context,
            securityHeaders: () -> Map<String, String>,
            session: suspend () -> Pair<String, String>?,
        ): OneloAffiliates {
            val prefs = context.applicationContext.getSharedPreferences(STORAGE_KEY, Context.MODE_PRIVATE)
            val store = object : Store {
                override fun read(): Map<String, String>? {
                    val id = prefs.getString("click_id", null) ?: return null
                    return buildMap { put("click_id", id); prefs.getString("claimed_for", null)?.let { put("claimed_for", it) } }
                }
                override fun write(value: Map<String, String>) {
                    prefs.edit().clear().apply { value.forEach { (k, v) -> putString(k, v) } }.apply()
                }
            }
            return OneloAffiliates(config.publishableKey, store, { path, body ->
                runCatching {
                    val headers = mapOf("X-Publishable-Key" to config.publishableKey, "X-SDK-Version" to SDK_VERSION) + securityHeaders()
                    val r = HttpClient.post("${config.apiUrl}$path", body, headers)
                    r.status to r.body
                }.getOrNull()
            }, session)
        }
    }
}

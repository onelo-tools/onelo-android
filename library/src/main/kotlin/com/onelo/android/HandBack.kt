package com.onelo.android

/**
 * Store Hand-back (#615) — the ONE decoder for "the hosted page is finished" in
 * this SDK. The hosted store and Customer Portal navigate the WebView to
 * `<scheme>://callback?…` (produced by one helper on the page,
 * frontend/lib/store-hand-back.ts, plus the portal's Done):
 *  - `?code=`                   a purchase finished → exchange the code
 *  - `?source=store&event=<r>`  a plan switch finished without a code
 *                               (plan_switched | switch_scheduled |
 *                               plan_switch_pending) — a SUCCESS, never a cancel
 *  - `?source=portal[&event=]`  the Customer Portal closed
 *  - `?error=<code>`            failed, or the link is spent
 * [OneloAuthActivity] (store / auth) and [OneloCustomerPortalActivity] both
 * decide from this, so a new terminal reaches both at once.
 *
 * Takes the query values of a `<scheme>://callback` URL (the caller checks the
 * scheme and host). Pure (no android.net.Uri) so it runs in a JVM unit test.
 */
internal sealed class HandBack {
    data class Purchase(val code: String) : HandBack()
    data class Switched(val reason: String) : HandBack()
    data class PortalClosed(val event: String?) : HandBack()
    data class Error(val error: String) : HandBack()
}

/**
 * The same hand-back when the store reports it as a page postMessage instead of a
 * deep-link (#627). The injected relays forward `type` / `code` / `reason` of
 * `{type:'onelo:code', code}` | `{type:'onelo:done', reason}` |
 * `{type:'onelo:cancel', reason:'expired'}` (wire format:
 * frontend/lib/store-hand-back.ts) and decide nothing themselves. A cancel maps
 * to [HandBack.Error] — "the surface is spent", the same meaning as `?error=`.
 */
internal fun classifyHandBackMessage(type: String?, code: String?, reason: String?): HandBack? = when (type) {
    "onelo:code" -> if (!code.isNullOrBlank()) HandBack.Purchase(code) else null
    "onelo:done" -> HandBack.Switched(reason ?: "")
    "onelo:cancel" -> HandBack.Error(if (reason == "expired") "token_expired" else "cancelled")
    else -> null
}

/** The page-side relay: forwards the three hand-back messages to `OneloAndroid.onHandBack`. */
internal const val HAND_BACK_RELAY_SNIPPET = """
function oneloHandBack(data) {
    if (!data || (data.type !== 'onelo:code' && data.type !== 'onelo:done' && data.type !== 'onelo:cancel')) return false;
    OneloAndroid.onHandBack(data.type, typeof data.code === 'string' ? data.code : '', typeof data.reason === 'string' ? data.reason : '');
    return true;
}
"""

internal fun classifyHandBack(source: String?, event: String?, code: String?, error: String?): HandBack? = when {
    source == "store" -> HandBack.Switched(event ?: "")
    source == "portal" -> HandBack.PortalClosed(event)
    !code.isNullOrBlank() -> HandBack.Purchase(code)
    !error.isNullOrBlank() -> HandBack.Error(error)
    else -> null
}

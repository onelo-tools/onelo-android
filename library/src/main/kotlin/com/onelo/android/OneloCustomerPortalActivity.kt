package com.onelo.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Hosts the Onelo-hosted Customer Portal page inside a WebView.
 *
 * Flow:
 *  1. Caller obtains a portal URL via [OneloCustomerPortal.openCustomerPortal] and
 *     launches this Activity via the registered [ActivityResultLauncher].
 *  2. The hosted page loads and the user manages their subscription / account.
 *  3. When the user is done, the hosted page redirects the WebView to
 *     `<callbackScheme>://callback?source=portal` (optionally with
 *     `&event=account_deletion_scheduled`).
 *  4. [OneloCustomerPortalWebViewClient.shouldOverrideUrlLoading] intercepts that
 *     redirect, puts the event (if any) into the result Intent, and finishes the Activity.
 *
 * ## "Change plan" (store checkout inside the portal)
 * The portal's "Change plan" navigates this same WebView to the hosted STORE
 * (`/store/hosted`) — plan selection stays in-app. When the buyer confirms, the store
 * opens the Stripe CARD page with `window.open()`; [onCreateWindow] hands THAT (and
 * only that) to the SYSTEM BROWSER (saved cards, reliable 3-D Secure), and
 * [OneloCustomerPortalWebViewClient] also diverts a same-host card nav
 * (`/store/pay`, `/paywall`) or Stripe Checkout (`*.stripe.com`) there. The card
 * redirects back to `<scheme>://callback?code=`, which returns EITHER in-WebView
 * (the store's status poll — this WebView registers the `OneloAndroid` bridge so the
 * hosted store's `hasNativeBridge()` picks the native deep-link path) OR from the
 * external browser via [Onelo.handleRedirect] → [pendingExternalReturn]. Either way
 * the Activity finishes with `EXTRA_CODE`, and [OneloCustomerPortal] exchanges it for
 * a refreshed session. This mirrors [OneloAuthActivity]'s store handling exactly.
 */
class OneloCustomerPortalActivity : AppCompatActivity() {

    companion object {
        /** Intent extra: the portal hosted_url to load. */
        const val EXTRA_URL = "onelo_portal_url"
        /** Intent extra: the deep-link callback scheme (e.g. "oneloandroid"). */
        const val EXTRA_CALLBACK_SCHEME = "onelo_portal_callback_scheme"
        /**
         * Intent extra in the result: the `event` query parameter from the callback URL,
         * if present (e.g. "account_deletion_scheduled"). Null if not present.
         */
        const val EXTRA_EVENT = "onelo_portal_event"
        /**
         * Intent extra in the result: the one-time `code` from a "Change plan" card
         * that completed. [OneloCustomerPortal] exchanges it for a refreshed session.
         */
        const val EXTRA_CODE = "onelo_portal_code"

        /**
         * Set while an [OneloCustomerPortalActivity] is on-screen. When a "Change plan"
         * card is opened in the SYSTEM BROWSER by [onCreateWindow], its
         * `<scheme>://callback?code=` return deep-links back into the app — NOT into
         * this WebView. [Onelo.handleRedirect] forwards that code here so the Activity
         * finishes with it (reusing the normal result→exchange path). The Android
         * equivalent of Swift's `.onOpenURL` and Flutter's `app_links` store return.
         * Mirrors [OneloAuthActivity.pendingExternalReturn] (checked first there).
         */
        @Volatile
        @JvmStatic
        var pendingExternalReturn: ((String) -> Unit)? = null

        /**
         * Pre-connect loading skeleton shown in the WebView while the hosted portal
         * page loads. Canonical HTML shared across all SDKs — mirrors
         * frontend/app/customer/portal/PortalSkeleton.tsx. Kills the white flash.
         */
        private const val SKELETON_HTML = """<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:#111111;font-family:-apple-system,sans-serif;overflow:hidden}
@keyframes onelo-shimmer{0%{background-position:-60vw 0}100%{background-position:100vw 0}}
.wrap{display:flex;flex-direction:column;align-items:center;width:100%;padding:48px 24px 0}
.col{width:100%;max-width:432px;display:flex;flex-direction:column;gap:14px}
.sk{background-color:rgba(255,255,255,0.04);background-image:linear-gradient(90deg,rgba(255,255,255,0) 0%,rgba(255,255,255,0.12) 50%,rgba(255,255,255,0) 100%);background-size:60vw 100%;background-repeat:no-repeat;background-attachment:fixed;animation:onelo-shimmer 2.4s linear infinite;border-radius:8px}
.strong{background-color:rgba(255,255,255,0.08);background-image:linear-gradient(90deg,rgba(255,255,255,0) 0%,rgba(255,255,255,0.18) 50%,rgba(255,255,255,0) 100%);background-size:60vw 100%;background-repeat:no-repeat;background-attachment:fixed;animation:onelo-shimmer 2.4s linear infinite;border-radius:9px}
.icon{width:64px;height:64px;border-radius:14px;margin-bottom:14px;background:rgba(255,255,255,0.06);border:1.5px solid rgba(255,255,255,0.1)}
.title{width:200px;height:22px;margin-bottom:10px;border-radius:6px}
.sub{width:80px;height:13px;opacity:0.7;margin-bottom:32px;border-radius:4px}
.c1{height:240px;border-radius:14px}
.c2{height:80px;border-radius:14px}
.c3{height:140px;border-radius:14px}
</style></head><body>
<div class="wrap">
<div class="sk icon"></div>
<div class="sk title"></div>
<div class="sk sub"></div>
<div class="col"><div class="strong c1"></div><div class="strong c2"></div><div class="strong c3"></div></div>
</div>
</body></html>"""
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var resultDelivered = false
    // Default matches PORTAL_CALLBACK_SCHEME / OneloConfig.callbackScheme's default.
    private var callbackScheme: String = PORTAL_CALLBACK_SCHEME

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
        callbackScheme = intent.getStringExtra(EXTRA_CALLBACK_SCHEME)?.takeIf { it.isNotBlank() }
            ?: PORTAL_CALLBACK_SCHEME

        if (url.isNullOrBlank()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        // Build layout programmatically — no XML resources needed in the library.
        val container = FrameLayout(this)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Required for [onCreateWindow] to fire when the hosted store window.open's
            // the Stripe card page — so we can divert THAT (and only that) to the
            // system browser instead of loading card entry in this embedded WebView.
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            setBackgroundColor(android.graphics.Color.parseColor("#111111"))
        }

        // Any code that reaches the app while THIS portal WebView is open (a "Change
        // plan" card completed in the external browser) should finish with it.
        pendingExternalReturn = { code -> runOnUiThread { deliverCode(code) } }
        // Register the `OneloAndroid` bridge so the hosted store's hasNativeBridge()
        // recognises this as a native container (→ it returns the auth code via a
        // `<scheme>://callback` nav we intercept, not a postMessage into the void) AND
        // so the postMessage relay below can hand a store `onelo:code` back.
        webView.addJavascriptInterface(OneloBridge(), "OneloAndroid")

        container.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )
        container.addView(
            progressBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        )

        // Neutral on-screen close affordance — a white "✕" in the top-right corner.
        // No Onelo branding; blends into the host app. Added AFTER the WebView +
        // ProgressBar so it sits on top and stays tappable. Hardware Back still works
        // via onBackPressed(). The ~12dp padding gives a comfortable 44dp+ touch
        // target. The activity uses an opaque (non-edge-to-edge) status bar
        // (Theme.AppCompat.NoActionBar, targetSdk 34), so the container already
        // starts below the status bar — a small edge margin is enough to avoid clipping.
        val density = resources.displayMetrics.density
        val touchPadding = (12 * density).toInt()
        val edgeMargin = (4 * density).toInt()
        val closeButton = TextView(this).apply {
            text = "✕"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 22f // sp
            setPadding(touchPadding, touchPadding, touchPadding, touchPadding)
            isClickable = true
            isFocusable = true
            contentDescription = "Close"
            setOnClickListener { deliverCancelled() }
        }
        container.addView(
            closeButton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, edgeMargin, edgeMargin, 0)
            }
        )
        setContentView(container)

        webView.webViewClient = OneloCustomerPortalWebViewClient(
            callbackScheme = callbackScheme,
            onDone = { event -> deliverDone(event) },
            onCode = { code -> deliverCode(code) },
            onExternalOpen = { uri -> openInBrowser(uri) },
            onPageFinished = { progressBar.visibility = View.GONE },
            onError = { deliverCancelled() },
        )

        // = OneloAuthActivity.onCreateWindow. The hosted store `window.open`s the
        // Stripe CARD page; open THAT (and only that) in the SYSTEM BROWSER. Android
        // doesn't hand the target URL to onCreateWindow directly, so we capture it via
        // a throwaway WebView whose shouldOverrideUrlLoading fires with the destination.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                val transient = WebView(view.context)
                transient.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        request?.url?.let { target -> openInBrowser(target) }
                        // Tear the throwaway view down AFTER this callback returns.
                        v?.post { v.destroy() }
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = transient
                resultMsg.sendToTarget()
                return true
            }
        }

        // Show the loading skeleton immediately so there's no white flash while the
        // hosted portal page loads. It stays visible until the real page commits.
        webView.loadDataWithBaseURL(null, SKELETON_HTML, "text/html", "UTF-8", null)
        webView.loadUrl(url)
    }

    /** Open a URL (the Stripe card / external link) in the system browser. Best-effort. */
    private fun openInBrowser(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { /* no browser — best effort */ }
    }

    override fun onDestroy() {
        // Release the external-return hook, but only if it's still ours (a newer
        // OneloCustomerPortalActivity may have replaced it).
        if (!isChangingConfigurations) pendingExternalReturn = null
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!::webView.isInitialized) {
            super.onBackPressed()
            return
        }
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            deliverCancelled()
        }
    }

    /** Deliver RESULT_OK with the portal event (if any) and finish. */
    private fun deliverDone(event: String?) {
        if (resultDelivered) return
        resultDelivered = true
        val data = Intent()
        if (event != null) data.putExtra(EXTRA_EVENT, event)
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    /** Deliver RESULT_OK with a "Change plan" card code and finish (SDK exchanges it). */
    private fun deliverCode(code: String) {
        if (resultDelivered) return
        resultDelivered = true
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_CODE, code))
        finish()
    }

    private fun deliverCancelled() {
        if (resultDelivered) return
        resultDelivered = true
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    /** JS bridge — relays a store `onelo:code` postMessage (parity w/ OneloAuthActivity). */
    private inner class OneloBridge {
        @JavascriptInterface
        fun onCode(code: String) {
            runOnUiThread { deliverCode(code) }
        }

        @JavascriptInterface
        fun onCancelled() {
            // A store cancel inside the portal shouldn't tear the portal down — the
            // buyer may just be backing out of checkout. No-op (Back / ✕ still close).
        }
    }
}

// ---------------------------------------------------------------------------
// WebViewClient — intercepts the portal callback redirect + "Change plan" card
// ---------------------------------------------------------------------------

private class OneloCustomerPortalWebViewClient(
    private val callbackScheme: String,
    private val onDone: (event: String?) -> Unit,
    private val onCode: (code: String) -> Unit,
    private val onExternalOpen: (uri: Uri) -> Unit,
    private val onPageFinished: () -> Unit,
    private val onError: () -> Unit,
) : WebViewClient() {

    override fun onPageFinished(view: WebView?, url: String?) {
        onPageFinished()
        // Inject the store's postMessage relay (same protocol as OneloAuthActivity) so
        // a "Change plan" card that reports success via `onelo:code` reaches us.
        view?.evaluateJavascript(
            """
            (function() {
                if (window.__oneloPortalRelay) return;
                window.__oneloPortalRelay = true;
                window.addEventListener('message', function(e) {
                    try {
                        var data = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
                        if (data && data.type === 'onelo:code' && data.code) {
                            OneloAndroid.onCode(data.code);
                        }
                    } catch(err) {}
                });
            })();
            """.trimIndent(),
            null,
        )
    }

    /**
     * Intercept, in order:
     *  1. `<scheme>://callback?source=portal[&event=…]` → portal Done (existing).
     *  2. `<scheme>://callback?code=…` → a "Change plan" card that completed in-WebView
     *     (the store's native status-poll deep-link) → finish with the code.
     *  3. The Stripe CARD page (`/store/pay`, `/paywall`, `*.stripe.com`) reached by a
     *     top-level nav → SYSTEM BROWSER. Plan selection (`/store/hosted`) stays in-app.
     * All other URLs load normally.
     */
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return false
        return handleUrl(uri)
    }

    // Deprecated override required for API < 23 (kept for broad compatibility,
    // mirrors OneloAuthActivity's approach).
    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        val uri = url?.let { Uri.parse(it) } ?: return false
        return handleUrl(uri)
    }

    private fun handleUrl(uri: Uri): Boolean {
        // 1 + 2: the SDK callback scheme.
        if (uri.scheme?.equals(callbackScheme, ignoreCase = true) == true
            && uri.host?.equals("callback", ignoreCase = true) == true
        ) {
            if (uri.getQueryParameter("source") == "portal") {
                onDone(uri.getQueryParameter("event"))
                return true
            }
            val code = uri.getQueryParameter("code")
            if (!code.isNullOrEmpty()) {
                onCode(code)
                return true
            }
        }
        // 3: the Stripe card surface → system browser.
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            val host = uri.host?.lowercase() ?: ""
            val path = uri.path ?: ""
            if (host.contains("stripe.com") ||
                path.startsWith("/store/pay") || path.startsWith("/paywall")
            ) {
                onExternalOpen(uri)
                return true
            }
        }
        return false
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        if (request?.isForMainFrame == true) {
            onError()
        }
    }
}

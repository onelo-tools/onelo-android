package com.onelo.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.onelo.android.internal.paintGround
import com.onelo.android.internal.tint
import com.onelo.android.internal.tintCloseGlyph
import android.os.Handler
import android.os.Looper
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
import com.onelo.android.internal.applyStatusBarInsetPadding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

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
        /** Result extra (RESULT_OK): the store opened in the portal finished a plan
         *  switch with no checkout; the value is the store's reason (#659). */
        const val EXTRA_STORE_DONE = "onelo_portal_store_done"

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
         * #613 / #614 — answers the hosted page's `onelo:renew_request` with a fresh
         * portal, minted with the signed-in user's session. Set by
         * [OneloCustomerPortal.openCustomerPortal] before it launches this Activity
         * (an Intent cannot carry a closure). Null → the page is told nothing and
         * shows its expiry screen after its own timeout.
         */
        @Volatile
        @JvmStatic
        internal var renewHandler: (suspend (String) -> JSONObject)? = null

        /**
         * #606 — receives this Activity's result when it was started WITHOUT a
         * launcher (`Onelo.openUpgrade` starts it from the application context, so
         * no `onActivityResult` exists). Called once, when the Activity finishes.
         */
        @Volatile
        @JvmStatic
        internal var resultHandler: ((resultCode: Int, data: Intent?) -> Unit)? = null


        // #44 — grace period before the native fail-safe ✕ appears. A normal (fast)
        // load posts `onelo:ready` well before this elapses, so the ✕ never flashes;
        // it shows only if the hosted page hasn't mounted after the grace, or on a
        // WebView load error. Mirrors Swift's 3.5s failsafe timer.
        private const val FAILSAFE_GRACE_MS = 3_500L
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    // #40 Phase 2 / #44 — native fail-safe ✕. Starts GONE; shown only after the grace
    // period (or on load error) if the hosted page hasn't posted `onelo:ready`.
    private lateinit var closeButton: TextView
    private val failsafeHandler = Handler(Looper.getMainLooper())
    private val showFailsafeRunnable = Runnable {
        if (::closeButton.isInitialized) closeButton.visibility = View.VISIBLE
    }
    private var resultDelivered = false
    private val renewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // Default matches PORTAL_CALLBACK_SCHEME / OneloConfig.callbackScheme's default.
    private var callbackScheme: String = PORTAL_CALLBACK_SCHEME
    /** The hosted portal's origin — only a page on it may ask for a renewal (#614). */
    private var portalOrigin: String? = null

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
        portalOrigin = PortalRenewal.originOf(url)

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
        }
        // #681 — ground, progress arc and fail-safe ✕ from the brand tokens.
        val brand = OneloBrandChrome.from(OneloBrand.current)
        brand.paintGround(container, webView)
        brand.tint(progressBar)

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

        // #40 Phase 2 — the canonical close ✕ is now rendered by the HOSTED page.
        // This native "✕" (top-right, white, no branding) is only a FAIL-SAFE for the
        // pre-load / load-error window when the hosted ✕ isn't on screen yet. It starts
        // GONE and appears only after a ~3.5s grace without `onelo:ready` (#44 no-flash
        // timing) or on a WebView load error. Added AFTER the WebView + ProgressBar so
        // it sits on top and stays tappable. Hardware Back still works via
        // onBackPressed(). The ~12dp padding gives a comfortable 44dp+ touch target. The
        // activity uses an opaque (non-edge-to-edge) status bar (Theme.AppCompat
        // .NoActionBar, targetSdk 34), so the container already starts below the status
        // bar — a small edge margin is enough to avoid clipping.
        val density = resources.displayMetrics.density
        val touchPadding = (12 * density).toInt()
        val edgeMargin = (4 * density).toInt()
        closeButton = TextView(this).apply {
            text = "✕"
            brand.tintCloseGlyph(this)
            textSize = 22f // sp
            setPadding(touchPadding, touchPadding, touchPadding, touchPadding)
            isClickable = true
            isFocusable = true
            contentDescription = "Close"
            visibility = View.GONE // fail-safe only — armed by the grace timer below
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
        applyStatusBarInsetPadding(container)

        // #44 — arm the fail-safe ✕ only after the grace period. A fast load posts
        // `onelo:ready` first (→ cancels this), so the ✕ never flashes on screen.
        failsafeHandler.postDelayed(showFailsafeRunnable, FAILSAFE_GRACE_MS)

        webView.webViewClient = OneloCustomerPortalWebViewClient(
            callbackScheme = callbackScheme,
            onDone = { event -> deliverDone(event) },
            onCode = { code -> deliverCode(code) },
            onStoreDone = { reason -> deliverStoreDone(reason) },
            // #606 — "Return to app" on a spent store link: close, nothing finished.
            onStoreCancel = { deliverCancelled() },
            onExternalOpen = { uri -> openInBrowser(uri) },
            onPageFinished = { progressBar.visibility = View.GONE },
            // Hosted page failed to load → its canonical ✕ will never appear. Surface
            // the native fail-safe ✕ immediately so the user can still dismiss (mirrors
            // Swift's error → fail-safe ✕). Portal dismiss stays on `source=portal`.
            // (`onelo:ready` → hideFailsafe is delivered via the OneloAndroid JS bridge.)
            onError = { showFailsafe(); progressBar.visibility = View.GONE },
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
        webView.loadDataWithBaseURL(null, oneloPortalSkeletonHtml(OneloBrand.current), "text/html", "UTF-8", null)
        webView.loadUrl(url)
    }

    /** Open a URL (the Stripe card / external link) in the system browser. Best-effort. */
    private fun openInBrowser(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { /* no browser — best effort */ }
    }

    /** #44 — reveal the native fail-safe ✕ (and cancel the pending grace-timer show). */
    private fun showFailsafe() {
        failsafeHandler.removeCallbacks(showFailsafeRunnable)
        if (::closeButton.isInitialized) closeButton.visibility = View.VISIBLE
    }

    /** #40 Phase 2 — hide the native fail-safe ✕ once the hosted ✕ owns the affordance. */
    private fun hideFailsafe() {
        failsafeHandler.removeCallbacks(showFailsafeRunnable)
        if (::closeButton.isInitialized) closeButton.visibility = View.GONE
    }

    override fun onDestroy() {
        failsafeHandler.removeCallbacks(showFailsafeRunnable)
        renewScope.cancel()
        // Release the external-return hook, but only if it's still ours (a newer
        // OneloCustomerPortalActivity may have replaced it).
        if (!isChangingConfigurations) {
            pendingExternalReturn = null
            // The renew answer holds the OneloAuth closure; drop it with the portal.
            renewHandler = null
            // #606 — a launcher-less start (openUpgrade) learns how it ended here.
            if (isFinishing) {
                val handler = resultHandler
                resultHandler = null
                handler?.invoke(finalResultCode, finalResultData)
            }
        }
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
        finishWith(Activity.RESULT_OK, data)
    }

    /** The result for [resultHandler]; RESULT_CANCELED until something is delivered. */
    private var finalResultCode = Activity.RESULT_CANCELED
    private var finalResultData: Intent? = null

    private fun finishWith(resultCode: Int, data: Intent?) {
        finalResultCode = resultCode
        finalResultData = data
        setResult(resultCode, data)
        finish()
    }

    /** Deliver RESULT_OK for a code-less plan switch, with the store's reason (#659). */
    private fun deliverStoreDone(reason: String) {
        if (resultDelivered) return
        resultDelivered = true
        finishWith(Activity.RESULT_OK, Intent().putExtra(EXTRA_STORE_DONE, reason))
    }

    /** Deliver RESULT_OK with a "Change plan" card code and finish (SDK exchanges it). */
    private fun deliverCode(code: String) {
        if (resultDelivered) return
        resultDelivered = true
        finishWith(Activity.RESULT_OK, Intent().putExtra(EXTRA_CODE, code))
    }

    private fun deliverCancelled() {
        if (resultDelivered) return
        resultDelivered = true
        finishWith(Activity.RESULT_CANCELED, null)
    }

    /** JS bridge — the store's hand-back, `onelo:ready` and renew requests. */
    private inner class OneloBridge {
        /**
         * The store's postMessage hand-back (#627), decoded by the one decoder: a
         * purchase finishes with its code, a code-less plan switch is Done, and
         * "Return to app" on a spent link closes the portal.
         */
        @JavascriptInterface
        fun onHandBack(type: String, code: String, reason: String) {
            val handBack = classifyHandBackMessage(type, code, reason) ?: return
            runOnUiThread {
                when (handBack) {
                    is HandBack.Purchase -> deliverCode(handBack.code)
                    is HandBack.Switched -> deliverStoreDone(handBack.reason)
                    is HandBack.Error, is HandBack.PortalClosed -> deliverCancelled()
                }
            }
        }

        /**
         * #40 Phase 2 — hosted portal mounted (`onelo:ready`) → hide the native fail-safe
         * ✕ and cancel the pending grace-timer show, so the hosted page's canonical ✕
         * owns the affordance.
         */
        @JavascriptInterface
        fun onReady() {
            runOnUiThread { hideFailsafe() }
        }

        /**
         * #613 / #614 — the page's portal token died; mint a fresh portal with the
         * user's session and hand it back as a self-post the page trusts.
         */
        @JavascriptInterface
        fun renewPortal(requestId: String) {
            val handler = renewHandler ?: return
            renewScope.launch {
                // A fresh portal is a bearer credential: hand it only to a page
                // still on the portal's own origin, never to a page the WebView
                // was navigated to (a PSP redirect, an external link).
                if (!::webView.isInitialized || PortalRenewal.originOf(webView.url) != portalOrigin) return@launch
                val reply = try { handler(requestId) } catch (_: Exception) { PortalRenewal.failed(requestId, "renew_failed") }
                if (::webView.isInitialized && !isDestroyed) {
                    webView.evaluateJavascript(PortalRenewal.replyScript(reply), null)
                }
            }
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
    private val onStoreDone: (reason: String) -> Unit,
    private val onStoreCancel: () -> Unit,
    private val onExternalOpen: (uri: Uri) -> Unit,
    private val onPageFinished: () -> Unit,
    private val onError: () -> Unit,
) : WebViewClient() {

    override fun onPageFinished(view: WebView?, url: String?) {
        onPageFinished()
        // Inject the store's postMessage relay (same protocol as OneloAuthActivity) so
        // a "Change plan" card that reports success via `onelo:code` reaches us, PLUS
        // the #40 Phase 2 `onelo:ready` signal that hides the native fail-safe ✕.
        view?.evaluateJavascript(
            """
            (function() {
                if (window.__oneloPortalRelay) return;
                window.__oneloPortalRelay = true;
                ${PortalRenewal.RELAY_SNIPPET}
                ${HAND_BACK_RELAY_SNIPPET}
                window.addEventListener('message', function(e) {
                    try {
                        var data = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
                        // #614 — only the page's own self-post may ask for a renewal.
                        if (e.source === window && oneloRenew(data)) return;
                        // #627 — the store's hand-back, decoded natively.
                        if (oneloHandBack(data)) return;
                        if (data && data.type === 'onelo:ready') {
                            OneloAndroid.onReady();
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
     *     `<scheme>://callback?source=store&event=…` → a plan switch with no checkout
     *     finished (#599) → Done.
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
            // The one Store Hand-back decoder (#615): the portal's Done, a
            // "Change plan" card completed in-WebView (#592), or a plan switch
            // with no checkout (#599) → Done (RESULT_OK), never an unknown-scheme load.
            when (val handBack = classifyHandBack(
                uri.getQueryParameter("source"), uri.getQueryParameter("event"),
                uri.getQueryParameter("code"), uri.getQueryParameter("error"),
            )) {
                is HandBack.PortalClosed -> { onDone(handBack.event); return true }
                is HandBack.Purchase -> { onCode(handBack.code); return true }
                is HandBack.Switched -> { onStoreDone(handBack.reason); return true }
                is HandBack.Error -> { onStoreCancel(); return true }
                null -> {}
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

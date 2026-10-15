package com.onelo.android

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import com.onelo.android.internal.paintGround
import com.onelo.android.internal.tint
import com.onelo.android.internal.tintCloseGlyph
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.onelo.android.internal.applyStatusBarInsetPadding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OneloFeedbackActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INITIATE_URL = "onelo_feedback_initiate_url"
        const val EXTRA_USER_ID = "onelo_feedback_user_id"
        // HashMap<String,String> of security headers (X-Bundle-Id / X-Integrity-Token /
        // instance id) captured by OneloFeedback + applied to the /initiate request here.
        const val EXTRA_SECURITY_HEADERS = "onelo_feedback_security_headers"


        internal const val RELAY_JS = """
(function() {
    // Once per document: onPageFinished can fire more than once for one page.
    if (window.__oneloFeedbackRelay) return;
    window.__oneloFeedbackRelay = true;
    // #622 — the form's reporter token `ft` expired; it asks (a self-post, or a
    // request parked before this relay ran) for a fresh one.
    function oneloFeedbackRenew(d) {
        if (d && d.type === 'onelo:renew_request' && d.kind === 'feedback' && typeof d.request_id === 'string') {
            OneloFeedback.renew(d.request_id);
            return true;
        }
        return false;
    }
    if (window.__oneloRenewPending) oneloFeedbackRenew(window.__oneloRenewPending);
    window.addEventListener('message', function(e) {
        try {
            var data = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
            if (e.source === window && oneloFeedbackRenew(data)) return;
            if (data && data.type === 'onelo:feedback_submitted') {
                OneloFeedback.onSubmitted();
            } else if (data && data.type === 'onelo:feedback_close') {
                // #40 Phase 2 — the canonical hosted ✕ (rendered by the hosted page)
                // was tapped. Same native teardown as a submit (dismiss the sheet).
                OneloFeedback.onClose();
            } else if (data && data.type === 'onelo:ready') {
                // Hosted page mounted → hide the native fail-safe ✕ (the hosted ✕
                // now owns the affordance).
                OneloFeedback.onReady();
            }
        } catch(err) {}
    });
})();"""

        // #44 — grace period before the native fail-safe ✕ appears. A normal (fast)
        // load posts `onelo:ready` well before this elapses, so the ✕ never flashes;
        // it shows only if the hosted page hasn't mounted after the grace, or on a
        // WebView load error. Mirrors Swift's 3.5s failsafe timer.
        private const val FAILSAFE_GRACE_MS = 3_500L
    }

    private lateinit var webView: WebView
    // #40 Phase 2 / #44 — native fail-safe ✕. Starts GONE; shown only after the grace
    // period (or on load error) if the hosted page hasn't posted `onelo:ready`.
    private lateinit var closeButton: TextView
    private val failsafeHandler = Handler(Looper.getMainLooper())
    private val showFailsafeRunnable = Runnable {
        if (::closeButton.isInitialized) closeButton.visibility = View.VISIBLE
    }

    // #622 — what renewing the form's `ft` needs: the same initiate request.
    private var initiateUrl: String? = null
    private var feedbackUserId: String? = null
    private var securityHeaders: HashMap<*, *>? = null
    /** The hosted form's origin — only a page on it may ask for a renewal. */
    private var hostedOrigin: String? = null
    private val renewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initiateUrl = intent.getStringExtra(EXTRA_INITIATE_URL)
        feedbackUserId = intent.getStringExtra(EXTRA_USER_ID)
        @Suppress("DEPRECATION")
        securityHeaders = intent.getSerializableExtra(EXTRA_SECURITY_HEADERS) as? HashMap<*, *>
        this.initiateUrl = initiateUrl
        if (initiateUrl.isNullOrBlank()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val container = FrameLayout(this)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }
        // #681 — ground and fail-safe ✕ from the brand tokens.
        val brand = OneloBrandChrome.from(OneloBrand.current)
        brand.paintGround(container, webView)
        container.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // #40 Phase 2 — the canonical close ✕ is now rendered by the HOSTED page.
        // This native "✕" (top-right, white, no branding) is only a FAIL-SAFE for the
        // pre-load / load-error window when the hosted ✕ isn't on screen yet. It starts
        // GONE and appears only after a ~3.5s grace without `onelo:ready` (#44 no-flash
        // timing) or on a WebView load error. Added AFTER the WebView so it sits on top
        // and stays tappable. Hardware Back still works via onBackPressed(). The ~12dp
        // padding gives a comfortable 44dp+ touch target. The activity uses an opaque
        // (non-edge-to-edge) status bar (Theme.AppCompat.NoActionBar, targetSdk 34), so
        // the container already starts below the status bar — a small edge margin is
        // enough to avoid clipping.
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
            setOnClickListener {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
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

        webView.addJavascriptInterface(FeedbackBridge(), "OneloFeedback")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(RELAY_JS, null)
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    // Hosted page failed to load → its canonical ✕ will never appear.
                    // Surface the native fail-safe ✕ immediately so the user can still
                    // dismiss (mirrors Swift's error → fail-safe ✕). Don't auto-finish.
                    failsafeHandler.removeCallbacks(showFailsafeRunnable)
                    closeButton.visibility = View.VISIBLE
                }
            }
        }

        // 1. Show skeleton immediately
        webView.loadDataWithBaseURL(null, oneloFeedbackSkeletonHtml(OneloBrand.current), "text/html", "UTF-8", null)

        // 2. Fetch hosted URL in background, navigate when ready
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val hostedUrl = fetchHostedUrl(initiateUrl)
                withContext(Dispatchers.Main) {
                    hostedOrigin = PortalRenewal.originOf(hostedUrl)
                    if (!isFinishing) webView.loadUrl(hostedUrl)
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                }
            }
        }
    }

    /** GET /feedback/initiate → hosted_url, carrying the signed-in user's token (#820). */
    private suspend fun fetchHostedUrl(initiateUrl: String): String =
        FeedbackInitiate.hostedUrl(
            initiateUrl = initiateUrl,
            userId = feedbackUserId,
            securityHeaders = securityHeaders
                ?.mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }
                ?.toMap()
                .orEmpty(),
            userToken = FeedbackInitiate.userToken,
        )

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else { setResult(Activity.RESULT_CANCELED); finish() }
    }

    override fun onDestroy() {
        failsafeHandler.removeCallbacks(showFailsafeRunnable)
        renewScope.cancel()
        super.onDestroy()
    }

    private inner class FeedbackBridge {
        @JavascriptInterface
        fun onSubmitted() {
            runOnUiThread { setResult(Activity.RESULT_OK); finish() }
        }

        /**
         * #40 Phase 2 — the canonical hosted ✕ was tapped (user cancelled). Same native
         * teardown as the native fail-safe ✕: dismiss with RESULT_CANCELED (nothing was
         * submitted). Twin of [onSubmitted].
         */
        @JavascriptInterface
        fun onClose() {
            runOnUiThread { setResult(Activity.RESULT_CANCELED); finish() }
        }

        /**
         * Hosted page mounted (`onelo:ready`) → hide the native fail-safe ✕ and cancel
         * the pending grace-timer show, so only the hosted page's canonical ✕ remains.
         */
        /**
         * #622 — the form's reporter token `ft` (30 minutes) expired before submit.
         * Initiate the same request again and hand the fresh hosted URL back as a
         * self-post the page trusts; the page submits with its new `ft`.
         */
        @JavascriptInterface
        fun renew(requestId: String) {
            val url = initiateUrl ?: return
            renewScope.launch {
                // Answer only a page still on the hosted form's origin.
                if (!::webView.isInitialized || PortalRenewal.originOf(webView.url) != hostedOrigin) return@launch
                val reply = try {
                    PortalRenewal.renewed(requestId, withContext(Dispatchers.IO) { fetchHostedUrl(url) })
                } catch (_: Exception) {
                    PortalRenewal.failed(requestId, "renew_failed")
                }
                if (::webView.isInitialized && !isDestroyed) {
                    webView.evaluateJavascript(PortalRenewal.replyScript(reply), null)
                }
            }
        }

        @JavascriptInterface
        fun onReady() {
            runOnUiThread {
                failsafeHandler.removeCallbacks(showFailsafeRunnable)
                closeButton.visibility = View.GONE
            }
        }
    }
}

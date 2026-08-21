package com.onelo.android

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class OneloFeedbackActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INITIATE_URL = "onelo_feedback_initiate_url"
        const val EXTRA_USER_ID = "onelo_feedback_user_id"
        // HashMap<String,String> of security headers (X-Bundle-Id / X-Integrity-Token /
        // instance id) captured by OneloFeedback + applied to the /initiate request here.
        const val EXTRA_SECURITY_HEADERS = "onelo_feedback_security_headers"

        private const val SKELETON_HTML = """<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:#111;font-family:-apple-system,sans-serif;padding:40px 36px 32px;overflow:hidden}
@keyframes shimmer{0%{background-position:-600px 0}100%{background-position:600px 0}}
.sk{border-radius:10px;background:linear-gradient(90deg,#1e1e1e 25%,#2a2a2a 50%,#1e1e1e 75%);background-size:600px 100%;animation:shimmer 1.4s infinite linear}
.icon{width:64px;height:64px;border-radius:14px;margin:0 auto 16px}
.title{width:220px;height:22px;margin:0 auto 40px;border-radius:6px}
.cards{display:flex;gap:12px;margin-bottom:32px}
.card{flex:1;height:76px;border-radius:12px}
.label{width:60px;height:13px;border-radius:4px;margin-bottom:8px}
.input{width:100%;height:44px;border-radius:10px;margin-bottom:24px}
.textarea{width:100%;height:110px;border-radius:10px;margin-bottom:32px}
.btn{width:100%;height:48px;border-radius:12px}
</style></head><body>
<div class="sk icon"></div><div class="sk title"></div>
<div class="cards"><div class="sk card"></div><div class="sk card"></div><div class="sk card"></div></div>
<div class="sk label"></div><div class="sk input"></div>
<div class="sk label"></div><div class="sk textarea"></div>
<div class="sk btn"></div>
</body></html>"""

        private const val RELAY_JS = """
(function() {
    window.addEventListener('message', function(e) {
        try {
            var data = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initiateUrl = intent.getStringExtra(EXTRA_INITIATE_URL)
        val feedbackUserId = intent.getStringExtra(EXTRA_USER_ID)
        @Suppress("DEPRECATION")
        val securityHeaders = intent.getSerializableExtra(EXTRA_SECURITY_HEADERS) as? HashMap<*, *>
        if (initiateUrl.isNullOrBlank()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val container = FrameLayout(this)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(android.graphics.Color.parseColor("#111111"))
        }
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
            setTextColor(android.graphics.Color.WHITE)
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
        webView.loadDataWithBaseURL(null, SKELETON_HTML, "text/html", "UTF-8", null)

        // 2. Fetch hosted URL in background, navigate when ready
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // userId travels as a HEADER (X-Onelo-User-Id), not a query param,
                // so it never appears in the access-logged initiate URL. readText()
                // can't set headers, so use an explicit HttpURLConnection.
                val conn = (URL(initiateUrl).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false  // see HttpClient.get() — headers follow the redirect
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    feedbackUserId?.let { setRequestProperty("X-Onelo-User-Id", it) }
                    // X-Bundle-Id (+ X-Integrity-Token / instance id) — without it the
                    // backend security gate 403s a live app with a registered bundle.
                    securityHeaders?.forEach { (k, v) ->
                        if (k is String && v is String) setRequestProperty(k, v)
                    }
                }
                val json = try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
                val hostedUrl = JSONObject(json).getString("hosted_url")
                withContext(Dispatchers.Main) {
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

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else { setResult(Activity.RESULT_CANCELED); finish() }
    }

    override fun onDestroy() {
        failsafeHandler.removeCallbacks(showFailsafeRunnable)
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
        @JavascriptInterface
        fun onReady() {
            runOnUiThread {
                failsafeHandler.removeCallbacks(showFailsafeRunnable)
                closeButton.visibility = View.GONE
            }
        }
    }
}

package com.onelo.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import com.onelo.android.internal.applyStatusBarInsetPadding

/**
 * Hosts the Onelo-hosted legal-consent gate page inside a full-screen WebView.
 *
 * Flow:
 *  1. [OneloConsent.presentGateIfNeeded] finds an outstanding BLOCKING legal
 *     document and launches this Activity (via the host-registered
 *     [androidx.activity.result.ActivityResultLauncher]) with the document's
 *     `consent_url` in [EXTRA_URL].
 *  2. The hosted gate page loads (document text + Accept/Decline buttons).
 *  3. On Accept/Decline the page posts
 *     `postMessage({type:'onelo:consent', action:'accept'|'decline'})`; the
 *     relay injected in [WebViewClient.onPageFinished] forwards `action` to
 *     [OneloBridge.onConsent], which returns it to the launcher via
 *     [EXTRA_ACTION] with `RESULT_OK`.
 *
 * Unlike [OneloAuthActivity] this is a HARD block: [onBackPressed] is a no-op
 * (Back is swallowed) and there is NO Cancel affordance. The only exits are
 * Accept, Decline, or a main-frame load error (fail-open → `RESULT_CANCELED`).
 * This mirrors Swift `OneloAuthView`'s non-dismissible consent cover and the
 * Electron gate's no-dismiss close veto.
 */
class OneloConsentActivity : AppCompatActivity() {

    companion object {
        /** Intent extra: the gate `consent_url` to load. */
        const val EXTRA_URL = "onelo_consent_url"
        /**
         * Intent extra in the result: the `action` from the hosted page's
         * `onelo:consent` postMessage ("accept" | "decline" | forward-compat
         * unknown). Present only with `RESULT_OK`.
         */
        const val EXTRA_ACTION = "onelo_consent_action"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var resultDelivered = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
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
        }
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
        setContentView(container)
        applyStatusBarInsetPadding(container)

        webView.addJavascriptInterface(OneloBridge(), "OneloAndroid")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                // Forward the gate page's onelo:consent postMessage into the
                // native bridge — same relay idiom as OneloAuthActivity.
                view?.evaluateJavascript(
                    """
                    (function() {
                        window.addEventListener('message', function(e) {
                            try {
                                var data = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
                                if (data && data.type === 'onelo:consent' && data.action) {
                                    OneloAndroid.onConsent(data.action);
                                }
                            } catch(err) {}
                        });
                    })();
                    """.trimIndent(),
                    null,
                )
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                // Fail-open: a load failure must not trap the user behind a blank,
                // non-dismissible gate. Deliver cancelled — OneloConsent leaves the
                // session intact so the next trigger re-presents.
                if (request?.isForMainFrame == true) {
                    deliverError()
                }
            }
        }

        webView.loadUrl(url)
    }

    /**
     * HARD block — the consent gate cannot be dismissed with Back. The only
     * exits are Accept / Decline (via the postMessage relay) or a load error.
     * Deliberately does NOT call super (no-op), mirroring Swift's non-dismissible
     * consent cover and Electron's close veto.
     */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // no-op — swallow Back.
    }

    private fun deliverAction(action: String) {
        if (resultDelivered) return
        resultDelivered = true
        val data = Intent().putExtra(EXTRA_ACTION, action)
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private fun deliverError() {
        if (resultDelivered) return
        resultDelivered = true
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private inner class OneloBridge {
        @JavascriptInterface
        fun onConsent(action: String) {
            runOnUiThread { deliverAction(action) }
        }
    }
}

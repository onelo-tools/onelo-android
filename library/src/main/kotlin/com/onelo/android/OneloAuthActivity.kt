package com.onelo.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Message
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity

class OneloAuthActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "onelo_auth_url"
        const val EXTRA_CODE = "onelo_code"
        /**
         * Intent extra: the deep-link callback scheme ([OneloConfig.callbackScheme]).
         * The hosted sign-in page signals success by REDIRECTING to
         * `<scheme>://callback?code=…` (the backend's canonical contract — see
         * hosted_auth.py). The WebView must intercept that redirect, since the scheme
         * isn't a real network scheme; otherwise it errors ERR_UNKNOWN_URL_SCHEME and
         * the code is lost.
         */
        const val EXTRA_SCHEME = "onelo_callback_scheme"

        /**
         * Set while an [OneloAuthActivity] is on-screen. When the store's Stripe CARD
         * page (a `window.open`) is opened in the SYSTEM BROWSER by [onCreateWindow],
         * its `<scheme>://callback?code=` return deep-links back into the app — NOT
         * into this WebView. [Onelo.handleRedirect] forwards that code here so the
         * Activity finishes with it (reusing the normal result→exchange path). This is
         * the Android equivalent of Swift's `.onOpenURL → OneloAuthView` and Flutter's
         * `app_links → OneloStoreView.onCheckoutReturn`.
         */
        @Volatile
        @JvmStatic
        var pendingExternalReturn: ((String) -> Unit)? = null
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var resultDelivered = false
    // Default matches OneloConfig.callbackScheme's default so a missing extra still works.
    private var callbackScheme: String = "oneloandroid"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        intent.getStringExtra(EXTRA_SCHEME)?.takeIf { it.isNotBlank() }?.let { callbackScheme = it }

        // Build layout programmatically — no XML resources needed in the library
        val container = FrameLayout(this)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Required for [onCreateWindow] to fire when the hosted page window.open's
            // the Stripe card page — so we can divert that (and only that) to the
            // system browser instead of loading card entry in this embedded WebView.
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
        }

        // Any code that reaches the app while THIS store WebView is open (the card
        // completed in the external browser) should finish this Activity with it.
        pendingExternalReturn = { code -> runOnUiThread { deliverCode(code) } }
        container.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        container.addView(progressBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        setContentView(container)

        webView.addJavascriptInterface(OneloBridge(), "OneloAndroid")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val uri = request?.url ?: return false
                // The hosted page signals success by REDIRECTING to
                // `<callbackScheme>://callback?code=…` — the SAME contract Swift's
                // ASWebAuthenticationSession and the RN WebView (onShouldStartLoad…)
                // consume. It's not a real network scheme, so intercept it here,
                // extract the code, and finish. Without this the WebView errors with
                // ERR_UNKNOWN_URL_SCHEME and the code is lost (session stays null).
                // The postMessage relay below only covers the web-popup origin case.
                if (uri.scheme.equals(callbackScheme, ignoreCase = true) &&
                    uri.host.equals("callback", ignoreCase = true)
                ) {
                    val code = uri.getQueryParameter("code")
                    if (!code.isNullOrBlank()) deliverCode(code) else deliverCancelled()
                    return true // consume — never let the WebView load the unknown scheme
                }
                // THE CARD PAGE → system browser. It's reached via a TOP-LEVEL nav
                // (window.location.href), not window.open, so onCreateWindow never
                // fires — match by URL. Onelo-branded card (/store/pay, /paywall, same
                // host) or Stripe Hosted Checkout (*.stripe.com). Plan selection
                // (/store/hosted) + auth stay IN-APP.
                val scheme = uri.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") {
                    val host = uri.host?.lowercase() ?: ""
                    val path = uri.path ?: ""
                    if (host.contains("stripe.com") ||
                        path.startsWith("/store/pay") || path.startsWith("/paywall")
                    ) {
                        try {
                            startActivity(
                                Intent(Intent.ACTION_VIEW, uri)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) { /* no browser — best effort */ }
                        return true
                    }
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                // Inject postMessage listener — same protocol as React Native SDK
                view?.evaluateJavascript("""
                    (function() {
                        window.addEventListener('message', function(e) {
                            try {
                                var data = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
                                if (data && data.type === 'onelo:code' && data.code) {
                                    OneloAndroid.onCode(data.code);
                                } else if (data && data.type === 'onelo:cancelled') {
                                    OneloAndroid.onCancelled();
                                }
                            } catch(err) {}
                        });
                    })();
                """.trimIndent(), null)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    deliverCancelled()
                }
            }
        }

        // = Swift `createWebViewWith` / Electron `setWindowOpenHandler`. The hosted
        // store `window.open`s the Stripe CARD page; open THAT (and only that) in the
        // SYSTEM BROWSER — real domain, saved cards, reliable 3-D Secure — instead of
        // in this embedded WebView. Android doesn't hand the target URL to
        // onCreateWindow directly, so we capture it via a throwaway WebView whose
        // shouldOverrideUrlLoading fires with the window.open destination.
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
                        request?.url?.let { target ->
                            try {
                                startActivity(
                                    Intent(Intent.ACTION_VIEW, target)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (_: Exception) { /* no browser — best effort */ }
                        }
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

        webView.loadUrl(url)
    }

    override fun onDestroy() {
        // Release the store external-return hook, but only if it's still ours (a
        // newer OneloAuthActivity may have replaced it).
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

    private fun deliverCode(code: String) {
        if (resultDelivered) return
        resultDelivered = true
        val data = Intent().putExtra(EXTRA_CODE, code)
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private fun deliverCancelled() {
        if (resultDelivered) return
        resultDelivered = true
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private inner class OneloBridge {
        @JavascriptInterface
        fun onCode(code: String) {
            runOnUiThread { deliverCode(code) }
        }

        @JavascriptInterface
        fun onCancelled() {
            runOnUiThread { deliverCancelled() }
        }
    }
}

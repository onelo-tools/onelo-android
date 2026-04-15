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

class OneloAuthActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "onelo_auth_url"
        const val EXTRA_CODE = "onelo_code"
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

        // Build layout programmatically — no XML resources needed in the library
        val container = FrameLayout(this)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }
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

        webView.loadUrl(url)
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

package com.onelo.android

import android.annotation.SuppressLint
import android.app.Activity
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

class OneloFeedbackActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "onelo_feedback_url"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

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

        webView.addJavascriptInterface(FeedbackBridge(), "OneloFeedback")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                view?.evaluateJavascript("""
                    (function() {
                        window.addEventListener('message', function(e) {
                            try {
                                var data = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
                                if (data && data.type === 'onelo:feedback_submitted') {
                                    OneloFeedback.onSubmitted();
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
                    setResult(Activity.RESULT_CANCELED)
                    finish()
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
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private inner class FeedbackBridge {
        @JavascriptInterface
        fun onSubmitted() {
            runOnUiThread {
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
    }
}

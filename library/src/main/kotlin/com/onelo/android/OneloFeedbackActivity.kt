package com.onelo.android

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class OneloFeedbackActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INITIATE_URL = "onelo_feedback_initiate_url"

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
            }
        } catch(err) {}
    });
})();"""
    }

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initiateUrl = intent.getStringExtra(EXTRA_INITIATE_URL)
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
        setContentView(container)

        webView.addJavascriptInterface(FeedbackBridge(), "OneloFeedback")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(RELAY_JS, null)
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                }
            }
        }

        // 1. Show skeleton immediately
        webView.loadDataWithBaseURL(null, SKELETON_HTML, "text/html", "UTF-8", null)

        // 2. Fetch hosted URL in background, navigate when ready
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = URL(initiateUrl).readText()
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

    private inner class FeedbackBridge {
        @JavascriptInterface
        fun onSubmitted() {
            runOnUiThread { setResult(Activity.RESULT_OK); finish() }
        }
    }
}

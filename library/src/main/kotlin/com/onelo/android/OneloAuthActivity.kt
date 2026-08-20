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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * True when [url] is an OAuth navigation that MUST leave the embedded WebView.
 *
 * Two shapes qualify:
 *  - the Onelo init route `/api/sdk/auth/oauth/{google|github|apple}`, which the
 *    hosted page navigates to and which 302s onward to the provider. Catching it
 *    here means the whole redirect chain runs in the browser, preserving the
 *    user's provider SSO cookies.
 *  - the provider authorization hosts themselves, as a backstop for any path we
 *    did not anticipate.
 *
 * Deliberately plain-Kotlin (java.net.URI, not android.net.Uri) so the navigation
 * policy is unit-testable on the JVM without Robolectric. Mirrors Electron's
 * `isOAuthProviderUrl` and RN's `isOAuthNavigation`.
 */
/**
 * The lowercased host of [url] if it's `https://` and non-empty, else null.
 *
 * Deliberately plain-Kotlin (java.net.URI, not android.net.Uri) so origin matching
 * is unit-testable on the JVM, same reasoning as [isOneloOAuthUrl]. Used to check a
 * `gate` deep-link value against a remembered hosted origin before it's loaded in a
 * WebView — an unchecked value here is a phishing page wearing the app's own
 * sign-in window, since ANY app on the device can fire our callback scheme.
 */
internal fun oneloHostedOriginHost(url: String): String? {
    val parsed = try { java.net.URI(url) } catch (_: Exception) { return null }
    if (parsed.scheme?.lowercase() != "https") return null
    return parsed.host?.lowercase()?.takeIf { it.isNotEmpty() }
}

internal fun isOneloOAuthUrl(url: String): Boolean {
    val parsed = try { java.net.URI(url) } catch (_: Exception) { return false }
    val scheme = parsed.scheme?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https") return false
    val host = parsed.host?.lowercase() ?: ""
    if (host == "accounts.google.com" || host == "github.com" || host == "appleid.apple.com") {
        return true
    }
    val path = parsed.path?.lowercase() ?: ""
    // Exact segment match — `/api/sdk/auth/oauth/hosted-init` is a token hand-off
    // consumed server-side, NOT a navigation to divert.
    return path == "/api/sdk/auth/oauth/google" ||
        path == "/api/sdk/auth/oauth/github" ||
        path == "/api/sdk/auth/oauth/apple"
}

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

        /**
         * Set while an [OneloAuthActivity] is on-screen showing "Check your inbox".
         * A magic link opens in the user's mail app, which hands the click to the
         * SYSTEM BROWSER, not this WebView — the Access Gate refusal (`?gate=…`,
         * no code) that browser tab produces then arrives back here purely via the
         * OS deep link ([Onelo.handleRedirect]), same as [pendingExternalReturn]
         * does for a returning `code`. Without this hook the refusal had nowhere to
         * land: [Onelo.handleRedirect] found no code, no pending OAuth, and the
         * refusal was silently dropped while this Activity sat on "Check your inbox"
         * forever. Parity with Swift's `pendingGateUrl` / Electron's gate handling.
         */
        @Volatile
        @JvmStatic
        var pendingGateReturn: ((String) -> Unit)? = null

        /**
         * Supplies a FRESH hosted URL when the page hands control back saying its
         * addressing token is spent (`?error=invalid_token`). Set by
         * [OneloAuth.loadAuthView] before launching; returns null if the flow can
         * no longer be resolved.
         *
         * Same static-hook idiom as [pendingExternalReturn] — the Activity is
         * started by the framework and has no reference to the SDK instance.
         *
         * Why it exists: without it a code-less callback took the plain cancel
         * path, so "Use a different account" on the no-plan page (which signs the
         * user out and returns `?error=invalid_token`) simply CLOSED the auth
         * window on them. Parity with Swift's `onSessionExpired`.
         */
        @Volatile
        @JvmStatic
        var pendingFreshUrl: (suspend () -> String?)? = null

        /**
         * Is this `?error=` value the hosted page saying its addressing token is
         * spent? Internal (not private) so the rule is tested directly rather
         * than re-implemented in a test — a test that re-implements the rule
         * stays green after the behaviour is deleted.
         *
         * NOT a catch-all: a genuine failure must still take the cancel path.
         * Reloading on every error would loop forever on one.
         */
        @JvmStatic
        internal fun isExpiredAuthError(err: String?): Boolean =
            err == "invalid_token" || err == "expired_token" || err == "token_expired"
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
        // A gate refusal that reaches the app while THIS "Check your inbox" screen
        // is open — load it into the SAME WebView instead of leaving the screen
        // stuck. The URL was already validated by Onelo.handleRedirect before this
        // hook is invoked (isOneloHostedSurface), so no re-check is needed here.
        pendingGateReturn = { url -> runOnUiThread { if (!isFinishing) webView.loadUrl(url) } }
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
                    val error = uri.getQueryParameter("error")
                    val expired = isExpiredAuthError(error)
                    val gate = uri.getQueryParameter("gate")
                    when {
                        !code.isNullOrBlank() -> deliverCode(code)
                        expired -> reloadFreshHostedUrl()
                        // A refusal reached IN this WebView (no browser hand-off
                        // involved) — load it in place, same surface. This value
                        // came from a navigation inside a page we already loaded
                        // from our own hosted origin, unlike the deep-link path in
                        // Onelo.handleRedirect, which must validate it first.
                        !gate.isNullOrBlank() -> webView.loadUrl(gate)
                        else -> deliverCancelled()
                    }
                    return true // consume — never let the WebView load the unknown scheme
                }
                // SOCIAL SIGN-IN → system browser. Google and Apple sniff the
                // User-Agent and refuse embedded WebViews outright ("this browser
                // is not secure" / disallowed_useragent), and GitHub leaves the
                // user without their saved session, so re-entering creds + 2FA
                // every time. Without this branch the WebView happily loaded
                // accounts.google.com and the sign-in dead-ended. Mirrors Flutter
                // `_providerFromUrl`, RN `isOAuthNavigation` and Electron
                // `isOAuthProviderUrl` — Android was the only UI SDK missing it.
                //
                // We divert the whole chain to the browser rather than just the
                // provider page, so the provider sees a real browser from the
                // first hop. The flow returns to the app as `<scheme>://callback
                // ?code=…`, which the host app forwards through
                // Onelo.handleRedirect → pendingExternalReturn → deliverCode —
                // the exact path the external Stripe card page already uses.
                if (isOneloOAuthUrl(uri.toString())) {
                    try {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, uri)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) { /* no browser — best effort */ }
                    return true
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
        if (!isChangingConfigurations) {
            pendingExternalReturn = null
            pendingGateReturn = null
            pendingFreshUrl = null
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

    /**
     * The addressing token is spent — re-resolve the flow and load the URL it
     * gives us (now a clean sign-in page, since the session was just revoked).
     * Falls back to cancelling if the flow can't be resolved, which is the old
     * behaviour and still better than a frozen WebView.
     */
    private fun reloadFreshHostedUrl() {
        val supplier = pendingFreshUrl
        if (supplier == null) { deliverCancelled(); return }
        lifecycleScope.launch {
            val fresh = try {
                supplier()
            } catch (e: Exception) {
                // Never swallow silently — a dead reload is the difference between
                // "landed on sign-in" and "window vanished".
                android.util.Log.w("Onelo", "reload after expiry failed: ${e.message}")
                null
            }
            if (fresh != null && !isFinishing) webView.loadUrl(fresh) else deliverCancelled()
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

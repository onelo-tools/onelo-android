package com.onelo.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.onelo.android.internal.HttpClient
import com.onelo.android.internal.Pkce
import com.onelo.android.internal.SecureStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder

private const val SDK_VERSION = "1.0.0-staging"

class OneloAuth internal constructor(
    private val config: OneloConfig,
    context: Context,
) {
    private val appContext: Context = context.applicationContext
    private val storage = SecureStorage(appContext)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _authStateFlow = MutableSharedFlow<OneloSession?>(replay = 1)
    internal val authStateFlow: SharedFlow<OneloSession?> = _authStateFlow.asSharedFlow()

    private var pkceVerifier: String? = null
    private val initDeferred = CompletableDeferred<Unit>()

    var isReady = false
        private set
    var isRevoked = false
        private set
    var allowCustomBranding = false
        private set
    var appName: String = "App"
        private set
    var appLogoUrl: String? = null
        private set

    init {
        require(config.apiUrl.isNotBlank()) { "[Onelo] apiUrl is required" }
        require(config.publishableKey.isNotBlank()) { "[Onelo] publishableKey is required" }
        scope.launch { initialize() }
    }

    private suspend fun initialize() {
        try {
            val verifier = Pkce.generateCodeVerifier()
            pkceVerifier = verifier
            val challenge = Pkce.generateCodeChallenge(verifier)
            val url = "${config.apiUrl}/api/sdk/config?key=${enc(config.publishableKey)}&code_challenge=${enc(challenge)}"
            val resp = HttpClient.get(url, mapOf("X-SDK-Version" to SDK_VERSION))
            if (resp.status == 401 || resp.status == 404) {
                isRevoked = true
                throw OneloError.invalidKey("Server rejected the key")
            }
            if (resp.status != 200) throw OneloError.server("Config request failed: HTTP ${resp.status}")
            allowCustomBranding = resp.body["allow_custom_branding"] as? Boolean ?: false
            (resp.body["app_name"] as? String)?.let { appName = it }
            appLogoUrl = resp.body["app_logo_url"] as? String
            isReady = true
            initDeferred.complete(Unit)
        } catch (e: OneloError) {
            if (e.code == OneloError.Code.INVALID_PUBLISHABLE_KEY) isRevoked = true
            initDeferred.complete(Unit)
        } catch (_: Exception) {
            initDeferred.complete(Unit)
        }
    }

    /** Suspends until SDK initialization is complete. */
    suspend fun whenReady() {
        initDeferred.await()
    }

    // ── Hosted flow ──────────────────────────────────────────────────────────────

    /**
     * Register the ActivityResultLauncher needed for hosted auth.
     * Call this in Activity/Fragment onCreate, before any UI interaction.
     */
    fun registerLauncher(
        activity: ComponentActivity,
        onResult: (OneloSession?) -> Unit,
    ): ActivityResultLauncher<Intent> {
        return activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val code = result.data?.getStringExtra(OneloAuthActivity.EXTRA_CODE)
                if (code != null) {
                    scope.launch {
                        try {
                            val session = exchangeCode(code)
                            onResult(session)
                        } catch (_: Exception) {
                            onResult(null)
                        }
                    }
                } else {
                    onResult(null)
                }
            } else {
                onResult(null)
            }
        }
    }

    /**
     * Open the hosted sign-in page in OneloAuthActivity.
     * The result is delivered to the launcher registered via [registerLauncher].
     */
    suspend fun loadAuthView(launcher: ActivityResultLauncher<Intent>) {
        initDeferred.await()
        if (isRevoked) throw OneloError.invalidKey("Application key has been revoked")
        val resp = HttpClient.get(
            "${config.apiUrl}/api/sdk/auth/initiate?key=${enc(config.publishableKey)}&callback_scheme=oneloandroid",
            mapOf("X-SDK-Version" to SDK_VERSION)
        )
        if (resp.status != 200) throw OneloError.server("Failed to initiate hosted auth flow")
        val hostedUrl = resp.body["hosted_url"] as? String
            ?: throw OneloError.server("Invalid initiate response")
        (resp.body["app_name"] as? String)?.let { appName = it }
        (resp.body["app_logo_url"] as? String)?.let { appLogoUrl = it }

        launcher.launch(Intent(appContext, OneloAuthActivity::class.java)
            .putExtra(OneloAuthActivity.EXTRA_URL, hostedUrl))
    }

    internal suspend fun exchangeCode(code: String): OneloSession {
        val resp = HttpClient.post(
            "${config.apiUrl}/api/sdk/auth/hosted-callback",
            mapOf("publishableKey" to config.publishableKey, "code" to code),
            mapOf("X-SDK-Version" to SDK_VERSION)
        )
        if (resp.status != 200) throw OneloError.server("Hosted callback failed")
        return mapSession(resp.body).also { saveSession(it) }
    }

    // ── Custom UI (paid plans only) ──────────────────────────────────────────────

    suspend fun signIn(email: String, password: String): OneloSession {
        initDeferred.await()
        if (!allowCustomBranding) throw OneloError.planRequired()
        if (pkceVerifier == null) pkceVerifier = Pkce.generateCodeVerifier()
        val resp = HttpClient.post(
            "${config.apiUrl}/api/sdk/auth/signin",
            mapOf(
                "email" to email,
                "password" to password,
                "publishableKey" to config.publishableKey,
                "code_verifier" to pkceVerifier,
            ),
            mapOf("X-SDK-Version" to SDK_VERSION)
        )
        HttpClient.checkHostedFlowRequired(resp.body)
        if (resp.status == 403) {
            val detail = resp.body["detail"] as? Map<*, *>
            if (detail?.get("error") == "user_revoked") throw OneloError.userRevoked()
            throw OneloError.server((detail?.get("message") ?: resp.body["error"]) as? String ?: "Sign in failed")
        }
        if (resp.status != 200) throw OneloError.server("Sign in failed: HTTP ${resp.status}")
        pkceVerifier = null
        return mapSession(resp.body).also { saveSession(it) }
    }

    suspend fun signUp(email: String, password: String): OneloSession {
        initDeferred.await()
        if (!allowCustomBranding) throw OneloError.planRequired()
        if (pkceVerifier == null) pkceVerifier = Pkce.generateCodeVerifier()
        val resp = HttpClient.post(
            "${config.apiUrl}/api/sdk/auth/signup",
            mapOf(
                "email" to email,
                "password" to password,
                "publishableKey" to config.publishableKey,
                "code_verifier" to pkceVerifier,
            ),
            mapOf("X-SDK-Version" to SDK_VERSION)
        )
        HttpClient.checkHostedFlowRequired(resp.body)
        if (resp.status != 200) throw OneloError.server("Sign up failed: HTTP ${resp.status}")
        pkceVerifier = null
        return mapSession(resp.body).also { saveSession(it) }
    }

    // ── Session management ───────────────────────────────────────────────────────

    suspend fun getSession(): OneloSession? {
        initDeferred.await()
        val accessToken = storage.get("onelo_access_token") ?: return null
        val refreshToken = storage.get("onelo_refresh_token") ?: return null
        val userJson = storage.get("onelo_user") ?: return null
        val expiresAt = storage.get("onelo_expires_at")?.toLongOrNull() ?: 0L

        if (System.currentTimeMillis() / 1000 > expiresAt - 60) {
            return refreshSession()
        }
        val userObj = JSONObject(userJson)
        return OneloSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = expiresAt,
            user = OneloUser(
                id = userObj.getString("id"),
                email = userObj.optString("email").ifBlank { null },
                role = userObj.optString("role").ifBlank { "member" },
                tenantId = userObj.optString("tenantId").ifBlank { null },
            )
        )
    }

    suspend fun refreshSession(): OneloSession? {
        val refreshToken = storage.get("onelo_refresh_token") ?: return null
        val resp = HttpClient.post(
            "${config.apiUrl}/api/sdk/auth/refresh",
            mapOf("publishableKey" to config.publishableKey, "refreshToken" to refreshToken),
            mapOf("X-SDK-Version" to SDK_VERSION)
        )
        HttpClient.checkHostedFlowRequired(resp.body)
        return when {
            resp.body["error"] == "user_revoked" -> { storage.clear(); notifyListeners(null); throw OneloError.userRevoked() }
            resp.body["error"] == "app_revoked" -> { storage.clear(); notifyListeners(null); throw OneloError.revoked() }
            resp.status != 200 -> { storage.clear(); notifyListeners(null); null }
            else -> mapSession(resp.body).also { saveSession(it) }
        }
    }

    suspend fun signOut() {
        storage.clear()
        notifyListeners(null)
    }

    /**
     * Returns a SharedFlow that emits whenever the auth state changes.
     * Collect this in a coroutine (e.g. lifecycleScope).
     */
    fun onAuthStateChange(): SharedFlow<OneloSession?> = authStateFlow

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun saveSession(session: OneloSession) {
        storage.set("onelo_access_token", session.accessToken)
        storage.set("onelo_refresh_token", session.refreshToken)
        storage.set("onelo_expires_at", session.expiresAt.toString())
        storage.set("onelo_user", JSONObject().apply {
            put("id", session.user.id)
            put("email", session.user.email ?: "")
            put("role", session.user.role)
            put("tenantId", session.user.tenantId ?: "")
        }.toString())
        notifyListeners(session)
    }

    private fun notifyListeners(session: OneloSession?) {
        scope.launch { _authStateFlow.emit(session) }
    }

    private fun mapSession(body: Map<String, Any?>): OneloSession {
        val user = body["user"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val appMeta = user["app_metadata"] as? Map<*, *> ?: emptyMap<String, Any?>()
        return OneloSession(
            accessToken = body["access_token"] as? String ?: "",
            refreshToken = body["refresh_token"] as? String ?: "",
            expiresAt = (body["expires_at"] as? Number)?.toLong() ?: 0L,
            user = OneloUser(
                id = user["id"] as? String ?: "",
                email = user["email"] as? String,
                role = (appMeta["user_role"] ?: user["role"] ?: "member") as? String ?: "member",
                tenantId = (appMeta["tenant_id"] ?: user["tenant_id"]) as? String,
            )
        )
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

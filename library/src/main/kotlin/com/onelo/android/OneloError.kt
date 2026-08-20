package com.onelo.android

class OneloError(
    val code: Code,
    message: String,
) : Exception(message) {

    enum class Code {
        NOT_AUTHENTICATED,
        NOT_ENTITLED,
        HOSTED_FLOW_REQUIRED,
        INVALID_PUBLISHABLE_KEY,
        NETWORK_ERROR,
        SERVER_ERROR,
        CANCELLED,
        REVOKED,
        USER_REVOKED,
        PLAN_REQUIRED,
        // Features secure-mode errors — parity w/ Swift OneloFeaturesError +
        // Flutter OneloFeaturesErrorKind (moduleToken 401 branches on detail.error).
        SECURE_MODE_REQUIRED,
        INVALID_USER_HASH,
        /**
         * Store/upgrade blocked because the app ships through an Apple store and
         * the tenant's Access Gate keeps the in-app store hidden (Apple 3.1.1).
         * Android is not subject to that guideline, but this SDK still recognises
         * the code so a cross-platform host sees it instead of a bare HTTP 409.
         */
        IN_APP_STORE_NOT_ALLOWED,
    }

    companion object {
        fun notAuthenticated() = OneloError(Code.NOT_AUTHENTICATED, "User is not authenticated")
        fun notEntitled() = OneloError(Code.NOT_ENTITLED, "User is not entitled to this module")
        fun hostedFlowRequired() = OneloError(Code.HOSTED_FLOW_REQUIRED, "This app requires the hosted sign-in flow. Use loadAuthView().")
        fun planRequired() = OneloError(Code.PLAN_REQUIRED, "[plan_required] Custom UI requires a paid Onelo plan. Use loadAuthView() instead.")
        fun invalidKey(msg: String) = OneloError(Code.INVALID_PUBLISHABLE_KEY, "Invalid publishable key: $msg")
        fun network(msg: String) = OneloError(Code.NETWORK_ERROR, "Network error: $msg")
        fun server(msg: String) = OneloError(Code.SERVER_ERROR, msg)
        fun cancelled() = OneloError(Code.CANCELLED, "Sign in was cancelled")
        fun revoked() = OneloError(Code.REVOKED, "This application has been revoked")
        fun userRevoked() = OneloError(Code.USER_REVOKED, "This user account has been suspended")
        fun secureModeRequired() = OneloError(Code.SECURE_MODE_REQUIRED, "Secure Mode is on but no userIdHash was provided. Compute HMAC-SHA256(secretKey, \"user:\" + userId) on your backend and pass it via identify(userId, userIdHash).")
        fun invalidUserHash() = OneloError(Code.INVALID_USER_HASH, "The provided userIdHash failed verification.")
        fun inAppStoreNotAllowed(msg: String) = OneloError(Code.IN_APP_STORE_NOT_ALLOWED, msg)
    }
}

/**
 * Pulls the machine-readable error code out of an Onelo error body.
 *
 * FastAPI wraps a dict `detail` as `{"detail": {"error": …, "message": …}}`, while
 * hand-rolled handlers return a flat `{"error": …}` and `raise HTTPException(detail="…")`
 * yields a plain string — all three shapes reach the SDK, so read them in that order.
 * Returns null for an unrecognised body; callers must keep their existing behaviour.
 */
internal fun extractErrorCode(body: Map<String, Any?>): String? =
    (body["error"] as? String)
        ?: ((body["detail"] as? Map<*, *>)?.get("error") as? String)
        ?: (body["detail"] as? String)

/** Human-readable message from the same body shapes, or null. */
internal fun extractErrorMessage(body: Map<String, Any?>): String? =
    (body["message"] as? String)
        ?: ((body["detail"] as? Map<*, *>)?.get("message") as? String)
        ?: (body["detail"] as? String)

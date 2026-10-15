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
        /** The account was banned — the only refusal that says "suspended". */
        USER_REVOKED,
        /**
         * The session is over: it expired, was replaced (`session_compromised` —
         * the same refresh token was presented again outside the server's grace
         * window) or is not recognised. Not an account action; sign in again.
         */
        SESSION_ENDED,
        /** The user's plan lapsed and the app has no free tier. Not a revocation. */
        NO_ACTIVE_PLAN,
        PLAN_REQUIRED,
        /** Never raised: Secure Mode is retired and the server no longer answers with it (#519). */
        @Deprecated("Secure Mode is retired; the server no longer returns this.")
        SECURE_MODE_REQUIRED,
        /** Never raised: Secure Mode is retired and the server no longer answers with it (#519). */
        @Deprecated("Secure Mode is retired; the server no longer returns this.")
        INVALID_USER_HASH,
        /**
         * Store/upgrade blocked because the app ships through an Apple store and
         * the tenant's Access Gate keeps the in-app store hidden (Apple 3.1.1).
         * Android is not subject to that guideline, but this SDK still recognises
         * the code so a cross-platform host sees it instead of a bare HTTP 409.
         */
        IN_APP_STORE_NOT_ALLOWED,
        /** Email Verification (#819): at most 3 verification e-mails an hour. */
        VERIFICATION_RATE_LIMITED,
        /** Email Verification is Off for this App (Auth → Sign-up on the parent App). */
        EMAIL_VERIFICATION_OFF,
        /** The verification link was used, replaced by a newer one, or expired. */
        INVALID_VERIFICATION_TOKEN,
        /** The redirectTo given for the link is not an allowed web origin nor `<registered-scheme>://…`. */
        INVALID_VERIFY_REDIRECT,
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
        fun sessionEnded() = OneloError(Code.SESSION_ENDED, "Your session ended — please sign in again")
        fun noActivePlan() = OneloError(
            Code.NO_ACTIVE_PLAN,
            "No active subscription — the plan has expired or was cancelled.",
        )
        @Deprecated("Secure Mode is retired; the SDK never raises this.")
        @Suppress("DEPRECATION")
        fun secureModeRequired() = OneloError(Code.SECURE_MODE_REQUIRED, "Secure Mode is retired.")
        @Deprecated("Secure Mode is retired; the SDK never raises this.")
        @Suppress("DEPRECATION")
        fun invalidUserHash() = OneloError(Code.INVALID_USER_HASH, "Secure Mode is retired.")
        fun inAppStoreNotAllowed(msg: String) = OneloError(Code.IN_APP_STORE_NOT_ALLOWED, msg)

        /** What a refused Email Verification call means (#819). */
        internal fun verification(status: Int, body: Map<String, Any?>): OneloError {
            val code = extractErrorCode(body)
            return when {
                status == 429 || code == "verification_rate_limited" ->
                    OneloError(Code.VERIFICATION_RATE_LIMITED, "At most 3 verification emails an hour — try again later")
                status == 409 || code == "email_verification_off" ->
                    OneloError(Code.EMAIL_VERIFICATION_OFF, "Email verification is off for this app — turn it on in Auth → Sign-up")
                status == 400 && code == "invalid_verify_redirect_to" ->
                    OneloError(Code.INVALID_VERIFY_REDIRECT, "redirectTo must be one of this app's allowed web origins or <your-scheme>://…")
                status == 400 && code == "invalid_token" ->
                    OneloError(Code.INVALID_VERIFICATION_TOKEN, "This verification link is invalid, already used, replaced by a newer one, or expired")
                status == 401 -> notAuthenticated()
                else -> server("Email verification request failed: HTTP $status")
            }
        }
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

/**
 * What a refused `/api/sdk/auth/refresh` means for the app (#548). The backend
 * answers `{"detail": {"error" | "error_code": …}}`; only a ban is an account
 * action — every other refusal (session_compromised / session_expired /
 * session_invalid / anything else) just ends the session.
 */
internal fun refreshRefusalError(body: Map<String, Any?>): OneloError =
    when (extractErrorCode(body) ?: ((body["detail"] as? Map<*, *>)?.get("error_code") as? String)) {
        "banned" -> OneloError.userRevoked()
        "no_plan_available" -> OneloError.noActivePlan()
        else -> OneloError.sessionEnded()
    }

/** True for an error a refused refresh throws AFTER the session was cleared and announced. */
internal fun OneloError.endsSession(): Boolean =
    code == OneloError.Code.SESSION_ENDED || code == OneloError.Code.USER_REVOKED ||
        code == OneloError.Code.NO_ACTIVE_PLAN

/** Delay before the retry after [attempt] consecutive transient refresh failures:
 *  5 s, 10 s, 20 s, 40 s, then 60 s. The first lands inside the server's 30 s
 *  refresh-token grace window (#547/#548). */
internal fun refreshRetryDelayMs(attempt: Int): Long =
    minOf(5_000L shl attempt.coerceIn(0, 10), 60_000L)


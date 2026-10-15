package com.onelo.android

/**
 * Whether the user currently has paid access. Mirrors Swift `OneloEntitlement`.
 * Absence or any unknown value resolves to [NONE] — NEVER silently [ACTIVE], so a
 * missing/unparseable field can never accidentally grant paid access.
 */
enum class OneloEntitlement {
    ACTIVE,
    NONE;

    companion object {
        /** Forward-compatible parse: ONLY the exact string "active" grants access. */
        fun parse(raw: Any?): OneloEntitlement = if (raw == "active") ACTIVE else NONE
    }
}

data class OneloUser(
    val id: String,
    val email: String?,
    val role: String,
    val tenantId: String?,
    /** Paid-access entitlement, returned on every auth response's `user` object. */
    val entitlement: OneloEntitlement = OneloEntitlement.NONE,
    /**
     * Server-computed Access Gate answer (`user.allowed_in` from the backend) — is
     * THIS caller allowed into the app right now? Null on an older backend that
     * doesn't send the field yet; [OneloAuth.isAllowedIn] falls back to the local
     * derivation only in that case. Never compute `!paywallEnabled ||
     * entitlement == ACTIVE` yourself — that rule lived in three other SDKs and
     * was found wrong in each on a different day (2026-08-18). Contract:
     * docs/sdk-access-gate-wiring.md.
     */
    val allowedIn: Boolean? = null,
    /**
     * Has this user proved their address receives mail (Email Verification,
     * #819)? From the backend's `email_verified`; null when an older backend did
     * not send it. OAuth, Magic Link and an e-mailed password reset count as
     * verified.
     */
    val emailVerified: Boolean? = null,
    /**
     * Why [allowedIn] is false, when the server named a reason the app may
     * branch on. Today only `"email_unverified"`: the parent App requires Email
     * Verification and this user has not opened the link yet. Null when allowed
     * in or when the reason is the paywall. Tolerate unknown values.
     */
    val accessReason: String? = null,
) {
    /**
     * The server's access answer from a `/api/sdk/auth/user` body merged over
     * this user (#819): [allowedIn], [emailVerified] and [accessReason] move
     * together. A field the body does not carry keeps its stored value; when the
     * body carries `allowed_in`, [accessReason] is taken from the body even when
     * absent — the answer is complete and an old reason must not outlive it.
     */
    internal fun mergingAccessAnswer(body: Map<String, Any?>, entitlement: OneloEntitlement): OneloUser {
        val answered = body["allowed_in"] is Boolean
        return copy(
            entitlement = entitlement,
            allowedIn = if (answered) body["allowed_in"] as Boolean else allowedIn,
            emailVerified = body["email_verified"] as? Boolean ?: emailVerified,
            accessReason = if (answered) body["access_reason"] as? String else accessReason,
        )
    }
}

/** The result of [OneloAuth.resendVerificationEmail] (#819). */
data class OneloVerificationResend(
    /** A new e-mail went out; the previous link stopped working. */
    val sent: Boolean,
    /** The address was already verified, so nothing was sent. */
    val emailVerified: Boolean,
)

data class OneloSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val user: OneloUser,
)

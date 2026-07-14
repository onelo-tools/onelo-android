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
)

data class OneloSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val user: OneloUser,
)

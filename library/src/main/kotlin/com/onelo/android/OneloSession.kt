package com.onelo.android

data class OneloUser(
    val id: String,
    val email: String?,
    val role: String,
    val tenantId: String?,
)

data class OneloSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val user: OneloUser,
)

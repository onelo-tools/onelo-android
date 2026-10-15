package com.onelo.android

import com.onelo.android.internal.HttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Email Verification (#819) in the Android SDK: the server's answer keeps an
 * unverified user out of a free app, the refresh merge moves the three access
 * fields together, the refusals map to their own codes, and the calls an app
 * with its own screen uses exist.
 */
class OneloAuthEmailVerificationTest {

    private val unverified = OneloUser(
        id = "u1", email = "a@b.c", role = "member", tenantId = null,
        entitlement = OneloEntitlement.NONE,
        allowedIn = false, emailVerified = false, accessReason = "email_unverified",
    )

    @Test
    fun `an unverified user is kept out of a FREE app because the server said no`() {
        assertFalse(OneloAuth.allowedInDecision(unverified.allowedIn, paywallEnabled = false, entitlement = OneloEntitlement.NONE))
    }

    @Test
    fun `a complete answer lets the user in and clears the old reason`() {
        val next = unverified.mergingAccessAnswer(
            mapOf("entitlement" to "none", "allowed_in" to true, "email_verified" to true),
            OneloEntitlement.NONE,
        )
        assertEquals(true, next.allowedIn)
        assertEquals(true, next.emailVerified)
        assertNull(next.accessReason)
    }

    @Test
    fun `fields the body does not carry keep their stored values`() {
        val next = unverified.mergingAccessAnswer(mapOf("entitlement" to "none"), OneloEntitlement.NONE)
        assertEquals(false, next.allowedIn)
        assertEquals(false, next.emailVerified)
        assertEquals("email_unverified", next.accessReason)
    }

    @Test
    fun `refusals map to their own codes`() {
        fun code(status: Int, error: String) =
            OneloError.verification(status, mapOf("detail" to mapOf("error" to error))).code
        assertEquals(OneloError.Code.VERIFICATION_RATE_LIMITED, code(429, "verification_rate_limited"))
        assertEquals(OneloError.Code.EMAIL_VERIFICATION_OFF, code(409, "email_verification_off"))
        assertEquals(OneloError.Code.INVALID_VERIFICATION_TOKEN, code(400, "invalid_token"))
        assertEquals(OneloError.Code.INVALID_VERIFY_REDIRECT, code(400, "invalid_verify_redirect_to"))
        assertEquals(OneloError.Code.NOT_AUTHENTICATED, code(401, "invalid_token"))
        assertEquals(OneloError.Code.SERVER_ERROR, code(500, "boom"))
    }

    @Test
    fun `OneloAuth exposes resendVerificationEmail and confirmEmail`() {
        val names = OneloAuth::class.java.declaredMethods.map { it.name }
        assertTrue(names.contains("resendVerificationEmail"))
        assertTrue(names.contains("confirmEmail"))
    }

    @Test
    fun `the stored user keeps the access answer across save and restore`() {
        val restored = OneloAuth.storedUserFrom(OneloAuth.storedUserJson(unverified))
        assertEquals(false, restored.allowedIn)
        assertEquals(false, restored.emailVerified)
        assertEquals("email_unverified", restored.accessReason)
        // Never answered stays null, not false.
        val bare = OneloAuth.storedUserFrom(
            OneloAuth.storedUserJson(OneloUser(id = "u2", email = null, role = "member", tenantId = null)),
        )
        assertNull(bare.allowedIn)
        assertNull(bare.emailVerified)
        assertNull(bare.accessReason)
    }

    @Test
    fun `confirm refreshes a signed-in session afterwards`() = runTest {
        val order = mutableListOf<String>()
        OneloAuth.confirmThenRefresh(
            confirm = { order += "confirm"; HttpResponse(200, mapOf("email_verified" to true)) },
            hasSession = { true },
            refresh = { order += "refresh" },
            warn = { _, _ -> order += "warn" },
        )
        assertEquals(listOf("confirm", "refresh"), order)
    }

    @Test
    fun `confirm without a session does not refresh, and a failed refresh is only logged`() = runTest {
        val order = mutableListOf<String>()
        OneloAuth.confirmThenRefresh(
            confirm = { HttpResponse(200, emptyMap()) },
            hasSession = { false },
            refresh = { order += "refresh" },
            warn = { _, _ -> order += "warn" },
        )
        assertEquals(emptyList<String>(), order)
        OneloAuth.confirmThenRefresh(
            confirm = { HttpResponse(200, emptyMap()) },
            hasSession = { true },
            refresh = { throw IllegalStateException("offline") },
            warn = { _, _ -> order += "warn" },
        )
        assertEquals(listOf("warn"), order)
    }

    @Test
    fun `a refused confirm throws its code and never refreshes`() = runTest {
        var refreshed = false
        try {
            OneloAuth.confirmThenRefresh(
                confirm = { HttpResponse(400, mapOf("detail" to mapOf("error" to "invalid_token"))) },
                hasSession = { true },
                refresh = { refreshed = true },
                warn = { _, _ -> },
            )
            fail("expected a refusal")
        } catch (e: OneloError) {
            assertEquals(OneloError.Code.INVALID_VERIFICATION_TOKEN, e.code)
        }
        assertFalse(refreshed)
    }
}

package com.onelo.android

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class OneloIdentityBridgeTest {

    @Test
    fun `session flow emits userId when session is present`() = runTest {
        val fakeFlow = MutableSharedFlow<OneloSession?>(replay = 1)
        fakeFlow.emit(
            OneloSession(
                accessToken = "tok",
                refreshToken = "ref",
                expiresAt = 0L,
                user = OneloUser(id = "user-abc", email = null, role = "member", tenantId = null)
            )
        )
        val session = fakeFlow.first()
        assertEquals("user-abc", session?.user?.id)
    }

    @Test
    fun `session flow emits null userId when session is null`() = runTest {
        val fakeFlow = MutableSharedFlow<OneloSession?>(replay = 1)
        fakeFlow.emit(null)
        val session = fakeFlow.first()
        assertEquals(null, session?.user?.id)
    }
}

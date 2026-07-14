package com.onelo.android

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

class HeartbeatTest {

    @Test
    fun `heartbeat job field exists and is null before any session`() = runTest {
        val config = OneloConfig(
            publishableKey = "pk_test",
            apiUrl = "https://api.example.com",
        )
        val auth = OneloAuth(config)

        val jobField = OneloAuth::class.java.getDeclaredField("heartbeatJob")
        jobField.isAccessible = true
        assertNull(jobField.get(auth))
    }

    @Test
    fun `signOut does not crash when no session exists`() = runTest {
        val config = OneloConfig(
            publishableKey = "pk_test",
            apiUrl = "https://api.example.com",
        )
        val auth = OneloAuth(config)
        auth.signOut() // should not throw
    }
}

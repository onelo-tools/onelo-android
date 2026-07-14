package com.onelo.android

import org.junit.Assert.*
import org.junit.Test

class OneloWaitlistTest {

    @Test
    fun `WaitlistResult data class holds all fields`() {
        val r = WaitlistResult(success = true, position = 5, alreadyJoined = false)
        assertTrue(r.success)
        assertEquals(5, r.position)
        assertFalse(r.alreadyJoined)
    }

    @Test
    fun `WaitlistResult alreadyJoined true`() {
        val r = WaitlistResult(success = true, alreadyJoined = true)
        assertTrue(r.alreadyJoined)
        assertNull(r.position)
    }
}

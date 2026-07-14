package com.onelo.android

import org.junit.Assert.*
import org.junit.Test

class OneloFormsTest {

    @Test
    fun `FormResult data class holds success and message`() {
        val r = FormResult(success = true, message = "Thanks!")
        assertTrue(r.success)
        assertEquals("Thanks!", r.message)
    }

    @Test
    fun `FormResult success false has null message by default`() {
        val r = FormResult(success = false)
        assertFalse(r.success)
        assertNull(r.message)
    }
}

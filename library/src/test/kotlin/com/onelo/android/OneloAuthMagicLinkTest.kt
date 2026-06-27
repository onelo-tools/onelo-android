package com.onelo.android

import org.junit.Assert.*
import org.junit.Test

class OneloAuthMagicLinkTest {

    @Test
    fun `OneloAuth exposes sendMagicLink method`() {
        val methods = OneloAuth::class.java.declaredMethods
        assertNotNull(methods.find { it.name == "sendMagicLink" })
    }

    @Test
    fun `OneloAuth exposes sendPasswordReset method`() {
        val methods = OneloAuth::class.java.declaredMethods
        assertNotNull(methods.find { it.name == "sendPasswordReset" })
    }
}

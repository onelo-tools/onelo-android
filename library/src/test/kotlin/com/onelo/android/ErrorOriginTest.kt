package com.onelo.android

import org.junit.Assert.assertEquals
import org.junit.Test

/** #548 — an uncaught exception is tagged with whose code threw it. */
class ErrorOriginTest {

    private fun thrownAt(vararg frames: Pair<String, String>): Throwable =
        IllegalStateException("boom").apply {
            stackTrace = frames.map { (cls, method) -> StackTraceElement(cls, method, "F.kt", 1) }.toTypedArray()
        }

    @Test
    fun `a OneloError is the SDK's`() {
        assertEquals("onelo_sdk", errorOrigin(OneloError.sessionEnded()))
    }

    @Test
    fun `an exception thrown in SDK code is the SDK's, past platform frames`() {
        val t = thrownAt(
            "kotlin.collections.CollectionsKt" to "first",
            "com.onelo.android.OneloAuth" to "performRefreshSession",
            "com.example.app.MainActivity" to "onCreate",
        )
        assertEquals("onelo_sdk", errorOrigin(t))
    }

    @Test
    fun `an app exception thrown from a callback the SDK invoked is the app's`() {
        val t = thrownAt(
            "com.example.app.MainActivity\$1" to "invoke",
            "com.onelo.android.OneloAuth" to "notifyListeners",
        )
        assertEquals("app", errorOrigin(t))
    }

    @Test
    fun `no frames is the app's`() {
        assertEquals("app", errorOrigin(thrownAt()))
    }
}

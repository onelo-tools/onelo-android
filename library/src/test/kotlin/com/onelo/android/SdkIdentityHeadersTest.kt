package com.onelo.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #122 — the SDK names itself (`X-Onelo-Sdk-Name`) next to its version so the
 * dashboard compares against `LATEST_SDK_VERSIONS["onelo-android"]` instead of
 * guessing the SDK from the app's type. `OneloAuth.baseHeaders` and
 * `Onelo.securityHeaders` both spread [sdkIdentityHeaders]; those need a
 * Context (see OneloAuthFlowRoutingTest), so the helper is what is pinned.
 */
class SdkIdentityHeadersTest {

    @Test
    fun `name is the registry key and travels with the version`() {
        val h = sdkIdentityHeaders()
        assertEquals("onelo-android", h["X-Onelo-Sdk-Name"])
        assertEquals(SDK_VERSION, h["X-SDK-Version"])
        assertEquals(2, h.size)
    }
}

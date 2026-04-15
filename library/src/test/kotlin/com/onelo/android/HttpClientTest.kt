package com.onelo.android

import com.onelo.android.internal.HttpClient
import com.onelo.android.internal.HttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HttpClientTest {

    @Test
    fun `HttpResponse data class holds status and body`() {
        val resp = HttpResponse(200, mapOf("key" to "value"))
        assertEquals(200, resp.status)
        assertEquals(mapOf("key" to "value"), resp.body)
    }

    @Test
    fun `checkHostedFlowRequired throws for hosted_flow_required error`() {
        val body = mapOf<String, Any>("error" to "hosted_flow_required")
        try {
            HttpClient.checkHostedFlowRequired(body)
            fail("Expected OneloError")
        } catch (e: com.onelo.android.OneloError) {
            assertEquals(com.onelo.android.OneloError.Code.HOSTED_FLOW_REQUIRED, e.code)
        }
    }

    @Test
    fun `checkHostedFlowRequired does not throw for other errors`() {
        HttpClient.checkHostedFlowRequired(mapOf("error" to "some_other_error"))
        // no exception
    }

    @Test
    fun `checkHostedFlowRequired reads nested detail error`() {
        val body = mapOf<String, Any>("detail" to mapOf("error" to "hosted_flow_required"))
        try {
            HttpClient.checkHostedFlowRequired(body)
            fail("Expected OneloError")
        } catch (e: com.onelo.android.OneloError) {
            assertEquals(com.onelo.android.OneloError.Code.HOSTED_FLOW_REQUIRED, e.code)
        }
    }
}

package com.talkback.core.endpointtext

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class EndpointTextControllerTest {
    @Test
    fun prepareSend_rejectsTextOver256Chars() {
        val controller = EndpointTextController()
        val from = EndpointAddress(ModuleId("M01"), EndpointId("E01"))
        val to = EndpointAddress(ModuleId("M02"), EndpointId("E01"))
        val result = controller.prepareSend(from, to, "y".repeat(257))
        assertTrue(result is EndpointTextPrepareResult.Rejected)
        assertEquals(
            EndpointTextController.REASON_TEXT_TOO_LONG,
            (result as EndpointTextPrepareResult.Rejected).reason
        )
    }

    @Test
    fun prepareSend_rateLimitsSecondSendWithinWindow() {
        var now = 1_000L
        val controller = EndpointTextController(clockMs = { now })
        val from = EndpointAddress(ModuleId("M01"), EndpointId("E01"))
        val to = EndpointAddress(ModuleId("M02"), EndpointId("E01"))
        assertTrue(controller.prepareSend(from, to, "a") is EndpointTextPrepareResult.Ready)
        controller.markSent(from, to)
        now += 200L
        assertTrue(controller.prepareSend(from, to, "b") is EndpointTextPrepareResult.RateLimited)
        now += 1_000L
        assertTrue(controller.prepareSend(from, to, "c") is EndpointTextPrepareResult.Ready)
        controller.markSent(from, to)
    }

    @Test
    fun onReceive_dedupsSameMessageId() {
        val controller = EndpointTextController()
        val from = EndpointAddress(ModuleId("M01"), EndpointId("E01"))
        val to = EndpointAddress(ModuleId("M02"), EndpointId("E01"))
        val payload = EndpointTextPayload("mid-1", "hi").encode()
        val signal = SignalEnvelope(
            type = SignalType.ENDPOINT_TEXT,
            from = from,
            to = to,
            sessionId = "",
            timestampMs = 1L,
            payload = payload,
            nonce = "n1",
            signature = "s"
        )
        assertEquals("hi", controller.onReceive(signal)?.text)
        assertNull(controller.onReceive(signal.copy(nonce = "n2")))
    }
}

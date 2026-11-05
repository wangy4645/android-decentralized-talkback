package com.talkback.core.endpointattachment

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class EndpointAttachmentControllerTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val from = EndpointAddress(m01, EndpointId("E01"))
    private val to = EndpointAddress(m02, EndpointId("E01"))

    @Test
    fun prepareSend_andReassemble_roundTrip() {
        val controller = EndpointAttachmentController()
        val payload = ByteArray(1200) { (it % 256).toByte() }
        val prepared = controller.prepareSend(
            from,
            to,
            payload,
            AttachmentKind.FILE,
            "application/octet-stream",
            "test.bin",
            caption = "cap"
        )
        assertTrue(prepared is EndpointAttachmentPrepareResult.Ready)
        val ready = prepared as EndpointAttachmentPrepareResult.Ready
        assertEquals(3, ready.chunks.size)

        val metaSignal = envelope(SignalType.ENDPOINT_ATTACHMENT_META, ready.meta.encode())
        assertNotNull(controller.onReceiveMeta(metaSignal))

        var event: EndpointAttachmentEvent? = null
        for (chunk in ready.chunks) {
            val chunkSignal = envelope(SignalType.ENDPOINT_ATTACHMENT_CHUNK, chunk.payload)
            event = controller.onReceiveChunk(chunkSignal) ?: continue
        }
        assertNotNull(event)
        assertArrayEquals(payload, event!!.bytes)
        assertEquals("cap", event.caption)
        assertEquals(AttachmentKind.FILE, event.kind)
    }

    private fun envelope(type: SignalType, payload: String): SignalEnvelope =
        SignalEnvelope(
            type = type,
            from = from,
            to = to,
            sessionId = "",
            timestampMs = System.currentTimeMillis(),
            payload = payload,
            nonce = "n-${type.name}",
            signature = "sig"
        )
}

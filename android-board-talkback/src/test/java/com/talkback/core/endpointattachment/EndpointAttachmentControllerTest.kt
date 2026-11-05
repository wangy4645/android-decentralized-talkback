package com.talkback.core.endpointattachment

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    private val m03 = ModuleId("M03")
    private val alice = EndpointAddress(m01, EndpointId("E01"))
    private val bobEndpoint = EndpointAddress(m02, EndpointId("E01"))
    private val eve = EndpointAddress(m03, EndpointId("E01"))
    private val to = bobEndpoint

    @Test
    fun prepareSend_andReassemble_roundTrip() {
        val controller = EndpointAttachmentController()
        val payload = ByteArray(1200) { (it % 256).toByte() }
        val prepared = controller.prepareSend(
            alice,
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

        val metaSignal = envelope(alice, SignalType.ENDPOINT_ATTACHMENT_META, ready.meta.encode(), "n-meta")
        assertNotNull(controller.onReceiveMeta(metaSignal))

        var event: EndpointAttachmentEvent? = null
        for ((i, chunk) in ready.chunks.withIndex()) {
            val chunkSignal = envelope(
                alice,
                SignalType.ENDPOINT_ATTACHMENT_CHUNK,
                chunk.payload,
                "n-chunk-$i"
            )
            event = controller.onReceiveChunk(chunkSignal) ?: continue
        }
        assertNotNull(event)
        assertArrayEquals(payload, event!!.bytes)
        assertEquals("cap", event.caption)
        assertEquals(ready.meta.sha256, event.sha256)
    }

    @Test
    fun chunkFromDifferentSender_isRejected() {
        val controller = EndpointAttachmentController()
        val payload = ByteArray(500) { 1 }
        val ready = controller.prepareSend(
            alice,
            to,
            payload,
            AttachmentKind.FILE,
            "application/octet-stream",
            "a.bin",
            caption = null
        ) as EndpointAttachmentPrepareResult.Ready

        assertNotNull(
            controller.onReceiveMeta(
                envelope(alice, SignalType.ENDPOINT_ATTACHMENT_META, ready.meta.encode(), "meta-1")
            )
        )
        val chunk = ready.chunks.first()
        assertNull(
            controller.onReceiveChunk(
                envelope(eve, SignalType.ENDPOINT_ATTACHMENT_CHUNK, chunk.payload, "evil-1")
            )
        )
        assertNotNull(
            controller.onReceiveChunk(
                envelope(alice, SignalType.ENDPOINT_ATTACHMENT_CHUNK, chunk.payload, "ok-1")
            )
        )
    }

    @Test
    fun staleTransfer_isEvicted() {
        var now = 0L
        val controller = EndpointAttachmentController(
            transferTtlMs = 1000L,
            clockMs = { now }
        )
        val payload = byteArrayOf(1, 2, 3)
        val ready = controller.prepareSend(
            alice,
            to,
            payload,
            AttachmentKind.FILE,
            "application/octet-stream",
            "t.bin",
            caption = null
        ) as EndpointAttachmentPrepareResult.Ready
        assertNotNull(
            controller.onReceiveMeta(
                envelope(alice, SignalType.ENDPOINT_ATTACHMENT_META, ready.meta.encode(), "meta-t")
            )
        )
        now = 2000L
        controller.expireStaleTransfersForTest(now)
        assertNull(
            controller.onReceiveChunk(
                envelope(
                    alice,
                    SignalType.ENDPOINT_ATTACHMENT_CHUNK,
                    ready.chunks.first().payload,
                    "chunk-late"
                )
            )
        )
    }

    private fun envelope(
        from: EndpointAddress,
        type: SignalType,
        payload: String,
        nonce: String
    ): SignalEnvelope =
        SignalEnvelope(
            type = type,
            from = from,
            to = to,
            sessionId = "",
            timestampMs = System.currentTimeMillis(),
            payload = payload,
            nonce = nonce,
            signature = "sig"
        )
}

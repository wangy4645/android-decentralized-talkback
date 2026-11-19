package com.talkback.core.endpointattachment.bulk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkAttachmentFrameCodecTest {
    @Test
    fun dataAndEnd_roundTrip() {
        val payload = ByteArray(120) { it.toByte() }
        val frame = BulkAttachmentFrameCodec.encodeDataFrame(payload)
        val parsed = BulkAttachmentFrameCodec.parseFrame(frame) as BulkAttachmentFrameCodec.Parsed.Data
        assertArrayEquals(payload, parsed.payload)
        assertTrue(BulkAttachmentFrameCodec.parseFrame(BulkAttachmentFrameCodec.encodeEndFrame()) is BulkAttachmentFrameCodec.Parsed.End)
    }
}
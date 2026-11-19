package com.talkback.core.endpointattachment.bulk

import java.nio.ByteBuffer
import java.nio.ByteOrder

object BulkAttachmentFrameCodec {
    fun encodeDataFrame(payload: ByteArray): ByteArray {
        require(payload.size in 1..BulkAttachmentPolicy.DATA_FRAME_MAX_BYTES)
        val out = ByteArray(5 + payload.size)
        out[0] = BulkAttachmentPolicy.FRAME_TYPE_DATA
        ByteBuffer.wrap(out, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size)
        payload.copyInto(out, destinationOffset = 5)
        return out
    }

    fun encodeEndFrame(): ByteArray = byteArrayOf(BulkAttachmentPolicy.FRAME_TYPE_END, 0, 0, 0, 0)

    sealed class Parsed {
        data class Data(val payload: ByteArray) : Parsed()
        data object End : Parsed()
    }

    fun parseFrame(bytes: ByteArray): Parsed? {
        if (bytes.size < 5) return null
        return when (bytes[0]) {
            BulkAttachmentPolicy.FRAME_TYPE_DATA -> {
                val len = ByteBuffer.wrap(bytes, 1, 4).order(ByteOrder.BIG_ENDIAN).int
                if (len <= 0 || len > BulkAttachmentPolicy.DATA_FRAME_MAX_BYTES) return null
                if (bytes.size < 5 + len) return null
                Parsed.Data(bytes.copyOfRange(5, 5 + len))
            }
            BulkAttachmentPolicy.FRAME_TYPE_END -> Parsed.End
            else -> null
        }
    }
}
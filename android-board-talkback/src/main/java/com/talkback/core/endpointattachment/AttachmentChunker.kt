package com.talkback.core.endpointattachment

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Splits attachment bytes into UDP-safe chunk payloads (base64 in JSON).
 * Keeps each chunk wire payload small relative to the 8192-byte datagram buffer.
 */
object AttachmentChunker {
    const val CHUNK_RAW_BYTES = 480
    const val MAX_ATTACHMENT_BYTES = 3 * 1024 * 1024

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    fun chunkCount(totalBytes: Int): Int =
        (totalBytes + CHUNK_RAW_BYTES - 1) / CHUNK_RAW_BYTES

    fun sliceChunk(data: ByteArray, index: Int): ByteArray {
        val start = index * CHUNK_RAW_BYTES
        val end = minOf(start + CHUNK_RAW_BYTES, data.size)
        return data.copyOfRange(start, end)
    }

    fun encodeChunkPayload(attachmentId: String, index: Int, total: Int, chunk: ByteArray): String {
        val json = JSONObject()
            .put("attachmentId", attachmentId)
            .put("index", index)
            .put("total", total)
            .put("data", Base64.encodeToString(chunk, Base64.NO_WRAP))
        return json.toString()
    }

    fun decodeChunkPayload(raw: String): AttachmentChunkWire? {
        if (raw.isBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            val attachmentId = json.optString("attachmentId", "")
            if (attachmentId.isBlank()) return null
            val index = json.optInt("index", -1)
            val total = json.optInt("total", -1)
            if (index < 0 || total <= 0 || index >= total) return null
            val b64 = json.optString("data", "")
            if (b64.isBlank()) return null
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            AttachmentChunkWire(attachmentId, index, total, bytes)
        }.getOrNull()
    }
}

data class AttachmentChunkWire(
    val attachmentId: String,
    val index: Int,
    val total: Int,
    val bytes: ByteArray
)

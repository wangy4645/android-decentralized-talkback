package com.talkback.core.endpointattachment.bulk

import com.talkback.core.endpointattachment.AttachmentKind
import org.json.JSONObject

/** UDP announcement before bulk payload on DataChannel (1:1 endpoint). */
data class EndpointBulkOpenPayload(
    val messageId: String,
    val attachmentId: String,
    val kind: AttachmentKind,
    val mimeType: String,
    val fileName: String,
    val totalBytes: Long,
    val sha256: String,
    val protocolVersion: Int = BulkAttachmentPolicy.PROTOCOL_VERSION,
    val caption: String?,
) {
    fun encode(): String {
        val json = JSONObject()
            .put("messageId", messageId)
            .put("attachmentId", attachmentId)
            .put("kind", kind.name)
            .put("mimeType", mimeType)
            .put("fileName", fileName)
            .put("totalBytes", totalBytes)
            .put("sha256", sha256)
            .put("protocolVersion", protocolVersion)
        if (caption.isNullOrBlank()) {
            json.put("caption", JSONObject.NULL)
        } else {
            json.put("caption", caption)
        }
        return json.toString()
    }

    companion object {
        fun decode(raw: String): EndpointBulkOpenPayload? {
            if (raw.isBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                val messageId = json.optString("messageId", "")
                val attachmentId = json.optString("attachmentId", "")
                if (messageId.isBlank() || attachmentId.isBlank()) return null
                val kind = AttachmentKind.fromWire(json.optString("kind", "")) ?: return null
                val totalBytes = json.optLong("totalBytes", -1L)
                if (totalBytes <= 0L || totalBytes > AttachmentSizePolicy.BULK_MAX_BYTES) return null
                val sha256 = json.optString("sha256", "")
                if (sha256.isBlank()) return null
                val protocolVersion = json.optInt("protocolVersion", 0)
                if (protocolVersion != BulkAttachmentPolicy.PROTOCOL_VERSION) return null
                val captionRaw = json.opt("caption")
                val caption = when {
                    captionRaw == null || captionRaw == JSONObject.NULL -> null
                    else -> captionRaw.toString().takeIf { it.isNotBlank() && it != "null" }
                }
                EndpointBulkOpenPayload(
                    messageId = messageId,
                    attachmentId = attachmentId,
                    kind = kind,
                    mimeType = json.optString("mimeType", "application/octet-stream"),
                    fileName = json.optString("fileName", "attachment"),
                    totalBytes = totalBytes,
                    sha256 = sha256,
                    protocolVersion = protocolVersion,
                    caption = caption,
                )
            }.getOrNull()
        }
    }
}
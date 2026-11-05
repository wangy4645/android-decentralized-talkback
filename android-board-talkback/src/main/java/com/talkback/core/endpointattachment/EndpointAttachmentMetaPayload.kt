package com.talkback.core.endpointattachment

import org.json.JSONObject

data class EndpointAttachmentMetaPayload(
    val messageId: String,
    val attachmentId: String,
    val kind: AttachmentKind,
    val mimeType: String,
    val fileName: String,
    val totalBytes: Int,
    val totalChunks: Int,
    val sha256: String,
    val caption: String?
) {
    fun encode(): String {
        val json = JSONObject()
            .put("messageId", messageId)
            .put("attachmentId", attachmentId)
            .put("kind", kind.name)
            .put("mimeType", mimeType)
            .put("fileName", fileName)
            .put("totalBytes", totalBytes)
            .put("totalChunks", totalChunks)
            .put("sha256", sha256)
        if (caption.isNullOrBlank()) {
            json.put("caption", JSONObject.NULL)
        } else {
            json.put("caption", caption)
        }
        return json.toString()
    }

    companion object {
        fun decode(raw: String): EndpointAttachmentMetaPayload? {
            if (raw.isBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                val messageId = json.optString("messageId", "")
                val attachmentId = json.optString("attachmentId", "")
                if (messageId.isBlank() || attachmentId.isBlank()) return null
                val kind = AttachmentKind.fromWire(json.optString("kind", "")) ?: return null
                val mimeType = json.optString("mimeType", "application/octet-stream")
                val fileName = json.optString("fileName", "attachment")
                val totalBytes = json.optInt("totalBytes", -1)
                val totalChunks = json.optInt("totalChunks", -1)
                if (totalBytes <= 0 || totalChunks <= 0) return null
                val sha256 = json.optString("sha256", "")
                val captionRaw = json.opt("caption")
                val caption = when {
                    captionRaw == null || captionRaw == JSONObject.NULL -> null
                    else -> captionRaw.toString().takeIf { it.isNotBlank() && it != "null" }
                }
                EndpointAttachmentMetaPayload(
                    messageId = messageId,
                    attachmentId = attachmentId,
                    kind = kind,
                    mimeType = mimeType,
                    fileName = fileName,
                    totalBytes = totalBytes,
                    totalChunks = totalChunks,
                    sha256 = sha256,
                    caption = caption
                )
            }.getOrNull()
        }
    }
}

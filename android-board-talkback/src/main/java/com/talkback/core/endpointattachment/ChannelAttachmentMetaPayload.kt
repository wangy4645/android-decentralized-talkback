package com.talkback.core.endpointattachment

import org.json.JSONObject

data class ChannelAttachmentMetaPayload(
    val messageId: String,
    val attachmentId: String,
    val channelId: String,
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
            .put("channelId", channelId)
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
        fun decode(raw: String): ChannelAttachmentMetaPayload? {
            if (raw.isBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                val messageId = json.optString("messageId", "")
                val attachmentId = json.optString("attachmentId", "")
                val channelId = json.optString("channelId", "")
                if (messageId.isBlank() || attachmentId.isBlank() || channelId.isBlank()) return null
                val kind = AttachmentKind.fromWire(json.optString("kind", "")) ?: return null
                val totalBytes = json.optInt("totalBytes", -1)
                val totalChunks = json.optInt("totalChunks", -1)
                if (totalBytes <= 0 || totalChunks <= 0) return null
                val captionRaw = json.opt("caption")
                val caption = when {
                    captionRaw == null || captionRaw == JSONObject.NULL -> null
                    else -> captionRaw.toString().takeIf { it.isNotBlank() && it != "null" }
                }
                ChannelAttachmentMetaPayload(
                    messageId = messageId,
                    attachmentId = attachmentId,
                    channelId = channelId,
                    kind = kind,
                    mimeType = json.optString("mimeType", "application/octet-stream"),
                    fileName = json.optString("fileName", "attachment"),
                    totalBytes = totalBytes,
                    totalChunks = totalChunks,
                    sha256 = json.optString("sha256", ""),
                    caption = caption
                )
            }.getOrNull()
        }
    }
}

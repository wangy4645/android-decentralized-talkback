package com.talkback.core.channeltext

import org.json.JSONObject

/**
 * Wire payload for [com.talkback.core.model.SignalType.CHANNEL_TEXT].
 * Channel identity lives here — envelope `to` is only the mesh hop target.
 */
data class ChannelTextPayload(
    val messageId: String,
    val channelId: String,
    val text: String,
    val priority: String = "INLINE"
) {
    fun encode(): String {
        return JSONObject()
            .put("messageId", messageId)
            .put("channelId", channelId)
            .put("text", text)
            .put("priority", priority)
            .toString()
    }

    companion object {
        fun decode(raw: String): ChannelTextPayload? {
            if (raw.isBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                val messageId = json.optString("messageId", "")
                val channelId = json.optString("channelId", "")
                if (messageId.isBlank() || channelId.isBlank()) return null
                ChannelTextPayload(
                    messageId = messageId,
                    channelId = channelId,
                    text = json.optString("text", ""),
                    priority = json.optString("priority", "INLINE").ifBlank { "INLINE" }
                )
            }.getOrNull()
        }
    }
}

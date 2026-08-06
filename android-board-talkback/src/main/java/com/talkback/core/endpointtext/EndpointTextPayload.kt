package com.talkback.core.endpointtext

import org.json.JSONObject

/**
 * Wire payload for [com.talkback.core.model.SignalType.ENDPOINT_TEXT].
 * Message Id lives here only — [com.talkback.core.model.SignalEnvelope.sessionId] stays empty.
 */
data class EndpointTextPayload(
    val messageId: String,
    val text: String,
    val priority: String = "INLINE",
    val sessionHint: String? = null
) {
    fun encode(): String {
        val json = JSONObject()
            .put("messageId", messageId)
            .put("text", text)
            .put("priority", priority)
        if (sessionHint == null) {
            json.put("sessionHint", JSONObject.NULL)
        } else {
            json.put("sessionHint", sessionHint)
        }
        return json.toString()
    }

    companion object {
        fun decode(raw: String): EndpointTextPayload? {
            if (raw.isBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                val messageId = json.optString("messageId", "")
                if (messageId.isBlank()) return null
                val text = json.optString("text", "")
                val hintRaw = json.opt("sessionHint")
                val sessionHint = when {
                    hintRaw == null || hintRaw == JSONObject.NULL -> null
                    else -> hintRaw.toString().takeIf { it.isNotBlank() && it != "null" }
                }
                EndpointTextPayload(
                    messageId = messageId,
                    text = text,
                    priority = json.optString("priority", "INLINE").ifBlank { "INLINE" },
                    sessionHint = sessionHint
                )
            }.getOrNull()
        }
    }
}

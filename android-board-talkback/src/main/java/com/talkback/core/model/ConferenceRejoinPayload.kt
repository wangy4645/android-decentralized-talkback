package com.talkback.core.model

import org.json.JSONObject

/** Payload for [SignalType.CONFERENCE_REJOIN] — return to a stable host conference room. */
data class ConferenceRejoinPayload(
    val channelId: String,
    val hostSessionId: String
) {
    fun encode(): String = JSONObject()
        .put("channelId", channelId)
        .put("hostSessionId", hostSessionId)
        .toString()

    companion object {
        fun decode(raw: String): ConferenceRejoinPayload? = runCatching {
            val json = JSONObject(raw)
            ConferenceRejoinPayload(
                channelId = json.optString("channelId", "CH-01"),
                hostSessionId = json.optString("hostSessionId", "")
            )
        }.getOrNull()
    }
}

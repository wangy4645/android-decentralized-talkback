package com.talkback.core.model

import org.json.JSONObject

data class GroupResyncRequestPayload(
    val channelId: String,
    val requesterRosterEpoch: Long
) {
    fun encode(): String = JSONObject()
        .put("channelId", channelId)
        .put("requesterRosterEpoch", requesterRosterEpoch)
        .toString()

    companion object {
        fun decode(raw: String): GroupResyncRequestPayload? = runCatching {
            val json = JSONObject(raw)
            GroupResyncRequestPayload(
                channelId = json.getString("channelId"),
                requesterRosterEpoch = json.optLong("requesterRosterEpoch", 0L)
            )
        }.getOrNull()
    }
}

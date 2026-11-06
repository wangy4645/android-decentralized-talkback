package com.talkback.core.model

import org.json.JSONObject

/** Wire body for [SignalType.GROUP_REOFFER_REQUEST]. */
data class GroupReofferRequestPayload(
    val channelId: String,
    val generation: Long,
) {
    fun encode(): String = JSONObject()
        .put("channelId", channelId)
        .put("generation", generation)
        .toString()

    companion object {
        fun decode(raw: String): GroupReofferRequestPayload? = runCatching {
            val json = JSONObject(raw)
            GroupReofferRequestPayload(
                channelId = json.getString("channelId"),
                generation = json.optLong("generation", -1L),
            )
        }.getOrNull()
    }
}

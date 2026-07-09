package com.talkback.core.model

import org.json.JSONObject

/** Payload for [SignalType.CONFERENCE_REJOIN] — Membership USER_REJOIN or Connectivity RECOVERY_REATTACH. */
data class ConferenceRejoinPayload(
    val channelId: String,
    val hostSessionId: String,
    val membershipEpoch: Long = 0L,
    val endpointId: String = "",
    val intent: ConferenceJoinIntent = ConferenceJoinIntent.USER_REJOIN
) {
    fun encode(): String = JSONObject()
        .put("channelId", channelId)
        .put("hostSessionId", hostSessionId)
        .put("membershipEpoch", membershipEpoch)
        .put("endpointId", endpointId)
        .put("intent", intent.encode())
        .toString()

    companion object {
        fun decode(raw: String): ConferenceRejoinPayload? = runCatching {
            val json = JSONObject(raw)
            ConferenceRejoinPayload(
                channelId = json.optString("channelId", "CH-01"),
                hostSessionId = json.optString("hostSessionId", ""),
                membershipEpoch = json.optLong("membershipEpoch", 0L),
                endpointId = json.optString("endpointId", ""),
                intent = if (json.has("intent")) {
                    ConferenceJoinIntent.fromPayload(json.optString("intent"))
                } else {
                    // Legacy wire default was recovery; Membership path now sets USER_REJOIN explicitly.
                    ConferenceJoinIntent.RECOVERY_REATTACH
                }
            )
        }.getOrNull()
    }
}
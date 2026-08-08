package com.talkback.core.model

import org.json.JSONObject

/** ADR-0043 P1 wire: requester asks authority for accepted membership context existence. */
data class MembershipContextExistenceQueryPayload(
    val channelId: String,
    val decisionEpoch: Long,
    val correlationId: String
) {
    fun encode(): String = JSONObject()
        .put("channelId", channelId)
        .put("decisionEpoch", decisionEpoch)
        .put("correlationId", correlationId)
        .toString()

    companion object {
        fun decode(raw: String): MembershipContextExistenceQueryPayload? = runCatching {
            val json = JSONObject(raw)
            MembershipContextExistenceQueryPayload(
                channelId = json.getString("channelId"),
                decisionEpoch = json.getLong("decisionEpoch"),
                correlationId = json.getString("correlationId")
            )
        }.getOrNull()
    }
}

/** ADR-0043 P1 wire: authority answers context existence for scope + epoch. */
data class MembershipContextExistenceResponsePayload(
    val channelId: String,
    val decisionEpoch: Long,
    val correlationId: String,
    val answer: String
) {
    fun encode(): String = JSONObject()
        .put("channelId", channelId)
        .put("decisionEpoch", decisionEpoch)
        .put("correlationId", correlationId)
        .put("answer", answer)
        .toString()

    companion object {
        fun decode(raw: String): MembershipContextExistenceResponsePayload? = runCatching {
            val json = JSONObject(raw)
            MembershipContextExistenceResponsePayload(
                channelId = json.getString("channelId"),
                decisionEpoch = json.getLong("decisionEpoch"),
                correlationId = json.getString("correlationId"),
                answer = json.getString("answer")
            )
        }.getOrNull()
    }
}

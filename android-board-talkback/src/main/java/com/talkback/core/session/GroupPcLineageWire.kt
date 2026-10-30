package com.talkback.core.session

import org.json.JSONObject

/**
 * ADR-0057 GPLB Option A — GROUP negotiation wire correlation (offerLineageId echo).
 * GROUP-scoped; not conference realization authority.
 */
object GroupPcLineageWire {
    const val UNKNOWN_LINEAGE = "OFFER_LINEAGE_UNKNOWN"

    const val KEY_ANSWER_SDP = "tbAnswerSdp"
    const val KEY_OFFER_LINEAGE = "offerLineageId"
    const val KEY_ICE_CANDIDATE = "tbIceCandidate"

    data class GroupAcceptParse(
        val sdp: String,
        val offerLineageId: String,
    )

    data class IceParse(
        val candidate: String,
        val offerLineageId: String,
    )

    fun encodeGroupAcceptAnswer(answerSdp: String, offerLineageId: String): String {
        require(offerLineageId.isNotBlank() && offerLineageId != UNKNOWN_LINEAGE) {
            "GROUP_ACCEPT requires offer-originated offerLineageId echo"
        }
        return JSONObject()
            .put(KEY_ANSWER_SDP, answerSdp)
            .put(KEY_OFFER_LINEAGE, offerLineageId)
            .toString()
    }

    fun parseGroupAccept(payload: String): GroupAcceptParse {
        if (payload.isBlank()) {
            return GroupAcceptParse(sdp = "", offerLineageId = UNKNOWN_LINEAGE)
        }
        val json = runCatching { JSONObject(payload) }.getOrNull()
        if (json != null && json.has(KEY_ANSWER_SDP)) {
            val lineage = json.optString(KEY_OFFER_LINEAGE).takeIf { it.isNotBlank() }
                ?: UNKNOWN_LINEAGE
            return GroupAcceptParse(
                sdp = json.optString(KEY_ANSWER_SDP),
                offerLineageId = lineage,
            )
        }
        return GroupAcceptParse(sdp = payload, offerLineageId = UNKNOWN_LINEAGE)
    }

    fun encodeIceCandidate(candidate: String, offerLineageId: String): String {
        require(offerLineageId.isNotBlank() && offerLineageId != UNKNOWN_LINEAGE) {
            "GROUP ICE requires negotiation offerLineageId stamp"
        }
        return JSONObject()
            .put(KEY_ICE_CANDIDATE, candidate)
            .put(KEY_OFFER_LINEAGE, offerLineageId)
            .toString()
    }

    fun parseIce(payload: String): IceParse {
        if (payload.isBlank()) {
            return IceParse(candidate = "", offerLineageId = UNKNOWN_LINEAGE)
        }
        val json = runCatching { JSONObject(payload) }.getOrNull()
        if (json != null && json.has(KEY_ICE_CANDIDATE)) {
            val lineage = json.optString(KEY_OFFER_LINEAGE).takeIf { it.isNotBlank() }
                ?: UNKNOWN_LINEAGE
            return IceParse(
                candidate = json.optString(KEY_ICE_CANDIDATE),
                offerLineageId = lineage,
            )
        }
        return IceParse(candidate = payload, offerLineageId = UNKNOWN_LINEAGE)
    }
}

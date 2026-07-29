package com.talkback.core.model

import com.talkback.core.util.RecoveryReattachAckFields
import org.json.JSONObject

/**
 * ADR-0035 — delivery confirmation for RECOVERY_REATTACH (not SDP answer).
 * Wire: [SignalType.RECOVERY_REATTACH_ACK].
 */
data class RecoveryReattachAckPayload(
    val offerLineageId: String,
    val recoveryAttemptId: Long,
    val obligationGeneration: Long,
    val deliveryAttemptId: Long
) {
    fun encode(): String = JSONObject()
        .put("offerLineageId", offerLineageId)
        .put("recoveryAttemptId", recoveryAttemptId)
        .put("obligationGeneration", obligationGeneration)
        .put("deliveryAttemptId", deliveryAttemptId)
        .toString()

    fun toAckFields(): RecoveryReattachAckFields = RecoveryReattachAckFields(
        offerLineageId = offerLineageId,
        recoveryAttemptId = recoveryAttemptId,
        obligationGeneration = obligationGeneration,
        deliveryAttemptId = deliveryAttemptId
    )

    companion object {
        fun decode(raw: String): RecoveryReattachAckPayload? = runCatching {
            val json = JSONObject(raw)
            RecoveryReattachAckPayload(
                offerLineageId = json.getString("offerLineageId"),
                recoveryAttemptId = json.optLong("recoveryAttemptId", 0L),
                obligationGeneration = json.optLong("obligationGeneration", 0L),
                deliveryAttemptId = json.optLong("deliveryAttemptId", 1L)
            )
        }.getOrNull()
    }
}
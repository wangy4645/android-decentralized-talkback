package com.talkback.core.model

import com.talkback.core.util.RecoveryReattachAckFields
import org.json.JSONObject

/** ADR-0035 — delivery confirmation for RECOVERY_REATTACH (not SDP answer). */
data class RecoveryReattachAckPayload(
    val offerLineageId: String,
    val recoveryAttemptId: Long,
    val obligationGeneration: Long,
    val deliveryAttemptId: Long,
    val handlerOutcome: RecoveryHandlerOutcome
) {
    fun encode(): String = JSONObject()
        .put("offerLineageId", offerLineageId)
        .put("recoveryAttemptId", recoveryAttemptId)
        .put("obligationGeneration", obligationGeneration)
        .put("deliveryAttemptId", deliveryAttemptId)
        .put("handlerOutcome", handlerOutcome.name)
        .toString()

    fun toAckFields(): RecoveryReattachAckFields = RecoveryReattachAckFields(
        offerLineageId = offerLineageId,
        recoveryAttemptId = recoveryAttemptId,
        obligationGeneration = obligationGeneration,
        deliveryAttemptId = deliveryAttemptId,
        handlerOutcome = handlerOutcome
    )

    companion object {
        fun decode(raw: String): RecoveryReattachAckPayload? = runCatching {
            val json = JSONObject(raw)
            val outcome = RecoveryHandlerOutcome.fromWire(json.optString("handlerOutcome", null))
                ?: return null
            RecoveryReattachAckPayload(
                offerLineageId = json.getString("offerLineageId"),
                recoveryAttemptId = json.optLong("recoveryAttemptId", 0L),
                obligationGeneration = json.optLong("obligationGeneration", 0L),
                deliveryAttemptId = json.optLong("deliveryAttemptId", 1L),
                handlerOutcome = outcome
            )
        }.getOrNull()
    }
}
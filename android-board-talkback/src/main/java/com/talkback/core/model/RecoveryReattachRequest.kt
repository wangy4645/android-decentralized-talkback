package com.talkback.core.model

/**
 * ADR-0021 D3: lineage material for conference recovery admission.
 * Wire encoding is [ConferenceRejoinPayload] on [SignalType.CONFERENCE_REJOIN].
 */
data class RecoveryReattachRequest(
    val conferenceId: String,
    val hostSessionId: String,
    val membershipEpoch: Long,
    val endpointId: String
) {
    fun toRejoinPayload(intent: ConferenceJoinIntent = ConferenceJoinIntent.RECOVERY_REATTACH): ConferenceRejoinPayload =
        ConferenceRejoinPayload(
            channelId = conferenceId,
            hostSessionId = hostSessionId,
            membershipEpoch = membershipEpoch,
            endpointId = endpointId,
            intent = intent
        )

    companion object {
        fun fromRejoinPayload(payload: ConferenceRejoinPayload): RecoveryReattachRequest? {
            if (payload.hostSessionId.isBlank()) return null
            return RecoveryReattachRequest(
                conferenceId = payload.channelId,
                hostSessionId = payload.hostSessionId,
                membershipEpoch = payload.membershipEpoch,
                endpointId = payload.endpointId
            )
        }
    }
}
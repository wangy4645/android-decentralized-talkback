package com.talkback.core.session

/**
 * Room-level ConferenceHealth. Aggregate of topology + media + recovery facts.
 * Does not own topology or recovery eligibility.
 *
 * Frozen: MEDIA_USABLE = true → user-facing ONLINE. No extra health states.
 */
data class ConferenceHealth(
    val conferenceId: String,
    val meshGeneration: Long,
    val topologyMode: ConferenceTopologyMode,
    val anchorHealthModel: Boolean,
    val mediaUsable: Boolean,
    val userFacingOnline: Boolean,
    val recoveryInFlight: Boolean,
    val recoveryFailed: Boolean,
    val l4RoomState: ConferenceL4RoomState = ConferenceL4RoomState.NOT_ESTABLISHED,
    val criticalTriggers: Set<ConferenceL4CriticalTrigger> = emptySet(),
) {
    companion object {
        fun from(
            snapshot: ConferenceTopologySnapshot,
            projection: ConferenceHealthProjection,
            l4: ConferenceL4AdjudicationResult? = null,
        ): ConferenceHealth {
            val l4RoomState = l4?.l4RoomState ?: if (projection.mediaUsable) {
                ConferenceL4RoomState.ONLINE
            } else {
                ConferenceL4RoomState.NOT_ESTABLISHED
            }
            val mediaUsable = l4?.mediaUsable ?: projection.mediaUsable
            val userFacingOnline = l4RoomState == ConferenceL4RoomState.ONLINE
            return ConferenceHealth(
                conferenceId = snapshot.conferenceId,
                meshGeneration = snapshot.meshGeneration,
                topologyMode = projection.topologyMode,
                anchorHealthModel = projection.anchorHealthModel,
                mediaUsable = mediaUsable,
                userFacingOnline = userFacingOnline,
                recoveryInFlight = projection.recoveryInFlight,
                recoveryFailed = projection.recoveryFailed,
                l4RoomState = l4RoomState,
                criticalTriggers = l4?.criticalTriggers.orEmpty(),
            )
        }
    }
}

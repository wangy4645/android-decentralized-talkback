package com.talkback.core.session

/**
 * AUTH-B-2: MediaUsable → [ConferenceL4RoomState].
 * C3 is a critical trigger input to MediaUsable — not a direct room-state shortcut.
 */
data class ConferenceL4AdjudicationInput(
    val sessionEstablished: Boolean,
    val mediaUsableResult: ConferenceL4MediaUsableResult,
    val recovery: RecoveryProgressFact = RecoveryProgressFact(),
)

data class ConferenceL4AdjudicationResult(
    val l4RoomState: ConferenceL4RoomState,
    val mediaUsable: Boolean,
    val criticalTriggers: Set<ConferenceL4CriticalTrigger>,
    val anchorAuthoritative: Boolean,
)

object ConferenceL4Adjudicator {

    fun adjudicate(input: ConferenceL4AdjudicationInput): ConferenceL4AdjudicationResult {
        val media = input.mediaUsableResult
        if (!input.sessionEstablished) {
            return result(ConferenceL4RoomState.NOT_ESTABLISHED, media)
        }
        if (media.mediaUsable) {
            return result(ConferenceL4RoomState.ONLINE, media)
        }
        if (isConferenceTerminal(input)) {
            return result(ConferenceL4RoomState.CONFERENCE_FAILED, media)
        }
        // Bootstrap / MESH / no critical fact yet: stay NOT_ESTABLISHED (Connecting), not DEGRADED.
        if (media.criticalTriggers.isEmpty()) {
            return result(ConferenceL4RoomState.NOT_ESTABLISHED, media)
        }
        return result(ConferenceL4RoomState.DEGRADED, media)
    }

    private fun isConferenceTerminal(input: ConferenceL4AdjudicationInput): Boolean {
        val recovery = input.recovery
        val triggers = input.mediaUsableResult.criticalTriggers
        val criticalPathFailed = recovery.failed &&
            (
                ConferenceL4CriticalTrigger.C1_ANCHOR_HEAR_SPEAK_IMPAIRED in triggers ||
                    ConferenceL4CriticalTrigger.C2_PROGRAM_RELAY_IMPAIRED in triggers ||
                    ConferenceL4CriticalTrigger.C4_TOPOLOGY_AUTHORITY_INVALID in triggers
                )
        return criticalPathFailed
    }

    private fun result(
        l4RoomState: ConferenceL4RoomState,
        media: ConferenceL4MediaUsableResult,
    ): ConferenceL4AdjudicationResult = ConferenceL4AdjudicationResult(
        l4RoomState = l4RoomState,
        mediaUsable = media.mediaUsable,
        criticalTriggers = media.criticalTriggers,
        anchorAuthoritative = media.anchorAuthoritative,
    )
}

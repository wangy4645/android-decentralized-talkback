package com.talkback.core.session

import com.talkback.core.session.failure.ConferenceFailureTerminal

/**
 * AUTH-B-1: Phase B [MediaUsable] evaluator — anchor authority for room (P-B3).
 */
data class ConferenceL4MediaUsableInput(
    val snapshot: ConferenceTopologySnapshot,
    val localModuleId: String,
    val sessionEstablished: Boolean,
    val mediaObservations: Set<MediaEdgeUsabilityObservation>,
    val failureTerminalsByModuleId: Map<String, ConferenceFailureTerminal> = emptyMap(),
    val programRelayUsable: Boolean? = null,
)

data class ConferenceL4MediaUsableResult(
    val mediaUsable: Boolean,
    val anchorAuthoritative: Boolean,
    val criticalTriggers: Set<ConferenceL4CriticalTrigger>,
    val anchorHearUsable: Boolean,
    val anchorSpeakUsable: Boolean,
)

object ConferenceL4MediaUsableContract {

    fun evaluate(input: ConferenceL4MediaUsableInput): ConferenceL4MediaUsableResult {
        val snapshot = input.snapshot
        val anchorId = snapshot.anchorId
        val anchorAuthoritative = snapshot.topologyMode == ConferenceTopologyMode.ANCHOR &&
            anchorId != null &&
            input.localModuleId == anchorId

        if (!input.sessionEstablished) {
            return ConferenceL4MediaUsableResult(
                mediaUsable = false,
                anchorAuthoritative = anchorAuthoritative,
                criticalTriggers = emptySet(),
                anchorHearUsable = false,
                anchorSpeakUsable = false,
            )
        }

        if (snapshot.topologyMode == ConferenceTopologyMode.MESH) {
            return ConferenceL4MediaUsableResult(
                mediaUsable = false,
                anchorAuthoritative = false,
                criticalTriggers = emptySet(),
                anchorHearUsable = false,
                anchorSpeakUsable = false,
            )
        }

        val anyUsableAdmitted = input.mediaObservations.any { obs ->
            obs.usable && obs.edge in snapshot.actualMediaEdges
        }
        val relayUsable = input.programRelayUsable ?: anyUsableAdmitted
        val anchorHear = relayUsable
        val anchorSpeak = relayUsable

        val criticalTriggers = ConferenceL4CriticalFacts.evaluate(
            snapshot = snapshot,
            failureTerminalsByModuleId = input.failureTerminalsByModuleId,
            anchorHearUsable = anchorHear,
            anchorSpeakUsable = anchorSpeak,
            programRelayUsable = input.programRelayUsable,
        )

        val mediaUsable = if (anchorAuthoritative) {
            evaluateAnchorRoomMediaUsable(
                criticalTriggers = criticalTriggers,
                anchorHear = anchorHear,
                anchorSpeak = anchorSpeak,
                relayUsable = relayUsable,
            )
        } else {
            evaluateSpokeLocalMediaUsable(
                snapshot = snapshot,
                localModuleId = input.localModuleId,
                observations = input.mediaObservations,
            )
        }

        return ConferenceL4MediaUsableResult(
            mediaUsable = mediaUsable,
            anchorAuthoritative = anchorAuthoritative,
            criticalTriggers = criticalTriggers,
            anchorHearUsable = anchorHear,
            anchorSpeakUsable = anchorSpeak,
        )
    }

    private fun evaluateAnchorRoomMediaUsable(
        criticalTriggers: Set<ConferenceL4CriticalTrigger>,
        anchorHear: Boolean,
        anchorSpeak: Boolean,
        relayUsable: Boolean,
    ): Boolean {
        if (ConferenceL4CriticalTrigger.C4_TOPOLOGY_AUTHORITY_INVALID in criticalTriggers) {
            return false
        }
        if (!anchorHear || !anchorSpeak || !relayUsable) {
            return false
        }
        if (ConferenceL4CriticalTrigger.C3_MULTI_IMPACT_SAME_CAUSE in criticalTriggers) {
            return false
        }
        return true
    }

    private fun evaluateSpokeLocalMediaUsable(
        snapshot: ConferenceTopologySnapshot,
        localModuleId: String,
        observations: Set<MediaEdgeUsabilityObservation>,
    ): Boolean {
        val localEdge = snapshot.actualMediaEdges.firstOrNull { edge ->
            localModuleId == edge.anchorModuleId || localModuleId == edge.remoteModuleId
        } ?: return false
        val obs = observations.firstOrNull { it.edge == localEdge } ?: return false
        return obs.usable
    }
}

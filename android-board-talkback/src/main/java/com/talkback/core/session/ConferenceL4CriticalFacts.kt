package com.talkback.core.session

import com.talkback.core.session.failure.ConferenceFailureTerminal

/**
 * P-B1 critical triggers — facts only; not [ConferenceL4RoomState].
 */
enum class ConferenceL4CriticalTrigger {
    C1_ANCHOR_HEAR_SPEAK_IMPAIRED,
    C2_PROGRAM_RELAY_IMPAIRED,
    C3_MULTI_IMPACT_SAME_CAUSE,
    C4_TOPOLOGY_AUTHORITY_INVALID,
}

object ConferenceL4CriticalFacts {

    fun evaluate(
        snapshot: ConferenceTopologySnapshot,
        failureTerminalsByModuleId: Map<String, ConferenceFailureTerminal>,
        anchorHearUsable: Boolean,
        anchorSpeakUsable: Boolean,
        programRelayUsable: Boolean?,
    ): Set<ConferenceL4CriticalTrigger> {
        val triggers = LinkedHashSet<ConferenceL4CriticalTrigger>()
        if (snapshot.topologyMode == ConferenceTopologyMode.ANCHOR) {
            if (snapshot.anchorId == null ||
                (snapshot.members.size > 1 && snapshot.actualMediaEdges.isEmpty())
            ) {
                triggers += ConferenceL4CriticalTrigger.C4_TOPOLOGY_AUTHORITY_INVALID
            }
            // C1/C2 only when relay is explicitly down — not cold-join "no media yet".
            if (programRelayUsable == false) {
                triggers += ConferenceL4CriticalTrigger.C2_PROGRAM_RELAY_IMPAIRED
                if (!anchorHearUsable || !anchorSpeakUsable) {
                    triggers += ConferenceL4CriticalTrigger.C1_ANCHOR_HEAR_SPEAK_IMPAIRED
                }
            }
            val blockedByCause = failureTerminalsByModuleId.values
                .filterIsInstance<ConferenceFailureTerminal.DomainBlocked>()
                .groupBy { it.attribution.causeEdgeKey }
            if (blockedByCause.any { (_, impacts) -> impacts.size >= 2 }) {
                triggers += ConferenceL4CriticalTrigger.C3_MULTI_IMPACT_SAME_CAUSE
            }
        }
        return triggers
    }
}

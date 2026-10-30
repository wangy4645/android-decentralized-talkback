package com.talkback.core.session.failure

import com.talkback.core.session.ConferenceParticipantDisplayState

/**
 * Phase A PR-A2: maps [ConferenceFailureTerminal] to L-participant projection only.
 */
object ConferenceFailureParticipantProjector {

    fun project(terminal: ConferenceFailureTerminal): ConferenceFailureParticipantProjection {
        val remoteModuleId = terminal.remoteModuleId()
            ?: error("terminal edgeKey must encode remote module id: ${terminal.edgeKey}")
        return when (terminal) {
            is ConferenceFailureTerminal.EdgeFailed -> ConferenceFailureParticipantProjection(
                remoteModuleId = remoteModuleId,
                displayState = ConferenceParticipantDisplayState.VISIBLE_EDGE_FAILED,
                blockedByDomain = false,
                causeEdgeKey = null,
                runtimeDomainRef = terminal.runtimeDomainRef,
                terminal = terminal,
            )
            is ConferenceFailureTerminal.DomainBlocked -> {
                val attr = terminal.attribution
                ConferenceFailureParticipantProjection(
                    remoteModuleId = remoteModuleId,
                    displayState = ConferenceParticipantDisplayState.VISIBLE_DOMAIN_BLOCKED,
                    blockedByDomain = true,
                    causeEdgeKey = attr.causeEdgeKey,
                    runtimeDomainRef = attr.runtimeDomainRef,
                    terminal = terminal,
                )
            }
        }
    }

    fun projectByModuleId(
        terminalsByModuleId: Map<String, ConferenceFailureTerminal>,
    ): Map<String, ConferenceFailureParticipantProjection> =
        terminalsByModuleId.mapNotNull { (moduleId, terminal) ->
            val projected = project(terminal)
            if (projected.remoteModuleId != moduleId) return@mapNotNull null
            moduleId to projected
        }.toMap()
}

package com.talkback.core.session.failure

import com.talkback.core.session.ConferenceParticipantDisplayState

/**
 * L-participant projection for Phase A CFC-1 terminals (PR-A2).
 * Classification fact → participant-visible state. No recovery / health / topology authority.
 */
data class ConferenceFailureParticipantProjection(
    val remoteModuleId: String,
    val displayState: ConferenceParticipantDisplayState,
    val blockedByDomain: Boolean,
    val causeEdgeKey: String?,
    val runtimeDomainRef: String,
    val terminal: ConferenceFailureTerminal,
) {
    init {
        when (terminal) {
            is ConferenceFailureTerminal.EdgeFailed -> {
                require(!blockedByDomain) { "L1 EDGE_FAILED must not set blockedByDomain" }
                require(displayState == ConferenceParticipantDisplayState.VISIBLE_EDGE_FAILED) {
                    "L1 must project to VISIBLE_EDGE_FAILED"
                }
            }
            is ConferenceFailureTerminal.DomainBlocked -> {
                require(blockedByDomain) { "L2 DOMAIN_BLOCKED must set blockedByDomain" }
                require(displayState == ConferenceParticipantDisplayState.VISIBLE_DOMAIN_BLOCKED) {
                    "L2 must project to VISIBLE_DOMAIN_BLOCKED"
                }
                require(!causeEdgeKey.isNullOrBlank()) { "L2 requires causeEdgeKey" }
            }
        }
    }
}

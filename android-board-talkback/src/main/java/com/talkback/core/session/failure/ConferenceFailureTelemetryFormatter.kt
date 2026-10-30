package com.talkback.core.session.failure

/**
 * Phase A PR-A3: stable log-line formatting for field adjudication.
 */
object ConferenceFailureTelemetryFormatter {

    fun formatEvent(event: ConferenceFailureTelemetryEvent): String = buildString {
        append("CONFERENCE_FAILURE_")
        append(
            when (event.stage) {
                ConferenceFailureTelemetryStage.CAUSE_INPUT_RECORDED -> "CAUSE_INPUT"
                ConferenceFailureTelemetryStage.L1_CLASSIFIED -> "L1_CLASSIFIED"
                ConferenceFailureTelemetryStage.L2_CLASSIFIED -> "L2_CLASSIFIED"
                ConferenceFailureTelemetryStage.PROJECTED -> "PROJECTED"
            },
        )
        append(" session=").append(event.generationScope.conferenceSessionId)
        append(" meshGeneration=").append(event.generationScope.meshGeneration)
        event.generationScope.pcGeneration?.let { append(" pcGeneration=").append(it) }
        append(" edgeKey=").append(event.edgeKey)
        append(" runtimeDomainRef=").append(event.runtimeDomainRef)
        append(" observedAtMs=").append(event.observedAtMs)
        event.holderEdgeKey?.let { append(" holderEdgeKey=").append(it) }
        if (event.leaseBusyInputObserved) append(" leaseBusyInput=true")
        event.causeFact?.let { append(" causeFact=").append(it.name) }
        event.causeEdgeKey?.let { append(" causeEdgeKey=").append(it) }
        event.terminal?.let { append(" terminal=").append(terminalLabel(it)) }
        event.projection?.let {
            append(" displayState=").append(it.displayState.name)
            append(" blockedByDomain=").append(it.blockedByDomain)
        }
    }

    fun formatChainValidated(scenario: String, causeEdgeKey: String, impactEdgeKey: String?): String =
        buildString {
            append("CONFERENCE_FAILURE_CHAIN_VALIDATED scenario=").append(scenario)
            append(" causeEdgeKey=").append(causeEdgeKey)
            impactEdgeKey?.let { append(" impactEdgeKey=").append(it) }
        }

    private fun terminalLabel(terminal: ConferenceFailureTerminal): String =
        when (terminal) {
            is ConferenceFailureTerminal.EdgeFailed -> "EDGE_FAILED"
            is ConferenceFailureTerminal.DomainBlocked -> "DOMAIN_BLOCKED"
        }
}

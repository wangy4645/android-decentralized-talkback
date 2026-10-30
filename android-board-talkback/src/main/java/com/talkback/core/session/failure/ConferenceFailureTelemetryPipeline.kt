package com.talkback.core.session.failure

/**
 * Phase A PR-A3: wires A1 classification + A2 projection into an auditable telemetry chain.
 *
 * No Coordinator · no B2-1 semantic change · no recovery/topology/health authority.
 */
object ConferenceFailureTelemetryPipeline {

    fun interface Clock {
        fun nowMs(): Long
    }

    val systemClock = Clock { System.currentTimeMillis() }

    data class ScenarioEResult(
        val terminal: ConferenceFailureTerminal.EdgeFailed,
        val projection: ConferenceFailureParticipantProjection,
        val chain: ConferenceFailureTelemetryChain,
    ) {
        val auditLines: List<String> get() = chain.formatLines()
    }

    data class ScenarioDResult(
        val causeTerminal: ConferenceFailureTerminal.EdgeFailed?,
        val impactTerminal: ConferenceFailureTerminal.DomainBlocked,
        val impactProjection: ConferenceFailureParticipantProjection,
        val chain: ConferenceFailureTelemetryChain,
    ) {
        val auditLines: List<String> get() = chain.formatLines()
    }

    /**
     * Scenario E: cause edge observation → L1 → projection.
     */
    fun emitScenarioE(
        causeObservation: ConferenceFailureObservation,
        clock: Clock = systemClock,
    ): ScenarioEResult? {
        val events = mutableListOf<ConferenceFailureTelemetryEvent>()
        val t0 = clock.nowMs()
        events += ConferenceFailureTelemetryEvent(
            stage = ConferenceFailureTelemetryStage.CAUSE_INPUT_RECORDED,
            edgeKey = causeObservation.edgeKey,
            observedAtMs = t0,
            generationScope = causeObservation.generationScope,
            runtimeDomainRef = causeObservation.runtimeDomainRef,
            holderEdgeKey = causeObservation.activeLeaseHolderEdgeKey ?: causeObservation.edgeKey,
            leaseBusyInputObserved = false,
        )
        val terminal = ConferenceFailureClassifier.classifyEdgeFailure(causeObservation) ?: return null
        val t1 = clock.nowMs()
        events += ConferenceFailureTelemetryEvent(
            stage = ConferenceFailureTelemetryStage.L1_CLASSIFIED,
            edgeKey = terminal.edgeKey,
            observedAtMs = t1,
            generationScope = terminal.generationScope,
            runtimeDomainRef = terminal.runtimeDomainRef,
            terminal = terminal,
        )
        val projection = ConferenceFailureParticipantProjector.project(terminal)
        val t2 = clock.nowMs()
        events += ConferenceFailureTelemetryEvent(
            stage = ConferenceFailureTelemetryStage.PROJECTED,
            edgeKey = terminal.edgeKey,
            observedAtMs = t2,
            generationScope = terminal.generationScope,
            runtimeDomainRef = terminal.runtimeDomainRef,
            terminal = terminal,
            projection = projection,
        )
        val chain = ConferenceFailureTelemetryChain(scenario = "E", events = events.toList())
        val validation = chain.validate()
        if (validation is ConferenceFailureTelemetryValidation.Invalid) return null
        return ScenarioEResult(terminal, projection, chain)
    }

    /**
     * Scenario D: cause fact → L2 DOMAIN_BLOCKED (full attribution) → projection.
     *
     * [causeObservation] supplies cause-side symptoms; [impactObservation] supplies impact edge.
     * LEASE_BUSY may be visible on impact observation but causeFact must be derived separately.
     */
    fun emitScenarioD(
        causeObservation: ConferenceFailureObservation,
        impactObservation: ConferenceFailureObservation,
        knownCausePhase: ConferenceFailureCausePhase? = null,
        clock: Clock = systemClock,
    ): ScenarioDResult? {
        require(causeObservation.generationScope == impactObservation.generationScope) {
            "cause and impact must share generationScope"
        }
        val events = mutableListOf<ConferenceFailureTelemetryEvent>()
        val causeDerivation = ConferenceFailureClassifier.deriveCause(impactObservation) ?: return null

        val tCause = clock.nowMs()
        events += ConferenceFailureTelemetryEvent(
            stage = ConferenceFailureTelemetryStage.CAUSE_INPUT_RECORDED,
            edgeKey = causeDerivation.causeEdgeKey,
            observedAtMs = tCause,
            generationScope = causeObservation.generationScope,
            runtimeDomainRef = causeObservation.runtimeDomainRef,
            causeFact = causeDerivation.causeFact,
            holderEdgeKey = causeDerivation.causeEdgeKey,
            leaseBusyInputObserved = impactObservation.leaseWaitHolderEdgeKey != null,
        )

        val causeTerminal = ConferenceFailureClassifier.classifyEdgeFailure(causeObservation)
        if (causeTerminal != null) {
            events += ConferenceFailureTelemetryEvent(
                stage = ConferenceFailureTelemetryStage.L1_CLASSIFIED,
                edgeKey = causeTerminal.edgeKey,
                observedAtMs = clock.nowMs(),
                generationScope = causeTerminal.generationScope,
                runtimeDomainRef = causeTerminal.runtimeDomainRef,
                causeFact = causeDerivation.causeFact,
                causeEdgeKey = causeDerivation.causeEdgeKey,
                terminal = causeTerminal,
            )
        }

        val impactTerminal = ConferenceFailureClassifier.classifyDomainContention(
            impactObservation = impactObservation,
            causeObservation = causeObservation,
            knownCausePhase = knownCausePhase,
        ) ?: return null

        val tImpact = clock.nowMs()
        val attr = impactTerminal.attribution
        events += ConferenceFailureTelemetryEvent(
            stage = ConferenceFailureTelemetryStage.L2_CLASSIFIED,
            edgeKey = attr.impactEdgeKey,
            observedAtMs = tImpact,
            generationScope = attr.generationScope,
            runtimeDomainRef = attr.runtimeDomainRef,
            causeFact = attr.causeFact,
            causeEdgeKey = attr.causeEdgeKey,
            holderEdgeKey = attr.causeEdgeKey,
            leaseBusyInputObserved = impactObservation.leaseWaitHolderEdgeKey != null,
            terminal = impactTerminal,
        )

        val impactProjection = ConferenceFailureParticipantProjector.project(impactTerminal)
        events += ConferenceFailureTelemetryEvent(
            stage = ConferenceFailureTelemetryStage.PROJECTED,
            edgeKey = attr.impactEdgeKey,
            observedAtMs = clock.nowMs(),
            generationScope = attr.generationScope,
            runtimeDomainRef = attr.runtimeDomainRef,
            causeFact = attr.causeFact,
            causeEdgeKey = attr.causeEdgeKey,
            terminal = impactTerminal,
            projection = impactProjection,
        )

        val chain = ConferenceFailureTelemetryChain(scenario = "D", events = events.toList())
        val validation = chain.validate()
        if (validation is ConferenceFailureTelemetryValidation.Invalid) return null
        return ScenarioDResult(causeTerminal, impactTerminal, impactProjection, chain)
    }
}

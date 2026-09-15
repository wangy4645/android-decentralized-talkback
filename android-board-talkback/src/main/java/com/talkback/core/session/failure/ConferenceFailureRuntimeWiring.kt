package com.talkback.core.session.failure

import com.talkback.core.session.ConferenceNativeExecutionDomain
import com.talkback.core.session.ConferenceSrdNativeDomainObservability
import java.util.concurrent.ConcurrentHashMap

/**
 * PR-A5: maps B2-1 / SRD observation facts into the existing CFC-1 PARTIAL pipeline.
 *
 * B1: Scenario E EDGE_FAILED also creates edge recovery intent (control plane only).
 */
class ConferenceFailureRuntimeWiring(
    private val clock: ConferenceFailureTelemetryPipeline.Clock =
        ConferenceFailureTelemetryPipeline.systemClock,
    domainSnapshotProvider: () -> ConferenceNativeExecutionDomain.HolderSnapshot? = { null },
    private val logLine: (String) -> Unit = {},
    recoveryIntentService: EdgeSrdRecoveryIntentService? = null,
) {

    private val recoveryIntents =
        recoveryIntentService ?: EdgeSrdRecoveryIntentService(
            domainSnapshotProvider = domainSnapshotProvider,
            clock = { clock.nowMs() },
            logLine = logLine,
        )

    private val terminalsBySession =
        ConcurrentHashMap<String, ConcurrentHashMap<String, ConferenceFailureTerminal>>()

    fun terminalsForSession(sessionId: String): Map<String, ConferenceFailureTerminal> =
        terminalsBySession[sessionId]?.toMap() ?: emptyMap()

    fun recoveryIntentsForSession(sessionId: String): List<EdgeSrdRecoveryIntent> =
        recoveryIntents.intentsForSession(sessionId)

    fun clearSession(sessionId: String) {
        recoveryIntents.clearSession(sessionId)
        terminalsBySession.remove(sessionId)
    }

    /**
     * Scenario D path: impact edge saw LEASE_BUSY with known holder (B2-1 fact).
     * No B1 recovery intent on impact or cause via this path.
     */
    fun onLeaseBusy(
        sessionId: String,
        impactModuleId: String,
        impactEdgeKey: String,
        holderEdgeKey: String,
        meshGeneration: Long,
        pcGeneration: Long? = null,
        causeHangingObserved: Boolean = true,
    ) {
        val scope = ConferenceFailureGenerationScope(
            conferenceSessionId = sessionId,
            meshGeneration = meshGeneration,
            pcGeneration = pcGeneration,
        )
        val causeRemote = remoteModuleIdFromEdgeKey(holderEdgeKey) ?: return
        val causeObs = ConferenceFailureObservation(
            edgeKey = holderEdgeKey,
            generationScope = scope,
            hangingObserved = causeHangingObserved,
            activeLeaseHolderEdgeKey = holderEdgeKey,
        )
        val impactObs = ConferenceFailureObservation(
            edgeKey = impactEdgeKey,
            generationScope = scope,
            leaseWaitHolderEdgeKey = holderEdgeKey,
        )
        val result = ConferenceFailureTelemetryPipeline.emitScenarioD(
            causeObservation = causeObs,
            impactObservation = impactObs,
            knownCausePhase = if (causeHangingObserved) {
                ConferenceFailureCausePhase.HANGING_OBSERVED
            } else {
                ConferenceFailureCausePhase.EDGE_FAILED
            },
            clock = clock,
        ) ?: return
        store(sessionId, causeRemote, result.causeTerminal)
        store(sessionId, impactModuleId, result.impactTerminal)
        result.auditLines.forEach(logLine)
    }

    /**
     * Scenario E path: self-attributed hang / timeout on cause edge.
     */
    fun onSelfAttributedHang(
        sessionId: String,
        moduleId: String,
        edgeKey: String,
        meshGeneration: Long,
        pcGeneration: Long? = null,
        conferenceGeneration: Long? = null,
        offerLineageId: String? = null,
        realizationAttemptId: String? = null,
        srdTimeout: Boolean = false,
        hangingObserved: Boolean = true,
        edgeLocalFailure: Boolean = false,
    ) {
        val scope = ConferenceFailureGenerationScope(
            conferenceSessionId = sessionId,
            meshGeneration = meshGeneration,
            pcGeneration = pcGeneration,
        )
        val obs = ConferenceFailureObservation(
            edgeKey = edgeKey,
            generationScope = scope,
            hangingObserved = hangingObserved,
            srdTimeoutObserved = srdTimeout,
            edgeLocalFailureObserved = edgeLocalFailure,
            activeLeaseHolderEdgeKey = edgeKey,
            runtimeDomainRef = ConferenceSrdNativeDomainObservability.DOMAIN_ID,
        )
        val result = ConferenceFailureTelemetryPipeline.emitScenarioE(obs, clock) ?: return
        store(sessionId, moduleId, result.terminal)
        result.auditLines.forEach(logLine)
        recoveryIntents.onScenarioEdgeFailed(
            EdgeSrdRecoveryTrigger(
                terminal = result.terminal,
                remoteModuleId = moduleId,
                conferenceGeneration = conferenceGeneration,
                offerLineageId = offerLineageId,
                realizationAttemptId = realizationAttemptId,
                causeFact = causeFactFromObservation(obs),
            )
        )
    }

    private fun store(
        sessionId: String,
        moduleId: String,
        terminal: ConferenceFailureTerminal?,
    ) {
        if (terminal == null) return
        terminalsBySession
            .getOrPut(sessionId) { ConcurrentHashMap() }[moduleId] = terminal
    }

    private fun causeFactFromObservation(obs: ConferenceFailureObservation): String =
        when {
            obs.srdTimeoutObserved -> "SRD_TIMEOUT"
            obs.hangingObserved -> "HANGING_OBSERVED"
            obs.edgeLocalFailureObserved -> "EDGE_LOCAL_FAILURE"
            obs.negotiationStuckObserved -> "NEGOTIATION_STUCK"
            else -> "EDGE_FAILED"
        }
}

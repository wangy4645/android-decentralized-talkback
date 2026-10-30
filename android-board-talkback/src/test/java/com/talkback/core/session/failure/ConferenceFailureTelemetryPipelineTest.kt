package com.talkback.core.session.failure

import com.talkback.core.session.ConferenceParticipantDisplayState
import com.talkback.core.session.ConferenceSrdNativeDomainObservability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceFailureTelemetryPipelineTest {

    private val scope = ConferenceFailureGenerationScope(
        conferenceSessionId = "sess-1",
        meshGeneration = 1L,
        pcGeneration = 2L,
    )

    private var clockMs = 1_000L
    private val clock = ConferenceFailureTelemetryPipeline.Clock { clockMs++ }

    private fun edgeKey(remote: String) = edgeKeyForRemote("sess-1", remote)

    private fun causeObs(remote: String = "M03") = ConferenceFailureObservation(
        edgeKey = edgeKey(remote),
        generationScope = scope,
        hangingObserved = true,
        activeLeaseHolderEdgeKey = edgeKey(remote),
    )

    private fun impactObs(holder: String = "M03") = ConferenceFailureObservation(
        edgeKey = edgeKey("M04"),
        generationScope = scope,
        leaseWaitHolderEdgeKey = edgeKey(holder),
    )

    @Test
    fun scenarioE_causeToEdgeFailedToProjection() {
        val result = ConferenceFailureTelemetryPipeline.emitScenarioE(causeObs(), clock)!!
        assertEquals(ConferenceParticipantDisplayState.VISIBLE_EDGE_FAILED, result.projection.displayState)
        assertTrue(result.auditLines.any { it.contains("CONFERENCE_FAILURE_L1_CLASSIFIED") })
        assertTrue(result.auditLines.any { it.contains("CONFERENCE_FAILURE_PROJECTED") })
        assertTrue(result.chain.validate() is ConferenceFailureTelemetryValidation.Valid)
    }

    @Test
    fun scenarioD_causeToDomainBlockedToProjection_fullAttribution() {
        val result = ConferenceFailureTelemetryPipeline.emitScenarioD(
            causeObservation = causeObs(),
            impactObservation = impactObs(),
            knownCausePhase = ConferenceFailureCausePhase.HANGING_OBSERVED,
            clock = clock,
        )!!
        assertEquals(ConferenceParticipantDisplayState.VISIBLE_DOMAIN_BLOCKED, result.impactProjection.displayState)
        assertTrue(result.impactProjection.blockedByDomain)
        assertEquals(edgeKey("M03"), result.impactTerminal.attribution.causeEdgeKey)
        assertEquals(ConferenceFailureCauseFact.LEASE_HELD_BY_CAUSE, result.impactTerminal.attribution.causeFact)
        assertEquals(ConferenceSrdNativeDomainObservability.DOMAIN_ID, result.impactTerminal.attribution.runtimeDomainRef)
        assertTrue(result.auditLines.any { it.contains("causeFact=LEASE_HELD_BY_CAUSE") })
        assertTrue(result.auditLines.any { it.contains("CONFERENCE_FAILURE_L2_CLASSIFIED") })
        assertTrue(result.chain.validate() is ConferenceFailureTelemetryValidation.Valid)
    }

    @Test
    fun leaseBusyAlone_doesNotEmitDomainBlocked() {
        val impactOnly = ConferenceFailureObservation(
            edgeKey = edgeKey("M04"),
            generationScope = scope,
        )
        assertNull(
            ConferenceFailureTelemetryPipeline.emitScenarioD(causeObs(), impactOnly, clock = clock),
        )
    }

    @Test
    fun domainBlocked_requiresCauseEdgeKey_inChain() {
        val result = ConferenceFailureTelemetryPipeline.emitScenarioD(
            causeObs(),
            impactObs(),
            clock = clock,
        )!!
        val l2 = result.chain.events.first { it.stage == ConferenceFailureTelemetryStage.L2_CLASSIFIED }
        assertNotNull(l2.causeEdgeKey)
        assertNotNull(l2.causeFact)
        assertTrue(l2.runtimeDomainRef.isNotBlank())
    }

    @Test
    fun causePrecedesImpact_inTelemetryOrdering() {
        val result = ConferenceFailureTelemetryPipeline.emitScenarioD(
            causeObs(),
            impactObs(),
            clock = clock,
        )!!
        val causeAt = result.chain.events.first { it.stage == ConferenceFailureTelemetryStage.CAUSE_INPUT_RECORDED }.observedAtMs
        val impactAt = result.chain.events.first { it.stage == ConferenceFailureTelemetryStage.L2_CLASSIFIED }.observedAtMs
        assertTrue(causeAt <= impactAt)
    }

    @Test
    fun staleGeneration_rejectedAtPipeline() {
        val mismatchedImpact = impactObs().copy(
            generationScope = scope.copy(meshGeneration = 99L),
        )
        try {
            ConferenceFailureTelemetryPipeline.emitScenarioD(causeObs(), mismatchedImpact, clock = clock)
            assertTrue(false)
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun chainValidation_rejectsMissingProjection() {
        val badChain = ConferenceFailureTelemetryChain(
            scenario = "D",
            events = listOf(
                ConferenceFailureTelemetryEvent(
                    stage = ConferenceFailureTelemetryStage.L2_CLASSIFIED,
                    edgeKey = edgeKey("M04"),
                    observedAtMs = 1L,
                    generationScope = scope,
                    runtimeDomainRef = ConferenceSrdNativeDomainObservability.DOMAIN_ID,
                    causeFact = ConferenceFailureCauseFact.LEASE_HELD_BY_CAUSE,
                    causeEdgeKey = edgeKey("M03"),
                    terminal = ConferenceFailureTerminal.DomainBlocked(
                        ConferenceFailureDomainBlockedAttribution(
                            impactEdgeKey = edgeKey("M04"),
                            runtimeDomainRef = ConferenceSrdNativeDomainObservability.DOMAIN_ID,
                            causeEdgeKey = edgeKey("M03"),
                            causeFact = ConferenceFailureCauseFact.LEASE_HELD_BY_CAUSE,
                            generationScope = scope,
                            causePhase = ConferenceFailureCausePhase.HANGING_OBSERVED,
                        ),
                    ),
                ),
            ),
        )
        val validation = badChain.validate()
        assertTrue(validation is ConferenceFailureTelemetryValidation.Invalid)
        assertTrue((validation as ConferenceFailureTelemetryValidation.Invalid).errors.any { it.contains("PROJECTED") })
    }
}

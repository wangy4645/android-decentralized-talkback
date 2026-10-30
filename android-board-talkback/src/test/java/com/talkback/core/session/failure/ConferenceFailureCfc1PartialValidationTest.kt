package com.talkback.core.session.failure

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.ConferenceParticipantDisplayState
import com.talkback.core.session.ConferenceParticipantProjector
import com.talkback.core.session.ConferenceRuntimeProjector
import com.talkback.core.session.ConferenceSrdNativeDomainObservability
import com.talkback.core.session.InviteState
import com.talkback.core.session.MediaState
import com.talkback.core.session.MemberView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-A4: CFC-1 PARTIAL (Scenario E ∧ D) integration validation.
 *
 * Wires A1 classification + A2 projection + A3 telemetry — no new architecture layer.
 * Field-adjacent desk gate; not P0.1g Acceptance 1 · not CFC-1 FULL.
 */
class ConferenceFailureCfc1PartialValidationTest {

    private val scope = ConferenceFailureGenerationScope(
        conferenceSessionId = "sess-1",
        meshGeneration = 1L,
        pcGeneration = 2L,
    )

    private var clockMs = 10_000L
    private val clock = ConferenceFailureTelemetryPipeline.Clock { clockMs++ }

    private fun conferenceEdgeKey(remote: String) = edgeKeyForRemote("sess-1", remote)

  // --- 4p RCA-B reference fixture (M01 anchor) ---

    private fun causeObservationM03() = ConferenceFailureObservation(
        edgeKey = conferenceEdgeKey("M03"),
        generationScope = scope,
        hangingObserved = true,
        activeLeaseHolderEdgeKey = conferenceEdgeKey("M03"),
    )

    private fun impactObservationM04() = ConferenceFailureObservation(
        edgeKey = conferenceEdgeKey("M04"),
        generationScope = scope,
        leaseWaitHolderEdgeKey = conferenceEdgeKey("M03"),
    )

    private fun participantProjectorInput(
        failureTerminals: Map<String, ConferenceFailureTerminal> = emptyMap(),
    ): ConferenceParticipantProjector.Input {
        val m01 = ModuleId("M01")
        return ConferenceParticipantProjector.Input(
            localModuleId = m01,
            localKey = "M01-E01",
            sessionAccepted = true,
            roster = listOf(
                EndpointAddress(m01, EndpointId("E01")),
                EndpointAddress(ModuleId("M02"), EndpointId("E01")),
                EndpointAddress(ModuleId("M03"), EndpointId("E01")),
                EndpointAddress(ModuleId("M04"), EndpointId("E01")),
            ),
            memberViews = listOf(
                MemberView("M02-E01", "M02", InviteState.ACCEPTED, MediaState.CONNECTED),
                MemberView("M03-E01", "M03", InviteState.ACCEPTED, MediaState.CONNECTING),
                MemberView("M04-E01", "M04", InviteState.ACCEPTED, MediaState.CONNECTING),
            ),
            failureTerminalsByModuleId = failureTerminals,
        )
    }

    // --- Scenario E ---

    @Test
    fun scenarioE_selfAttributedFailure_endToEnd() {
        val pipeline = ConferenceFailureTelemetryPipeline.emitScenarioE(
            causeObservationM03(),
            clock,
        )!!
        assertEquals(ConferenceParticipantDisplayState.VISIBLE_EDGE_FAILED, pipeline.projection.displayState)
        assertFalse(pipeline.projection.blockedByDomain)
        assertTrue(pipeline.chain.validate() is ConferenceFailureTelemetryValidation.Valid)
        assertTrue(pipeline.auditLines.any { it.contains("CONFERENCE_FAILURE_CHAIN_VALIDATED scenario=E") })

        val runtime = ConferenceRuntimeProjector.project(
            ConferenceRuntimeProjector.Input(
                transitionTerminalReady = true,
                connectedRemoteMediaCount = 1,
                sessionAccepted = true,
                awaitingAdditionalParticipants = false,
                isConferenceHost = true,
            ),
        )
        assertFalse(runtime.conferenceDegraded)
    }

    // --- Scenario D ---

    @Test
    fun scenarioD_domainContention_fullAttribution_endToEnd() {
        val pipeline = ConferenceFailureTelemetryPipeline.emitScenarioD(
            causeObservation = causeObservationM03(),
            impactObservation = impactObservationM04(),
            knownCausePhase = ConferenceFailureCausePhase.HANGING_OBSERVED,
            clock = clock,
        )!!
        val attr = pipeline.impactTerminal.attribution
        assertEquals(conferenceEdgeKey("M04"), attr.impactEdgeKey)
        assertEquals(conferenceEdgeKey("M03"), attr.causeEdgeKey)
        assertEquals(ConferenceFailureCauseFact.LEASE_HELD_BY_CAUSE, attr.causeFact)
        assertEquals(ConferenceFailureDomainBlockedAttribution.CONTENTION_KIND, attr.contentionKind)
        assertEquals(ConferenceFailureDomainBlockedAttribution.BLOCK_REASON, attr.blockReason)
        assertEquals(ConferenceSrdNativeDomainObservability.DOMAIN_ID, attr.runtimeDomainRef)
        assertEquals(scope, attr.generationScope)

        assertEquals(ConferenceParticipantDisplayState.VISIBLE_DOMAIN_BLOCKED, pipeline.impactProjection.displayState)
        assertTrue(pipeline.impactProjection.blockedByDomain)
        assertNotNull(pipeline.impactProjection.causeEdgeKey)
        assertTrue(pipeline.chain.validate() is ConferenceFailureTelemetryValidation.Valid)
        assertTrue(pipeline.auditLines.any { it.contains("CONFERENCE_FAILURE_CHAIN_VALIDATED scenario=D") })
    }

    @Test
    fun scenarioD_impactEdge_notClassifiedAsEdgeFailed() {
        val pipeline = ConferenceFailureTelemetryPipeline.emitScenarioD(
            causeObservationM03(),
            impactObservationM04(),
            clock = clock,
        )!!
        assertNotEquals(
            ConferenceFailureTerminal.EdgeFailed::class,
            pipeline.impactTerminal::class,
        )
        assertNull(
            ConferenceFailureClassifier.classifyEdgeFailure(
                impactObservationM04().copy(srdTimeoutObserved = true),
            ),
        )
    }

    @Test
    fun scenarioD_m02Operational_m04BlockedInProjection() {
        val pipeline = ConferenceFailureTelemetryPipeline.emitScenarioD(
            causeObservationM03(),
            impactObservationM04(),
            clock = clock,
        )!!
        val terminals = buildMap {
            pipeline.causeTerminal?.let { put("M03", it) }
            put("M04", pipeline.impactTerminal)
        }
        val base = ConferenceParticipantProjector.project(participantProjectorInput())
        val withFailures = ConferenceParticipantProjector.project(participantProjectorInput(terminals))

        assertEquals(base.joinedParticipantCount, withFailures.joinedParticipantCount)
        assertEquals(base.rosterParticipants, withFailures.rosterParticipants)

        val m02 = withFailures.visibleParticipants.first { it.moduleId == "M02" }
        assertEquals(ConferenceParticipantDisplayState.VISIBLE_CONNECTED, m02.displayState)
        assertEquals(null, m02.failureProjection)

        val m04 = withFailures.visibleParticipants.first { it.moduleId == "M04" }
        assertEquals(ConferenceParticipantDisplayState.VISIBLE_DOMAIN_BLOCKED, m04.displayState)
        assertNotEquals(ConferenceParticipantDisplayState.VISIBLE_CONNECTING, m04.displayState)
        assertEquals(conferenceEdgeKey("M03"), m04.failureProjection!!.causeEdgeKey)
    }

    // --- CFC-1 PARTIAL adjudication ---

    @Test
    fun cfc1PartialPass_scenarioE_and_D_together() {
        val scenarioE = ConferenceFailureTelemetryPipeline.emitScenarioE(causeObservationM03(), clock)
        val scenarioD = ConferenceFailureTelemetryPipeline.emitScenarioD(
            causeObservationM03(),
            impactObservationM04(),
            knownCausePhase = ConferenceFailureCausePhase.HANGING_OBSERVED,
            clock = clock,
        )
        assertNotNull(scenarioE)
        assertNotNull(scenarioD)

        val verdict = adjudicateCfc1PartialPass(scenarioE!!, scenarioD!!)
        assertEquals(PASS_WORDING_PHASE_A, verdict.phaseAPassLine)
        assertEquals(PASS_WORDING_CFC1_PARTIAL, verdict.cfc1PartialPassLine)
        assertEquals(PASS_WORDING_ACCEPTANCE1_HOLD, verdict.acceptance1HoldLine)

        assertNotEquals("CFC-1 FULL PASS", verdict.cfc1PartialPassLine)
        assertFalse(verdict.cfc1PartialPassLine.contains("P0.1g PASS"))
        assertFalse(verdict.cfc1PartialPassLine.contains("native isolation"))
    }

    @Test
    fun cfc1Partial_notFull_notScenarioC_notP01g() {
        val scenarioD = ConferenceFailureTelemetryPipeline.emitScenarioD(
            causeObservationM03(),
            impactObservationM04(),
            clock = clock,
        )!!
        assertTrue(scenarioD.chain.scenario == "D")
        assertFalse(scenarioD.auditLines.any { it.contains("scenario=C") })
        assertFalse(scenarioD.auditLines.any { it.contains("DOMAIN_FAILED") })
        assertFalse(scenarioD.auditLines.any { it.contains("CONFERENCE_FAILED") })
        assertFalse(scenarioD.auditLines.any { it.contains("MediaUsable") })
    }

    private data class Cfc1PartialVerdict(
        val phaseAPassLine: String,
        val cfc1PartialPassLine: String,
        val acceptance1HoldLine: String,
    )

    private fun adjudicateCfc1PartialPass(
        scenarioE: ConferenceFailureTelemetryPipeline.ScenarioEResult,
        scenarioD: ConferenceFailureTelemetryPipeline.ScenarioDResult,
    ): Cfc1PartialVerdict {
        require(scenarioE.chain.validate() is ConferenceFailureTelemetryValidation.Valid)
        require(scenarioD.chain.validate() is ConferenceFailureTelemetryValidation.Valid)
        require(scenarioE.projection.displayState == ConferenceParticipantDisplayState.VISIBLE_EDGE_FAILED)
        require(scenarioD.impactProjection.displayState == ConferenceParticipantDisplayState.VISIBLE_DOMAIN_BLOCKED)
        val attr = scenarioD.impactTerminal.attribution
        require(attr.causeEdgeKey.isNotBlank())
        require(attr.runtimeDomainRef.isNotBlank())
        require(attr.causeFact == ConferenceFailureCauseFact.LEASE_HELD_BY_CAUSE ||
            attr.causeFact == ConferenceFailureCauseFact.NATIVE_DOMAIN_OBSTRUCTED)
        return Cfc1PartialVerdict(
            phaseAPassLine = PASS_WORDING_PHASE_A,
            cfc1PartialPassLine = PASS_WORDING_CFC1_PARTIAL,
            acceptance1HoldLine = PASS_WORDING_ACCEPTANCE1_HOLD,
        )
    }

    companion object {
        const val PASS_WORDING_PHASE_A = "Phase A PASS"
        const val PASS_WORDING_CFC1_PARTIAL = "CFC-1 PARTIAL PASS (Scenario E ∧ D)"
        const val PASS_WORDING_ACCEPTANCE1_HOLD = "P0.1g Acceptance 1 remains HOLD — ORIGINAL"
    }
}

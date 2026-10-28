package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 4-7 — Field shadow evidence (F7-A–F7-E). */
class ShadowFieldEvidenceContractTest {

    private val harness = ShadowFieldEvidenceHarness()

    @Test
    fun f7a_coldJoin_collectsMembershipTopologyAndMismatchTaxonomy() {
        val roster = MeetingRuntimeObservationFixture.EIGHT_MEMBERS
        val session = harness.conferenceSession(roster = roster)
        val timelineBefore = harness.sessionTimeline()
        val report = harness.runFieldCard(
            runCard = ShadowFieldRunCard.F7A_COLD_JOIN,
            fieldRunId = "f7a-cold-join-001",
            session = session,
            participants = roster,
            timeline = listOf(
                CoordinatorObservationEventKind.JOIN_ACCEPTED,
                CoordinatorObservationEventKind.ICE_CONNECTED,
                CoordinatorObservationEventKind.MEDIA_STATE_CHANGED
            )
        )
        assertTrue(ShadowFieldEvidenceContract.validateEvidenceAvailable(report))
        assertEquals(FieldObservationSource.HARNESS, report.observationSource)
        assertEquals(roster.size, report.participantCount)
        assertTrue(report.mismatchDetail.countsByCategory.keys.containsAll(ShadowFieldMismatchCategory.entries))
        assertTrue(harness.sessionTimeline() == timelineBefore)
        assertTrue(
            ShadowFieldEvidenceContract.validateNoControlOutputs(
                harness.collector.observationPoints()
            )
        )
    }

    @Test
    fun f7b_steadyState_edgeIdentityStableAndOrderingClean() {
        val roster = MeetingRuntimeObservationFixture.EIGHT_MEMBERS
        val session = harness.conferenceSession(roster = roster)
        val report = harness.runSteadyStateCard(session, participants = roster, steadyEventCount = 60)
        assertEquals(ShadowFieldRunCard.F7B_STEADY_STATE, report.runCard)
        assertEquals(61, report.qualification.runtimeHealth.eventCount)
        assertEquals(0, report.qualification.result.eventOrderingViolationCount)
        assertTrue(report.qualification.runtimeHealth.reportSignatureStable)
    }

    @Test
    fun f7c_leaveRejoin_recordsEdgeIdentityMismatchCategoryWhenApplicable() {
        val roster = MeetingRuntimeObservationFixture.EIGHT_MEMBERS
        val session = harness.conferenceSession(roster = roster)
        session.meshCompletedModules.remove("M05")
        val report = harness.runFieldCard(
            runCard = ShadowFieldRunCard.F7C_LEAVE_REJOIN,
            fieldRunId = "f7c-leave-rejoin-001",
            session = session,
            participants = roster,
            timeline = listOf(
                CoordinatorObservationEventKind.JOIN_ACCEPTED,
                CoordinatorObservationEventKind.ICE_CONNECTED,
                CoordinatorObservationEventKind.MEMBER_LEFT,
                CoordinatorObservationEventKind.JOIN_ACCEPTED,
                CoordinatorObservationEventKind.ICE_CONNECTED
            ),
            mutateBeforeRun = { it.meshGeneration = 3L }
        )
        assertTrue(report.qualification.topologyComparisons.any { it.generationDiff != null })
        assertNotNull(report.mismatchDetail.countsByCategory)
    }

    @Test
    fun f7d_recoveryObservation_legacyVsShadowWouldActionNoExecution() {
        val roster = MeetingRuntimeObservationFixture.EIGHT_MEMBERS
        val session = harness.conferenceSession(roster = roster)
        session.participant("M05").media = MediaState.RECONNECTING
        val report = harness.runFieldCard(
            runCard = ShadowFieldRunCard.F7D_RECOVERY_OBSERVATION,
            fieldRunId = "f7d-recovery-001",
            session = session,
            participants = roster,
            timeline = listOf(
                CoordinatorObservationEventKind.ICE_DISCONNECTED,
                CoordinatorObservationEventKind.RECOVERY_STARTED,
                CoordinatorObservationEventKind.RECOVERY_FINISHED
            )
        )
        assertTrue(report.qualification.recoveryPredictionSummaries.isNotEmpty())
        assertTrue(
            ShadowFieldEvidenceContract.validateNoControlOutputs(
                harness.collector.observationPoints()
            )
        )
    }

    @Test
    fun f7e_tenParticipantLifecycle_evidenceAvailable() {
        val roster = ShadowFieldEvidenceContract.TEN_NODE_ROSTER
        val session = harness.conferenceSession(roster = roster)
        val report = harness.runTenNodeLifecycle(session, roster)
        assertEquals(10, report.participantCount)
        assertEquals(ShadowFieldRunCard.F7E_MULTI_PARTICIPANT_LIFECYCLE, report.runCard)
        assertTrue(report.qualification.runtimeHealth.eventCount >= roster.size)
        assertTrue(ShadowFieldEvidenceContract.validateEvidenceAvailable(report))
    }

    @Test
    fun mismatchRecord_containsCategoryLegacyAndShadowFacts() {
        val roster = MeetingRuntimeObservationFixture.EIGHT_MEMBERS
        val session = harness.conferenceSession(roster = roster)
        session.meshCompletedModules.add("M99")
        val report = harness.runFieldCard(
            runCard = ShadowFieldRunCard.F7E_MULTI_PARTICIPANT_LIFECYCLE,
            fieldRunId = "f7-mismatch-detail-001",
            session = session,
            participants = roster,
            timeline = listOf(
                CoordinatorObservationEventKind.ICE_CONNECTED,
                CoordinatorObservationEventKind.MEDIA_STATE_CHANGED
            )
        )
        assertTrue(report.qualification.result.projectionMismatchCount >= 1)
        assertTrue(report.mismatchDetail.records.isNotEmpty())
        val record = report.mismatchDetail.records.first()
        assertTrue(record.legacyFact.contains("legacyEdges="))
        assertTrue(record.shadowFact.contains("shadowEdges="))
        assertTrue(record.category in ShadowFieldMismatchCategory.entries)
    }

    @Test
    fun evidenceSignature_deterministicForSameFieldTimeline() {
        val roster = MeetingRuntimeObservationFixture.EIGHT_MEMBERS
        val session = harness.conferenceSession(roster = roster)
        val timeline = listOf(
            CoordinatorObservationEventKind.JOIN_ACCEPTED,
            CoordinatorObservationEventKind.ICE_CONNECTED,
            CoordinatorObservationEventKind.MEDIA_STATE_CHANGED
        )
        val first = harness.runFieldCard(
            runCard = ShadowFieldRunCard.F7A_COLD_JOIN,
            fieldRunId = "f7-det-1",
            session = session,
            participants = roster,
            timeline = timeline
        )
        harness.collector.publisher.clear()
        harness.collector.publisher.enabled = true
        val second = harness.runFieldCard(
            runCard = ShadowFieldRunCard.F7A_COLD_JOIN,
            fieldRunId = "f7-det-2",
            session = session,
            participants = roster,
            timeline = timeline
        )
        assertEquals(
            ShadowFieldEvidenceContract.evidenceSignature(first).substringAfter(':').substringAfter(':'),
            ShadowFieldEvidenceContract.evidenceSignature(second).substringAfter(':').substringAfter(':')
        )
    }

    @Test
    fun inducedTopologyMismatch_classifiedNotControl() {
        val roster = MeetingRuntimeObservationFixture.EIGHT_MEMBERS
        val session = harness.conferenceSession(roster = roster)
        session.meshCompletedModules.add("M99")
        val report = harness.runFieldCard(
            runCard = ShadowFieldRunCard.F7A_COLD_JOIN,
            fieldRunId = "f7-topology-mismatch",
            session = session,
            participants = roster,
            timeline = listOf(CoordinatorObservationEventKind.ICE_CONNECTED)
        )
        assertFalse(report.mismatchDetail.records.all { it.category == ShadowFieldMismatchCategory.UNKNOWN_FACT })
        assertTrue(
            report.mismatchDetail.records.any {
                it.category == ShadowFieldMismatchCategory.TOPOLOGY_FACT_MISMATCH ||
                    it.category == ShadowFieldMismatchCategory.EDGE_IDENTITY_MISMATCH
            }
        )
        assertTrue(
            ShadowFieldEvidenceContract.validateNoControlOutputs(
                harness.collector.observationPoints()
            )
        )
    }
}

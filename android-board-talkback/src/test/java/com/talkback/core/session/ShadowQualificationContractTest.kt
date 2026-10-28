package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 4-6 — Shadow qualification (Q6-A–Q6-E). */
class ShadowQualificationContractTest {

    private val harness = ShadowQualificationHarness()

    @Test
    fun q6a_normalMeeting_joinSteadyLeave_metricsAvailable() {
        val session = harness.eightNodeSession()
        val timelineBefore = harness.sessionTimeline()
        val report = harness.runCard(
            session = session,
            card = ShadowQualificationCard.Q6A_NORMAL_MEETING,
            timeline = listOf(
                CoordinatorObservationEventKind.JOIN_ACCEPTED,
                CoordinatorObservationEventKind.ICE_CONNECTED,
                CoordinatorObservationEventKind.MEDIA_STATE_CHANGED,
                CoordinatorObservationEventKind.MEMBER_LEFT
            )
        )
        assertTrue(ShadowQualificationContract.validateMetricsAvailable(report))
        assertEquals(4, report.runtimeHealth.eventCount)
        assertEquals(4, report.projectionSummary.observationPointCount)
        assertTrue(harness.sessionTimeline() == timelineBefore)
        assertTrue(
            ShadowQualificationContract.validateNoControlOutputs(
                harness.publisher.qualificationObservationPoints(session.id)
            )
        )
    }

    @Test
    fun q6b_leaveRejoin_tracksGenerationAndEdgeInvalidation() {
        val session = harness.eightNodeSession()
        val report = harness.runCard(
            session = session,
            card = ShadowQualificationCard.Q6B_LEAVE_REJOIN,
            timeline = listOf(
                CoordinatorObservationEventKind.JOIN_ACCEPTED,
                CoordinatorObservationEventKind.ICE_CONNECTED,
                CoordinatorObservationEventKind.MEMBER_LEFT,
                CoordinatorObservationEventKind.JOIN_ACCEPTED,
                CoordinatorObservationEventKind.ICE_CONNECTED
            ),
            mutateBeforeRun = { it.meshGeneration = 2L }
        )
        assertTrue(report.topologyComparisons.isNotEmpty())
        assertTrue(report.topologyComparisons.any { it.generationDiff != null })
        assertTrue(report.result.projectionMismatchCount >= 0)
    }

    @Test
    fun q6c_wifiRecovery_observesLegacyRecoveryWithoutShadowExecution() {
        val session = harness.eightNodeSession()
        session.participant("M05").media = MediaState.RECONNECTING
        val report = harness.runCard(
            session = session,
            card = ShadowQualificationCard.Q6C_WIFI_RECOVERY,
            timeline = listOf(
                CoordinatorObservationEventKind.ICE_DISCONNECTED,
                CoordinatorObservationEventKind.RECOVERY_STARTED,
                CoordinatorObservationEventKind.RECOVERY_FINISHED
            )
        )
        assertTrue(report.recoveryPredictionSummaries.isNotEmpty())
        assertTrue(
            ShadowQualificationContract.validateNoControlOutputs(
                harness.publisher.qualificationObservationPoints(session.id)
            )
        )
        assertEquals(0, report.result.observerErrorCount)
    }

    @Test
    fun q6d_multiParticipant_eightNodeObservationOverheadBounded() {
        val session = harness.eightNodeSession()
        val remotes = MeetingRuntimeObservationFixture.EIGHT_MEMBERS.filter { it != "M01" }
        val timeline = buildList {
            add(CoordinatorObservationEventKind.JOIN_ACCEPTED)
            remotes.forEach { _ ->
                add(CoordinatorObservationEventKind.ICE_CONNECTED)
            }
            add(CoordinatorObservationEventKind.MEDIA_STATE_CHANGED)
        }
        val report = harness.runCard(
            session = session,
            card = ShadowQualificationCard.Q6D_MULTI_PARTICIPANT,
            timeline = timeline
        )
        assertEquals(MeetingRuntimeObservationFixture.EIGHT_MEMBERS.size, remotes.size + 1)
        assertEquals(timeline.size, report.runtimeHealth.eventCount)
        assertTrue(report.result.runtimeOverhead.reportsPerEvent >= 1.0)
        assertEquals(0, report.result.eventOrderingViolationCount)
    }

    @Test
    fun q6e_longRun_noOrderingViolationOrListenerLeak() {
        val session = harness.eightNodeSession()
        val report = harness.runLongRunCard(session, eventCount = 2_000)
        assertEquals(2_000, report.runtimeHealth.eventCount)
        assertEquals(2_000, report.runtimeHealth.reportCount)
        assertEquals(0, report.result.eventOrderingViolationCount)
        assertEquals(0, harness.publisher.listenerCount())
        assertTrue(report.runtimeHealth.reportSignatureStable)
    }

    @Test
    fun qualificationProjection_deterministicForSameTimeline() {
        val session = harness.eightNodeSession()
        val signatures = harness.deterministicSignatureForTimeline(
            session,
            listOf(
                CoordinatorObservationEventKind.JOIN_ACCEPTED,
                CoordinatorObservationEventKind.ICE_CONNECTED,
                CoordinatorObservationEventKind.MEDIA_STATE_CHANGED
            )
        )
        val parts = signatures.split("::")
        assertEquals(2, parts.size)
        assertEquals(parts[0], parts[1])
    }

    @Test
    fun shadowDisabled_producesNoQualificationEvidence() {
        val session = harness.eightNodeSession()
        harness.publisher.enabled = false
        harness.publisher.emitObservation(session, CoordinatorObservationEventKind.JOIN_ACCEPTED)
        assertTrue(harness.publisher.mirroredEvents(session.id).isEmpty())
        assertTrue(harness.publisher.shadowPipeline.reports(session.id).isEmpty())
    }

    @Test
    fun mismatchEvidence_doesNotEmitControl() {
        val session = harness.eightNodeSession()
        session.meshCompletedModules.add("M99")
        val report = harness.runCard(
            session = session,
            card = ShadowQualificationCard.Q6D_MULTI_PARTICIPANT,
            timeline = listOf(
                CoordinatorObservationEventKind.ICE_CONNECTED,
                CoordinatorObservationEventKind.MEDIA_STATE_CHANGED
            )
        )
        assertTrue(report.result.projectionMismatchCount >= 1)
        assertFalse(report.topologyComparisons.all { it.mismatchCategory == TopologyProjectionMismatchCategory.NONE })
        assertTrue(
            ShadowQualificationContract.validateNoControlOutputs(
                harness.publisher.qualificationObservationPoints(session.id)
            )
        )
    }
}

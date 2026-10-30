package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 4-8 — Field run evidence export (operational). */
class ShadowFieldRunEvidenceContractTest {

    private val harness = ShadowFieldEvidenceHarness()

    @Test
    fun caseA_coldJoin_exportsFullEvidenceBundle() {
        val roster = MeetingRuntimeObservationFixture.EIGHT_MEMBERS
        val session = harness.conferenceSession(roster = roster)
        val startedAt = System.currentTimeMillis()
        harness.collector.beginRun(
            runCard = ShadowFieldRunCard.F7A_COLD_JOIN,
            fieldRunId = "p48-case-a-001",
            session = session,
            participants = roster,
            timelineBefore = harness.sessionTimeline()
        )
        listOf(
            CoordinatorObservationEventKind.JOIN_ACCEPTED,
            CoordinatorObservationEventKind.ICE_CONNECTED,
            CoordinatorObservationEventKind.MEDIA_STATE_CHANGED
        ).forEach { kind ->
            harness.collector.publisher.emitObservation(session, kind, remoteModuleId = "M05")
        }
        val metadata = ShadowFieldRunMetadata(
            runId = "p48-case-a-001",
            case = ShadowFieldRunCase.CASE_A_COLD_JOIN,
            startedAtMs = startedAt,
            endedAtMs = startedAt,
            apkVersion = "test-apk",
            deviceList = listOf("M01", "M02", "M03"),
            participantCount = roster.size,
            networkEnvironment = "lab-wifi",
            sessionId = session.id
        )
        val bundle = harness.collector.finalizeRunWithMetadata(
            metadata = metadata,
            participants = roster,
            sessionTimelineAfter = harness.sessionTimeline(),
            observationSource = FieldObservationSource.DEVICE,
            emitLog = false
        )!!
        assertEquals(ShadowFieldRunCase.CASE_A_COLD_JOIN, bundle.metadata.case)
        assertEquals(FieldObservationSource.DEVICE, bundle.evidenceReport.observationSource)
        assertTrue(bundle.mismatchSummary.countsByCategory.keys.containsAll(ShadowFieldMismatchCategory.entries))
        assertTrue(bundle.stopConditionViolations.isEmpty())
        assertTrue(ShadowFieldRunEvidenceExporter.evidenceText(bundle).contains("SHADOW_FIELD_RUN_EVIDENCE"))
    }

    @Test
    fun caseMapping_alignsRunCardWithPhase48Cases() {
        assertEquals(
            ShadowFieldRunCard.F7A_COLD_JOIN,
            ShadowFieldRunEvidenceContract.caseToRunCard(ShadowFieldRunCase.CASE_A_COLD_JOIN)
        )
        assertEquals(
            ShadowFieldRunCard.F7D_RECOVERY_OBSERVATION,
            ShadowFieldRunEvidenceContract.caseToRunCard(ShadowFieldRunCase.CASE_D_WIFI_FLAP)
        )
    }

    @Test
    fun stopCondition_detectsControlContaminationWhenMetricsNonZero() {
        val contaminatedPoint = ShadowQualificationObservationPoint(
            event = null,
            report = ShadowComparisonReport(
                topologyProjectionMatch = false,
                edgeSetDiff = ShadowEdgeSetDiff(),
                generationDiff = null,
                mismatchDetected = true
            ),
            wiringResult = RuntimeShadowWiringResult(
                report = ShadowComparisonReport(
                    topologyProjectionMatch = false,
                    edgeSetDiff = ShadowEdgeSetDiff(),
                    generationDiff = null,
                    mismatchDetected = true
                ),
                projection = null,
                metrics = RuntimeShadowControlMetrics(publishTopologyCount = 1),
                timelineSignature = "x"
            )
        )
        val violations = ShadowFieldRunEvidenceContract.detectStopConditions(listOf(contaminatedPoint))
        assertTrue(violations.contains(ShadowFieldRunEvidenceContract.CONTROL_CONTAMINATION))
        assertTrue(violations.contains(ShadowFieldRunEvidenceContract.AUTHORITY_VIOLATION_PUBLISH))
    }

    @Test
    fun exportText_containsMismatchRecordsAndRuntimeHealth() {
        val roster = MeetingRuntimeObservationFixture.EIGHT_MEMBERS
        val session = harness.conferenceSession(roster = roster)
        session.meshCompletedModules.add("M99")
        val report = harness.runFieldCard(
            runCard = ShadowFieldRunCard.F7C_LEAVE_REJOIN,
            fieldRunId = "p48-export-001",
            session = session,
            participants = roster,
            timeline = listOf(CoordinatorObservationEventKind.ICE_CONNECTED)
        )
        val metadata = ShadowFieldRunMetadata(
            runId = "p48-export-001",
            case = ShadowFieldRunCase.CASE_C_LEAVE_REJOIN,
            startedAtMs = System.currentTimeMillis() - 1_000,
            endedAtMs = System.currentTimeMillis(),
            apkVersion = "test-apk",
            deviceList = listOf("HTUBB21B09220661", "2d73067a", "MDX0220416001963"),
            participantCount = roster.size,
            networkEnvironment = "field-wifi",
            sessionId = session.id
        )
        val bundle = ShadowFieldRunEvidenceContract.assembleBundle(
            metadata = metadata,
            participants = roster,
            report = report,
            observationPoints = harness.collector.observationPoints()
        )
        val text = ShadowFieldRunEvidenceExporter.evidenceText(bundle)
        assertTrue(text.contains("mismatchCategory."))
        assertTrue(text.contains("runtimeHealth.eventCount="))
        assertTrue(text.contains("END_SHADOW_FIELD_RUN_EVIDENCE"))
        assertFalse(bundle.stopConditionViolations.contains(ShadowFieldRunEvidenceContract.CONTROL_CONTAMINATION))
    }
}

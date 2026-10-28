package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 4-5 — Coordinator observation mirror (C5-A–C5-E). */
class CoordinatorObservationPublisherContractTest {

    @Test
    fun c5a_eventMirror_preservesOrderAndKind() {
        val harness = CoordinatorObservationPublisherHarness()
        val session = harness.conferenceSession()
        val timeline = listOf(
            CoordinatorObservationEventKind.JOIN_ACCEPTED,
            CoordinatorObservationEventKind.ICE_CONNECTED,
            CoordinatorObservationEventKind.MEDIA_STATE_CHANGED,
            CoordinatorObservationEventKind.RECOVERY_STARTED,
            CoordinatorObservationEventKind.MEMBER_LEFT
        )
        val mirrored = harness.mirrorTimeline(session, timeline)
        assertEquals(timeline.size, mirrored.size)
        assertEquals(timeline, mirrored.map { it.kind })
        assertEquals((1L..timeline.size.toLong()).toList(), mirrored.map { it.sequence })
    }

    @Test
    fun c5b_noFeedbackLoop_sessionTimelineAndMetricsUnchanged() {
        val harness = CoordinatorObservationPublisherHarness()
        val session = harness.conferenceSession()
        val before = harness.sessionTimeline()
        harness.mirrorTimeline(
            session,
            listOf(
                CoordinatorObservationEventKind.JOIN_ACCEPTED,
                CoordinatorObservationEventKind.ICE_CONNECTED,
                CoordinatorObservationEventKind.RECOVERY_STARTED
            )
        )
        val after = harness.sessionTimeline()
        assertEquals(before, after)
        val reports = harness.publisher.shadowPipeline.reports(session.id)
        assertTrue(reports.all { report ->
            report.recoveryActionWouldBe == null || report.recoveryActionBlockedReason != null ||
                report.topologyProjectionMatch || !report.mismatchDetected
        })
    }

    @Test
    fun c5c_longRunHarness_noOrderingViolationOrListenerLeak() {
        val harness = CoordinatorObservationPublisherHarness()
        val session = harness.conferenceSession()
        val stats = harness.simulateLongRun(session, eventCount = 2_000)
        assertEquals(2_000, stats.eventCount)
        assertEquals(2_000, stats.uniqueSequences)
        assertTrue(stats.monotonic)
        assertEquals(1, stats.listenerCount)
        assertEquals(2_000, stats.reportCount)
        harness.publisher.detachSession(session.id)
        assertEquals(0, harness.publisher.listenerCount())
    }

    @Test
    fun c5d_projectionStability_sameTimelineSameSignatures() {
        val harness = CoordinatorObservationPublisherHarness()
        val session = harness.conferenceSession()
        val timeline = listOf(
            CoordinatorObservationEventKind.JOIN_ACCEPTED,
            CoordinatorObservationEventKind.ICE_CONNECTED,
            CoordinatorObservationEventKind.MEDIA_STATE_CHANGED
        )
        val first = harness.mirrorTimeline(session, timeline)
        val firstSignatures = harness.publisher.shadowPipeline.reportSignatures(session.id).toList()
        harness.publisher.clear()
        harness.publisher.enabled = true
        val second = harness.mirrorTimeline(session, timeline)
        val secondSignatures = harness.publisher.shadowPipeline.reportSignatures(session.id).toList()
        assertEquals(first.map { it.kind }, second.map { it.kind })
        assertEquals(firstSignatures, secondSignatures)
    }

    @Test
    fun c5e_shadowDisabled_producesNoMirroredEvents() {
        val harness = CoordinatorObservationPublisherHarness()
        val session = harness.conferenceSession()
        harness.publisher.enabled = false
        harness.publisher.emitObservation(
            session = session,
            kind = CoordinatorObservationEventKind.JOIN_ACCEPTED
        )
        assertTrue(harness.publisher.mirroredEvents(session.id).isEmpty())
        assertTrue(CoordinatorObservationEmitter.currentPublisher() == null)
    }

    @Test
    fun telemetryOnly_mismatchDoesNotEmitControlMetrics() {
        val harness = CoordinatorObservationPublisherHarness()
        val session = harness.conferenceSession()
        session.meshCompletedModules.add("M99")
        harness.publisher.enabled = true
        harness.publisher.emitObservation(session, CoordinatorObservationEventKind.ICE_CONNECTED, remoteModuleId = "M99")
        val report = harness.publisher.shadowPipeline.reports(session.id).last()
        assertTrue(report.mismatchDetected || !report.topologyProjectionMatch)
        assertEquals(MeetingRuntimeShadowContract.SHADOW_MISMATCH_DETECTED, report.mismatchCode)
    }
}

package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 4-4 — Runtime shadow wiring (W4A–W4E). */
class RuntimeShadowWiringContractTest {

    @Test
    fun w4a_realSourceSnapshot_producesShadowReport() {
        val harness = RuntimeShadowWiringHarness()
        val session = harness.eightNodeSession()
        val source = TalkbackSessionRuntimeObservationSource(session)
        val bundle = source.snapshotObservation()
        assertNotNull(bundle)
        assertEquals(8, bundle!!.membership.memberModuleIds.size)
        val result = harness.runShadow(session)
        assertNotNull(result.projection)
        assertTrue(result.report.topologyProjectionMatch || !result.report.mismatchDetected)
    }

    @Test
    fun w4b_noControlPath_publishAndExecutorCountsZero() {
        val harness = RuntimeShadowWiringHarness()
        val result = harness.runShadow(harness.eightNodeSession())
        assertEquals(0, result.metrics.publishTopologyCount)
        assertEquals(0, result.metrics.executorInvocationCount)
        assertTrue(RuntimeShadowWiringContract.validateTelemetryOnly(result))
    }

    @Test
    fun w4c_existingMeetingTimelineUnchanged() {
        val harness = RuntimeShadowWiringHarness()
        val session = harness.eightNodeSession()
        val source = TalkbackSessionRuntimeObservationSource(session)
        harness.seedAuthorityForSession(session)
        val before = source.sessionTimelineSignature()
        RuntimeShadowWiringContract.runShadowFromSource(
            source = source,
            authority = harness.authority,
            validator = harness.validator,
            timelineSignature = before
        )
        val after = source.sessionTimelineSignature()
        assertTrue(RuntimeShadowWiringContract.validateTimelineUnchanged(before, after))
    }

    @Test
    fun w4d_observationCompleteness_iceConnectedNotMediaActive() {
        val harness = RuntimeShadowWiringHarness()
        val session = harness.eightNodeSession()
        session.participant("M06").media = MediaState.CONNECTING
        session.meshCompletedModules.add("M06")
        val bundle = TalkbackSessionRuntimeObservationSource(session).snapshotObservation()!!
        val gaps = RuntimeShadowWiringContract.iceConnectedWithoutMediaReady(bundle)
        assertTrue(gaps.any { it.contains("M06") })
        assertTrue(gaps.any { it.contains(RuntimeShadowWiringContract.ICE_CONNECTED_NOT_MEDIA_ACTIVE) })
    }

    @Test
    fun w4e_mismatchTelemetryOnly_noRecoveryOrTopologyControl() {
        val harness = RuntimeShadowWiringHarness()
        val result = harness.runShadowWithMismatch(harness.eightNodeSession())
        assertTrue(result.report.mismatchDetected)
        assertEquals(MeetingRuntimeShadowContract.SHADOW_MISMATCH_DETECTED, result.report.mismatchCode)
        assertTrue(RuntimeShadowWiringContract.validateTelemetryOnly(result))
        assertEquals(0, result.metrics.topologyChangesEmitted)
        assertEquals(0, result.metrics.recoveryActionsEmitted)
    }
}

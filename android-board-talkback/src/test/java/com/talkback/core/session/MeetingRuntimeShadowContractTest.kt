package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 4-3 — Meeting runtime shadow validation (S4A–S4E). */
class MeetingRuntimeShadowContractTest {

    private val conferenceId = MeetingRuntimeObservationFixture.CONFERENCE_ID

    @Test
    fun s4a_cleanJoinShadow_matchesExpectedTopology() {
        val harness = MeetingRuntimeShadowHarness()
        val report = harness.runCleanJoinShadow(conferenceId)
        assertTrue(report.topologyProjectionMatch)
        assertFalse(report.mismatchDetected)
        assertEquals(
            EightNodeControlledLifecycleContract.anchorStarEdgeCount(8),
            harness.currentSnapshot(conferenceId)!!.actualMediaEdges.size
        )
    }

    @Test
    fun s4b_leaveRejoinProjection_oldEdgeDiffersStaleFactRejected() {
        val harness = MeetingRuntimeShadowHarness()
        harness.seedAuthorityEightNodeJoin(conferenceId)
        val oldRef = TopologyMediaEdgeRef.fromSnapshot(
            harness.currentSnapshot(conferenceId)!!,
            MediaEdge("M01", "M05")
        )
        harness.seedAuthorityAfterLeave(conferenceId)
        harness.rejoinAuthority(conferenceId)
        val newRef = TopologyMediaEdgeRef.fromSnapshot(
            harness.currentSnapshot(conferenceId)!!,
            MediaEdge("M01", "M05")
        )
        assertNotEquals(oldRef.mediaEdgeId, newRef.mediaEdgeId)
        val staleFact = RecoveryExecutionFact.Outcome(
            mediaEdgeId = oldRef.mediaEdgeId,
            action = RecoveryEdgeAction.ICE_RESTART,
            kind = RecoveryExecutionOutcomeKind.ACTION_COMPLETED,
            meshGeneration = oldRef.meshGeneration,
            anchorEpoch = oldRef.anchorEpoch
        )
        assertTrue(
            EightNodeControlledLifecycleContract.staleFactCannotResurrectEdge(
                staleFact,
                harness.currentSnapshot(conferenceId)!!
            )
        )
    }

    @Test
    fun s4c_recoveryShadow_producesDecisionWithoutExecutorCalls() {
        val harness = MeetingRuntimeShadowHarness()
        val snap = harness.seedAuthorityEightNodeJoin(conferenceId)
        val before = harness.validator.captureBaseline(harness.authority, conferenceId)
        val projection = harness.validator.runRecoveryShadow(
            MeetingRuntimeObservationFixture.recoverySignalForRemote(snap, "M05"),
            snap
        )
        assertNotNull(projection.decision)
        assertEquals(0, harness.validator.captureBaseline(harness.authority, conferenceId).executorCallCount)
        assertTrue(harness.validator.validateZeroImpact(before, harness.authority))
    }

    @Test
    fun s4d_topologyMismatch_reportsMismatchWithoutPublish() {
        val harness = MeetingRuntimeShadowHarness()
        harness.seedAuthorityEightNodeJoin(conferenceId)
        val before = harness.validator.captureBaseline(harness.authority, conferenceId)
        val (report, _) = harness.validator.runTopologyShadow(
            harness.authority,
            MeetingRuntimeObservationFixture.mismatchedLegacyMesh(
                MeetingRuntimeObservationFixture.EIGHT_MEMBERS,
                conferenceId
            )
        )
        assertTrue(report.mismatchDetected)
        assertEquals(MeetingRuntimeShadowContract.SHADOW_MISMATCH_DETECTED, report.mismatchCode)
        assertTrue(report.edgeSetDiff.onlyInLegacyProjection.isNotEmpty())
        assertTrue(harness.validator.validateZeroImpact(before, harness.authority))
        assertEquals(0, harness.validator.captureBaseline(harness.authority, conferenceId).publishCount)
    }

    @Test
    fun s4e_noSideEffect_baselineUnchangedAfterShadow() {
        val harness = MeetingRuntimeShadowHarness()
        harness.seedAuthorityEightNodeJoin(conferenceId)
        val before = harness.validator.captureBaseline(harness.authority, conferenceId)
        harness.validator.runTopologyShadow(
            harness.authority,
            MeetingRuntimeObservationFixture.cleanJoinObservations(conferenceId)
        )
        val snap = harness.currentSnapshot(conferenceId)!!
        harness.validator.runRecoveryShadow(
            MeetingRuntimeObservationFixture.recoverySignalForRemote(snap, "M07"),
            snap
        )
        val after = harness.validator.captureBaseline(harness.authority, conferenceId)
        assertTrue(MeetingRuntimeShadowContract.validateZeroProductionImpact(before, after))
        assertEquals(before.authoritySnapshot, after.authoritySnapshot)
        assertEquals(before.recordedFacts, after.recordedFacts)
    }

    @Test
    fun forbiddenShadowPublish_returnsReasonWithoutMutatingAuthority() {
        val harness = MeetingRuntimeShadowHarness()
        harness.seedAuthorityEightNodeJoin(conferenceId)
        val before = harness.currentSnapshot(conferenceId)
        val reason = harness.validator.attemptForbiddenPublish()
        assertTrue(reason.contains("forbidden"))
        assertEquals(before, harness.currentSnapshot(conferenceId))
    }
}

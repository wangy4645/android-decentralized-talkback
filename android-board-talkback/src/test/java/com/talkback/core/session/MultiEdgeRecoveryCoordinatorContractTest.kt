package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 3-4 — Multi-edge recovery coordination (M4A–M4F). */
class MultiEdgeRecoveryCoordinatorContractTest {

    @Test
    fun m4a_threeEdges_independentRecovery() {
        val harness = MultiEdgeRecoveryHarness(maxConcurrentSlots = 3)
        val snap = harness.anchorSnapshot()
        val result = harness.coordinateRemotes(snap, listOf("M02", "M03", "M04"))
        val admitted = result.outcomes.filterIsInstance<MultiEdgeRecoveryEdgeOutcome.Admitted>()
        assertEquals(3, admitted.size)
        assertEquals(3, admitted.map { it.mediaEdgeId }.toSet().size)
        assertEquals(3, result.state.inFlightByMediaEdgeId.size)
    }

    @Test
    fun m4b_sameEdgeDuplicate_singleFlightRejected() {
        val harness = MultiEdgeRecoveryHarness(maxConcurrentSlots = 2)
        val snap = harness.anchorSnapshot()
        val input = harness.decisionInputForRemote(snap, "M03")
        val first = harness.coordinator.coordinate(listOf(input))
        assertEquals(1, first.outcomes.filterIsInstance<MultiEdgeRecoveryEdgeOutcome.Admitted>().size)
        val second = harness.coordinator.coordinate(listOf(input))
        val rejected = second.outcomes.filterIsInstance<MultiEdgeRecoveryEdgeOutcome.Rejected>().single()
        assertEquals(MultiEdgeRecoveryRejectCode.SINGLE_FLIGHT_BUSY, rejected.code)
    }

    @Test
    fun m4c_boundedSlots_thirdDeferred() {
        val harness = MultiEdgeRecoveryHarness(maxConcurrentSlots = 2)
        val snap = harness.anchorSnapshot()
        val result = harness.coordinateRemotes(snap, listOf("M02", "M03", "M04"))
        assertEquals(2, result.outcomes.filterIsInstance<MultiEdgeRecoveryEdgeOutcome.Admitted>().size)
        assertEquals(1, result.outcomes.filterIsInstance<MultiEdgeRecoveryEdgeOutcome.Deferred>().size)
        assertEquals(2, result.state.slotPool.occupiedCount())
    }

    @Test
    fun m4d_staleIsolation_otherEdgeUnchanged() {
        val harness = MultiEdgeRecoveryHarness(maxConcurrentSlots = 3)
        val snap = harness.anchorSnapshot(meshGeneration = 12L)
        val staleM03 = harness.decisionInputForRemote(
            snapshot = snap,
            remote = "M03",
            meshGenerationOverride = 10L
        )
        val validM05 = harness.decisionInputForRemote(snap, "M05")
        val result = harness.coordinator.coordinate(listOf(staleM03, validM05))
        assertTrue(result.outcomes.any { it is MultiEdgeRecoveryEdgeOutcome.DropStale && it.mediaEdgeId.contains("M03") })
        assertTrue(result.outcomes.any { it is MultiEdgeRecoveryEdgeOutcome.Admitted && it.mediaEdgeId.contains("M05") })
        assertFalse(result.state.factsByMediaEdgeId.isNotEmpty())
        assertEquals(1, result.state.inFlightByMediaEdgeId.size)
    }

    @Test
    fun m4e_unauthorizedExecutionBlocked() {
        val rejected = MultiEdgeRecoveryCoordinationContract.validateForbiddenCoordinatorAction(
            MultiEdgeRecoveryForbiddenAction.EXECUTE_RECOVERY
        )
        assertEquals(MultiEdgeRecoveryRejectCode.FORBIDDEN_COORDINATOR_ACTION, rejected.code)
        assertTrue(rejected.reason.contains("forbidden"))
    }

    @Test
    fun m4f_crossEdgeOrderingIndependence() {
        val harness = MultiEdgeRecoveryHarness(maxConcurrentSlots = 3)
        val snap = harness.anchorSnapshot()
        val orderAbc = harness.coordinateRemotes(snap, listOf("M02", "M03", "M04"))
        harness.coordinator.reset(maxConcurrentSlots = 3)
        val orderCba = harness.coordinateRemotes(snap, listOf("M04", "M03", "M02"))
        assertEquals(
            harness.admissionSignature(orderAbc),
            harness.admissionSignature(orderCba)
        )
    }

    @Test
    fun m4d_factIsolation_doesNotCrossEdges() {
        val harness = MultiEdgeRecoveryHarness(maxConcurrentSlots = 3)
        val snap = harness.anchorSnapshot()
        val result = harness.coordinateRemotes(snap, listOf("M03", "M05"))
        val m03 = result.outcomes.filterIsInstance<MultiEdgeRecoveryEdgeOutcome.Admitted>()
            .single { it.mediaEdgeId.contains("M03") }
        val m05Before = result.state.inFlightByMediaEdgeId.keys.single { it.contains("M05") }
        val fact = harness.failFactForEdge(m03.request, reason = "M03 ACTION_FAILED")
        harness.coordinator.recordFact(fact)
        val state = harness.coordinator.currentState()
        assertTrue(state.factsByMediaEdgeId.containsKey(m03.mediaEdgeId))
        assertFalse(state.factsByMediaEdgeId.containsKey(m05Before))
        assertTrue(state.inFlightByMediaEdgeId.containsKey(m05Before))
        assertFalse(state.inFlightByMediaEdgeId.containsKey(m03.mediaEdgeId))
    }
}

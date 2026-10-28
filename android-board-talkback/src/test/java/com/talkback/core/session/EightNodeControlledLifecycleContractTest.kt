package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 4-2 — 8-node controlled lifecycle (L8-A–L8-F). */
class EightNodeControlledLifecycleContractTest {

    @Test
    fun l8a_cleanEightNodeJoin_uniqueEdgesNoStale() {
        val harness = EightNodeControlledLifecycleHarness()
        val state = harness.establish()
        assertEquals(8, state.snapshot.members.size)
        assertEquals(
            EightNodeControlledLifecycleContract.anchorStarEdgeCount(8),
            state.snapshot.actualMediaEdges.size
        )
        assertTrue(EightNodeControlledLifecycleContract.validateUniqueMediaEdgeIds(state.snapshot))
        assertTrue(
            EightNodeControlledLifecycleContract.validateSingleTopologyTruth(
                state.snapshot,
                EightNodeControlledLifecycleContract.EIGHT_NODE_ROSTER.toSet()
            )
        )
        assertTrue(state.mediaStates.values.all { it == MediaExecutionLifecycleState.ACTIVE })
    }

    @Test
    fun l8b_singleParticipantLeave_drainsOnlyLeavingEdges() {
        val harness = EightNodeControlledLifecycleHarness()
        harness.establish()
        val leavingRef = harness.simulator.edgeRefForRemote("M05")!!
        val stableRef = harness.simulator.edgeRefForRemote("M02")!!
        harness.leave("M05")
        val state = harness.simulator.snapshot()
        assertEquals(MediaExecutionLifecycleState.REMOVED, state.mediaStates[leavingRef.mediaEdgeId])
        assertEquals(MediaExecutionLifecycleState.ACTIVE, state.mediaStates[stableRef.mediaEdgeId])
        assertFalse(harness.simulator.currentMembers().contains("M05"))
    }

    @Test
    fun l8c_rejoin_regeneratesMediaEdgeId() {
        val harness = EightNodeControlledLifecycleHarness()
        harness.establish()
        val oldRef = harness.simulator.edgeRefForRemote("M05")!!
        harness.leave("M05")
        harness.rejoin("M05")
        val newRef = harness.simulator.edgeRefForRemote("M05")!!
        assertNotEquals(oldRef.mediaEdgeId, newRef.mediaEdgeId)
        val staleFact = RecoveryExecutionFact.Outcome(
            mediaEdgeId = oldRef.mediaEdgeId,
            action = RecoveryEdgeAction.ICE_RESTART,
            kind = RecoveryExecutionOutcomeKind.ACTION_COMPLETED,
            meshGeneration = oldRef.meshGeneration,
            anchorEpoch = oldRef.anchorEpoch
        )
        assertTrue(EightNodeControlledLifecycleContract.staleFactCannotResurrectEdge(staleFact, harness.simulator.currentSnapshot()!!))
    }

    @Test
    fun l8d_multiEdgeRecovery_isolatedWithSlotLimit() {
        val harness = EightNodeControlledLifecycleHarness(maxRecoverySlots = 2)
        harness.establish()
        val result = harness.recover("M05", "M07", "M08")
        assertEquals(2, result.outcomes.filterIsInstance<MultiEdgeRecoveryEdgeOutcome.Admitted>().size)
        assertEquals(1, result.outcomes.filterIsInstance<MultiEdgeRecoveryEdgeOutcome.Deferred>().size)
    }

    @Test
    fun l8e_anchorFailover_regeneratesTopologyAndIsolatesFacts() {
        val harness = EightNodeControlledLifecycleHarness()
        harness.establish()
        val before = harness.simulator.snapshot()
        val oldRef = harness.simulator.edgeRefForRemote("M03")!!
        val outcome = harness.failover("M02")
        assertTrue(outcome.nextSnapshot!!.anchorEpoch > before.snapshot.anchorEpoch)
        assertTrue(outcome.invalidatedEdgeIds.contains(oldRef.mediaEdgeId))
        assertTrue(outcome.newEdgeIds.isNotEmpty())
        val oldFact = RecoveryExecutionFact.Outcome(
            mediaEdgeId = oldRef.mediaEdgeId,
            action = RecoveryEdgeAction.ICE_RESTART,
            kind = RecoveryExecutionOutcomeKind.ACTION_FAILED,
            meshGeneration = oldRef.meshGeneration,
            anchorEpoch = oldRef.anchorEpoch
        )
        assertFalse(
            AnchorTopologySimulationContract.recoveryFactAppliesToEdge(
                oldFact,
                harness.simulator.edgeRefForRemote("M03")!!
            )
        )
    }

    @Test
    fun l8f_adversarialOrdering_convergesToSingleTopologyTruth() {
        val base = EightNodeControlledLifecycleHarness().adversarialBaseScript()
        val permutations = listOf(
            base,
            base.reversed(),
            listOf(
                base[3],
                base[1],
                base[4],
                base[0],
                base[2]
            )
        )
        permutations.forEach { permutation ->
            val harness = EightNodeControlledLifecycleHarness()
            harness.establish()
            val final = harness.applyScript(permutation)
            assertTrue(
                EightNodeControlledLifecycleContract.validateSingleTopologyTruth(
                    final.snapshot,
                    final.snapshot.members.toSet()
                )
            )
            assertTrue(EightNodeControlledLifecycleContract.validateUniqueMediaEdgeIds(final.snapshot))
            final.recordedFacts.values.forEach { fact ->
                assertTrue(
                    EightNodeControlledLifecycleContract.staleFactCannotResurrectEdge(
                        fact,
                        final.snapshot
                    )
                )
            }
        }
        val orderedHarness = EightNodeControlledLifecycleHarness()
        orderedHarness.establish()
        val orderedFinal = orderedHarness.applyScript(
            EightNodeControlledLifecycleContract.deterministicScriptOrder(base)
        )
        assertTrue(orderedFinal.snapshot.members.contains("M05"))
    }
}

package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 3-2 — Recovery trigger contract (T3A–T3E). */
class RecoveryTriggerContractTest {

    private val harness = RecoveryTriggerHarness()

    @Test
    fun t3a_triggerProducesDecisionInput() {
        val snap = harness.anchorSnapshot()
        val trigger = harness.triggerForRemote(snap, RecoveryTriggerKind.ICE_DISCONNECTED)
        harness.provider.ingest(trigger, snap)
        val normalized = harness.provider.normalize(trigger.edgeKey, snap)
        assertTrue(normalized is RecoveryTriggerNormalizationResult.DecisionInputReady)
        val ready = normalized as RecoveryTriggerNormalizationResult.DecisionInputReady
        assertEquals(RecoveryEdgeAction.ICE_RESTART, ready.input.requestedAction)
        assertTrue(harness.decideFromNormalization(normalized) is RecoveryEdgeDecision.Allow)
    }

    @Test
    fun t3b_triggerHasNoExecutorPermission() {
        val rejected = RecoveryTriggerContract.validateForbiddenTriggerAction(
            RecoveryForbiddenTriggerAction.CREATE_OFFER
        )
        assertTrue(rejected.reason.contains("must not execute"))
    }

    @Test
    fun t3c_sameEdgeTriggers_mergeToOneDecisionInput() {
        val snap = harness.anchorSnapshot()
        val triggers = listOf(
            harness.triggerForRemote(snap, RecoveryTriggerKind.ICE_DISCONNECTED),
            harness.triggerForRemote(snap, RecoveryTriggerKind.NETWORK_LOST),
            harness.triggerForRemote(snap, RecoveryTriggerKind.REMOTE_EDGE_TIMEOUT)
        )
        val normalized = harness.ingestAndNormalize(triggers, snap)
        assertTrue(normalized is RecoveryTriggerNormalizationResult.DecisionInputReady)
        val ready = normalized as RecoveryTriggerNormalizationResult.DecisionInputReady
        assertEquals(3, ready.mergedKinds.size)
        assertEquals(RecoveryEdgeAction.ICE_RESTART, ready.input.requestedAction)
        assertTrue(harness.provider.pendingEdgeKeys().isEmpty())
    }

    @Test
    fun t3d_triggerDoesNotMutateTopologySnapshot() {
        val snap = harness.anchorSnapshot()
        val before = snap.copy(
            members = snap.members.toList(),
            actualMediaEdges = snap.actualMediaEdges.toSet()
        )
        val normalized = harness.ingestAndNormalize(
            listOf(harness.triggerForRemote(snap, RecoveryTriggerKind.NETWORK_LOST)),
            snap
        )
        assertTrue(normalized is RecoveryTriggerNormalizationResult.DecisionInputReady)
        assertEquals(before, snap)
        assertEquals(before.actualMediaEdges, snap.actualMediaEdges)
    }

    @Test
    fun t3e_staleTrigger_discardedNotFailedRecovery() {
        val snap = harness.anchorSnapshot(meshGeneration = 12L)
        val staleTrigger = harness.triggerForRemote(snap, RecoveryTriggerKind.ICE_DISCONNECTED)
            .copy(
                mediaEdgeRef = TopologyMediaEdgeRef.fromSnapshot(
                    snap,
                    MediaEdge("M01", "M02")
                ).copy(meshGeneration = 10L)
            )
        val result = harness.provider.ingest(staleTrigger, snap)
        assertEquals(RecoveryTriggerNormalizationResult.DropStaleTrigger, result)
        assertTrue(harness.provider.pendingEdgeKeys().isEmpty())
    }

    @Test
    fun userReattach_selectsRetryNegotiation() {
        val snap = harness.anchorSnapshot()
        val normalized = harness.ingestAndNormalize(
            listOf(harness.triggerForRemote(snap, RecoveryTriggerKind.USER_REATTACH)),
            snap
        ) as RecoveryTriggerNormalizationResult.DecisionInputReady
        assertEquals(RecoveryEdgeAction.RETRY_NEGOTIATION, normalized.input.requestedAction)
    }
}

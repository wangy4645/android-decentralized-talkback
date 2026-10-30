package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 3-3 — Recovery signal adapter contract (S3A–S3E). */
class RecoverySignalAdapterContractTest {

    private val harness = RecoverySignalAdapterHarness()

    @Test
    fun s3a_fakeIceSignal_producesTrigger() {
        val snap = harness.anchorSnapshot()
        val signal = harness.iceSignalForRemote(snap)
        harness.adapter.ingest(signal, snap)
        val adapted = harness.adapter.adapt(signal.edgeKey, snap)
        assertTrue(adapted is RecoverySignalAdaptationResult.TriggerBatchReady)
        val batch = adapted as RecoverySignalAdaptationResult.TriggerBatchReady
        assertEquals(1, batch.triggers.size)
        assertEquals(RecoveryTriggerKind.ICE_DISCONNECTED, batch.triggers.first().kind)
        assertEquals(RecoveryTriggerSource.ICE_MONITOR, batch.triggers.first().source)
    }

    @Test
    fun s3b_fakeNetworkSignal_producesTrigger() {
        val snap = harness.anchorSnapshot()
        val signal = harness.networkSignalForRemote(snap)
        val adapted = harness.ingestAndAdapt(listOf(signal), snap)
        assertTrue(adapted is RecoverySignalAdaptationResult.TriggerBatchReady)
        val batch = adapted as RecoverySignalAdaptationResult.TriggerBatchReady
        assertEquals(RecoveryTriggerKind.NETWORK_LOST, batch.triggers.first().kind)
        assertEquals(RecoveryTriggerSource.NETWORK_MONITOR, batch.triggers.first().source)
    }

    @Test
    fun s3c_duplicateSignals_mergeToOneTriggerBatch() {
        val snap = harness.anchorSnapshot()
        val ice = harness.iceSignalForRemote(snap)
        val network = harness.networkSignalForRemote(snap)
        val adapted = harness.ingestAndAdapt(listOf(ice, network), snap)
        assertTrue(adapted is RecoverySignalAdaptationResult.TriggerBatchReady)
        val batch = adapted as RecoverySignalAdaptationResult.TriggerBatchReady
        assertEquals(2, batch.mergedSignalKinds.size)
        assertEquals(2, batch.triggers.size)
        val normalized = harness.adaptThroughDecision(adapted, snap)
        assertTrue(normalized is RecoveryTriggerNormalizationResult.DecisionInputReady)
        val ready = normalized as RecoveryTriggerNormalizationResult.DecisionInputReady
        assertEquals(RecoveryEdgeAction.ICE_RESTART, ready.input.requestedAction)
        assertEquals(2, ready.mergedKinds.size)
    }

    @Test
    fun s3d_staleSignal_droppedNotFailedRecovery() {
        val snap = harness.anchorSnapshot(meshGeneration = 12L)
        val staleSignal = harness.iceSignalForRemote(snap).copy(
            mediaEdgeRef = TopologyMediaEdgeRef.fromSnapshot(
                snap,
                MediaEdge("M01", "M02")
            ).copy(meshGeneration = 10L)
        )
        val result = harness.adapter.ingest(staleSignal, snap)
        assertEquals(RecoverySignalAdaptationResult.DropStaleSignal, result)
        assertTrue(harness.adapter.pendingEdgeKeys().isEmpty())
    }

    @Test
    fun s3e_adapterCannotExecute() {
        val rejected = RecoverySignalAdapterContract.validateForbiddenSignalAction(
            RecoveryForbiddenSignalAction.CREATE_OFFER
        )
        assertTrue(rejected.reason.contains("must not execute"))
    }

    @Test
    fun signalSource_preservedThroughFullChain() {
        val snap = harness.anchorSnapshot()
        val adapted = harness.ingestAndAdapt(
            listOf(harness.iceSignalForRemote(snap)),
            snap
        ) as RecoverySignalAdaptationResult.TriggerBatchReady
        assertEquals(RecoveryTriggerSource.ICE_MONITOR, adapted.triggers.first().source)
    }

    @Test
    fun duplicateSameSignal_deduped() {
        val snap = harness.anchorSnapshot()
        val signal = harness.iceSignalForRemote(snap)
        harness.adapter.ingest(signal, snap)
        harness.adapter.ingest(signal, snap)
        val adapted = harness.adapter.adapt(signal.edgeKey, snap)
        assertTrue(adapted is RecoverySignalAdaptationResult.TriggerBatchReady)
        assertEquals(1, (adapted as RecoverySignalAdaptationResult.TriggerBatchReady).triggers.size)
    }
}

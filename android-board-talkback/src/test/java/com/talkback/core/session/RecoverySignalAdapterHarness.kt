package com.talkback.core.session

/**
 * Phase 3-3 harness — signal → trigger → decision input (no executor).
 */
class RecoverySignalAdapterHarness {
    val adapter = RecoverySignalAdapter()
    val triggerProvider = RecoveryTriggerDecisionProvider()
    private val triggerHarness = RecoveryTriggerHarness()

    fun anchorSnapshot(
        conferenceId: String = "c-signal",
        meshGeneration: Long = 10L,
        anchorEpoch: Long = 100L
    ) = triggerHarness.anchorSnapshot(conferenceId, meshGeneration, anchorEpoch)

    fun iceSignalForRemote(
        snapshot: ConferenceTopologySnapshot,
        remote: String = "M02"
    ): RecoverySignal {
        val edge = MediaEdge(snapshot.anchorId!!, remote)
        return RecoverySignal(
            kind = RecoverySignalKind.ICE_STATE_DISCONNECTED,
            source = RecoveryTriggerSource.ICE_MONITOR,
            mediaEdgeRef = TopologyMediaEdgeRef.fromSnapshot(snapshot, edge),
            negotiation = NegotiationEdgeRef(
                sessionId = snapshot.conferenceId,
                localModuleId = snapshot.anchorId!!,
                remoteModuleId = remote
            )
        )
    }

    fun networkSignalForRemote(
        snapshot: ConferenceTopologySnapshot,
        remote: String = "M02"
    ): RecoverySignal {
        val edge = MediaEdge(snapshot.anchorId!!, remote)
        return RecoverySignal(
            kind = RecoverySignalKind.NETWORK_LOST,
            source = RecoveryTriggerSource.NETWORK_MONITOR,
            mediaEdgeRef = TopologyMediaEdgeRef.fromSnapshot(snapshot, edge),
            negotiation = NegotiationEdgeRef(
                sessionId = snapshot.conferenceId,
                localModuleId = snapshot.anchorId!!,
                remoteModuleId = remote
            )
        )
    }

    fun ingestAndAdapt(
        signals: List<RecoverySignal>,
        snapshot: ConferenceTopologySnapshot
    ): RecoverySignalAdaptationResult {
        signals.forEach { adapter.ingest(it, snapshot) }
        val key = signals.firstOrNull()?.edgeKey
            ?: return RecoverySignalAdaptationResult.NoPendingSignals
        return adapter.adapt(key, snapshot)
    }

    fun adaptThroughDecision(
        adaptation: RecoverySignalAdaptationResult,
        snapshot: ConferenceTopologySnapshot
    ): RecoveryTriggerNormalizationResult? {
        if (adaptation !is RecoverySignalAdaptationResult.TriggerBatchReady) return null
        adaptation.triggers.forEach { triggerProvider.ingest(it, snapshot) }
        val key = adaptation.triggers.firstOrNull()?.edgeKey ?: return null
        return triggerProvider.normalize(key, snapshot)
    }
}

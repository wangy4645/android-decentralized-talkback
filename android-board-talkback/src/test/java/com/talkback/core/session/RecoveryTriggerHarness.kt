package com.talkback.core.session

/**
 * Phase 3-2 harness — trigger → decision input → provider.decide (no executor).
 */
class RecoveryTriggerHarness {
    val provider = RecoveryTriggerDecisionProvider()
    private val actionHarness = RecoveryEdgeActionHarness()

    fun anchorSnapshot(
        conferenceId: String = "c-trigger",
        meshGeneration: Long = 10L,
        anchorEpoch: Long = 100L
    ) = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = conferenceId,
            hostModuleId = "M01",
            anchorId = "M01",
            members = listOf("M01", "M02", "M03"),
            meshGeneration = meshGeneration,
            anchorEpoch = anchorEpoch
        )
    )

    fun triggerForRemote(
        snapshot: ConferenceTopologySnapshot,
        kind: RecoveryTriggerKind,
        remote: String = "M02"
    ): RecoveryTrigger {
        val edge = MediaEdge(snapshot.anchorId!!, remote)
        return RecoveryTrigger(
            kind = kind,
            source = RecoveryTriggerSource.CONTROLLED_HARNESS,
            mediaEdgeRef = TopologyMediaEdgeRef.fromSnapshot(snapshot, edge),
            negotiation = NegotiationEdgeRef(
                sessionId = snapshot.conferenceId,
                localModuleId = snapshot.anchorId!!,
                remoteModuleId = remote
            )
        )
    }

    fun ingestAndNormalize(
        triggers: List<RecoveryTrigger>,
        snapshot: ConferenceTopologySnapshot
    ): RecoveryTriggerNormalizationResult {
        triggers.forEach { provider.ingest(it, snapshot) }
        val key = triggers.firstOrNull()?.edgeKey
            ?: return RecoveryTriggerNormalizationResult.NoPendingTriggers
        return provider.normalize(key, snapshot)
    }

    fun decideFromNormalization(
        result: RecoveryTriggerNormalizationResult
    ): RecoveryEdgeDecision? =
        when (result) {
            is RecoveryTriggerNormalizationResult.DecisionInputReady ->
                RecoveryEdgeProvider.decide(result.input)
            else -> null
        }
}

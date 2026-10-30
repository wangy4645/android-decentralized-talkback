package com.talkback.core.session

/**
 * Independent CPP track: admitted edge ≠ usable edge.
 * Coordinator / FactsAdapter wiring: presence consume + HELLO digest.
 * Topology / RecoveryTarget / Health rules unchanged.
 * See docs/analysis/cpp-per-edge-media-fact-contract.md
 */
data class PerEdgeMediaUsabilityFact(
    val conferenceId: String,
    val anchorEpoch: Long,
    val meshGeneration: Long,
    val producedAtMs: Long,
    val producerModuleId: String,
    val edge: MediaEdge,
    val usable: Boolean
)

data class ConferencePerEdgeMediaFactInput(
    val snapshot: ConferenceTopologySnapshot,
    val localModuleId: String,
    val facts: List<PerEdgeMediaUsabilityFact> = emptyList(),
    val nowMs: Long,
    val staleAfterMs: Long = ConferencePresenceProjector.DEFAULT_STALE_AFTER_MS,
    /** MUST NOT upgrade a non-incident star edge to VIA_ANCHOR. */
    val localIceToAnchor: Boolean = false
)

data class CppStarEdgeView(
    val mediaRelation: CppMediaRelation,
    val evidence: CppEvidence,
    val reconnecting: Boolean
) {
    val confirmedUsable: Boolean
        get() = evidence == CppEvidence.FRESH &&
            (mediaRelation == CppMediaRelation.DIRECT ||
                mediaRelation == CppMediaRelation.VIA_ANCHOR) &&
            !reconnecting
}

object ConferencePerEdgeMediaFactContract {

    fun starEdge(anchorId: String, spoke: String): MediaEdge =
        if (spoke == anchorId) {
            error("spoke must not equal anchor")
        } else {
            MediaEdge(anchorId, spoke)
        }

    fun isEndpointProducer(edge: MediaEdge, producerModuleId: String): Boolean =
        producerModuleId == edge.anchorModuleId || producerModuleId == edge.remoteModuleId

    fun isValidFact(
        snapshot: ConferenceTopologySnapshot,
        fact: PerEdgeMediaUsabilityFact,
        nowMs: Long,
        staleAfterMs: Long
    ): Boolean {
        if (snapshot.topologyMode != ConferenceTopologyMode.ANCHOR) return false
        if (fact.conferenceId != snapshot.conferenceId) return false
        if (fact.anchorEpoch != snapshot.anchorEpoch) return false
        if (fact.meshGeneration < snapshot.meshGeneration) return false
        if (fact.edge !in snapshot.actualMediaEdges) return false
        if (!isEndpointProducer(fact.edge, fact.producerModuleId)) return false
        if (nowMs - fact.producedAtMs > staleAfterMs) return false
        return true
    }

    fun selectFact(
        snapshot: ConferenceTopologySnapshot,
        edge: MediaEdge,
        facts: List<PerEdgeMediaUsabilityFact>,
        nowMs: Long,
        staleAfterMs: Long
    ): PerEdgeMediaUsabilityFact? =
        facts
            .filter { it.edge == edge && isValidFact(snapshot, it, nowMs, staleAfterMs) }
            .maxByOrNull { it.producedAtMs }

    /**
     * View of [remoteModuleId] for [input.localModuleId].
     * Local ICE to anchor is ignored unless [remoteModuleId] is the incident peer.
     */
    fun viewOf(input: ConferencePerEdgeMediaFactInput, remoteModuleId: String): CppStarEdgeView? {
        val snapshot = input.snapshot
        if (remoteModuleId == input.localModuleId) return null
        if (snapshot.topologyMode != ConferenceTopologyMode.ANCHOR) return null
        val anchor = snapshot.anchorId ?: return null
        val edge = when {
            input.localModuleId == anchor && remoteModuleId != anchor ->
                starEdge(anchor, remoteModuleId)
            remoteModuleId == anchor && input.localModuleId != anchor ->
                starEdge(anchor, input.localModuleId)
            input.localModuleId != anchor && remoteModuleId != anchor ->
                starEdge(anchor, remoteModuleId)
            else -> return null
        }
        if (edge !in snapshot.actualMediaEdges) {
            return CppStarEdgeView(
                mediaRelation = CppMediaRelation.NONE,
                evidence = CppEvidence.UNKNOWN,
                reconnecting = false
            )
        }
        val incident = input.localModuleId == edge.anchorModuleId ||
            input.localModuleId == edge.remoteModuleId
        val fact = selectFact(snapshot, edge, input.facts, input.nowMs, input.staleAfterMs)
        val usable = fact?.usable == true
        // localIceToAnchor must not be consulted: missing/unusable fact is fail-closed.
        if (!usable) {
            return CppStarEdgeView(
                mediaRelation = CppMediaRelation.NONE,
                evidence = CppEvidence.UNKNOWN,
                reconnecting = false
            )
        }
        return if (incident) {
            CppStarEdgeView(
                mediaRelation = CppMediaRelation.DIRECT,
                evidence = CppEvidence.FRESH,
                reconnecting = false
            )
        } else {
            CppStarEdgeView(
                mediaRelation = CppMediaRelation.VIA_ANCHOR,
                evidence = CppEvidence.FRESH,
                reconnecting = false
            )
        }
    }
}

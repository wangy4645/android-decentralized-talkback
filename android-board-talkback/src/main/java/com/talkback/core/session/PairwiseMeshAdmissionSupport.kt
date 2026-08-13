package com.talkback.core.session

import com.talkback.core.model.ModuleId

/**
 * #180 Phase 1-A — required pairwise edges and session-scoped admission obligations.
 * Pure helpers; coordinator wires lifecycle hooks in later phases.
 */
object PairwiseMeshAdmissionSupport {

    data class ObligationCounts(
        val requiredEdges: Int,
        val signalingSatisfiedEdges: Int
    )

    /**
     * Global required edges for [canonicalMembers] under [topology].
     * Distinct from per-local [GroupMeshPlanner] invite/join targets.
     */
    fun requiredPairwiseEdges(
        topology: GroupMediaTopology,
        anchorModuleId: ModuleId?,
        canonicalMembers: Set<ModuleId>
    ): Set<PairwiseMeshEdgeKey> {
        if (canonicalMembers.size < 2) return emptySet()
        return when (topology) {
            GroupMediaTopology.MESH -> allUnorderedPairs(canonicalMembers)
            GroupMediaTopology.ANCHOR -> {
                val anchor = anchorModuleId ?: return emptySet()
                if (anchor !in canonicalMembers) return emptySet()
                canonicalMembers
                    .filter { it != anchor }
                    .map { PairwiseMeshEdgeKey.of(anchor, it) }
                    .toSet()
            }
        }
    }

    /** Perfect Negotiation: lexicographically smaller moduleId is offerer. */
    fun perfectNegotiationRoles(edge: PairwiseMeshEdgeKey): Pair<ModuleId, ModuleId> =
        ModuleId(edge.first) to ModuleId(edge.second)

    /**
     * Edges this session node must track (pairs involving [localModuleId]).
     */
    fun localRequiredEdges(
        topology: GroupMediaTopology,
        anchorModuleId: ModuleId?,
        canonicalMembers: Set<ModuleId>,
        localModuleId: ModuleId
    ): Set<PairwiseMeshEdgeKey> =
        requiredPairwiseEdges(topology, anchorModuleId, canonicalMembers)
            .filter { it.involves(localModuleId) }
            .toSet()

    fun isSignalingSatisfied(session: TalkbackSession, edge: PairwiseMeshEdgeKey): Boolean {
        val peer = edge.peerOf(session.local.moduleId) ?: return false
        return peer.value in session.meshCompletedModules
    }

    /**
     * Reconcile [TalkbackSession.pairwiseMeshAdmissionObligations] from canonical roster.
     * Does not create obligations from ICE state — only refreshes signaling evidence from
     * [TalkbackSession.meshCompletedModules].
     */
    fun reconcile(session: TalkbackSession): ObligationCounts {
        if (session.type != SessionType.GROUP || !session.accepted) {
            session.pairwiseMeshAdmissionObligations.clear()
            return ObligationCounts(requiredEdges = 0, signalingSatisfiedEdges = 0)
        }
        val canonical = GroupMembershipSupport.canonicalMemberModuleIds(session)
        val local = session.local.moduleId
        val required = localRequiredEdges(
            topology = session.mediaTopology,
            anchorModuleId = session.anchorModuleId,
            canonicalMembers = canonical,
            localModuleId = local
        )
        val next = linkedMapOf<String, PairwiseMeshAdmissionObligation>()
        required.forEach { edge ->
            val (offerer, answerer) = perfectNegotiationRoles(edge)
            next[edge.storageKey()] = PairwiseMeshAdmissionObligation(
                edge = edge,
                offerer = offerer,
                answerer = answerer,
                signalingSatisfied = isSignalingSatisfied(session, edge)
            )
        }
        session.pairwiseMeshAdmissionObligations.clear()
        session.pairwiseMeshAdmissionObligations.putAll(next)
        return obligationCounts(session)
    }

    fun obligation(session: TalkbackSession, edge: PairwiseMeshEdgeKey): PairwiseMeshAdmissionObligation? =
        session.pairwiseMeshAdmissionObligations[edge.storageKey()]

    fun obligationForRemotePeer(
        session: TalkbackSession,
        remoteModuleId: ModuleId
    ): PairwiseMeshAdmissionObligation? {
        if (remoteModuleId == session.local.moduleId) return null
        val edge = PairwiseMeshEdgeKey.of(session.local.moduleId, remoteModuleId)
        return obligation(session, edge)
    }

    /**
     * #180-B — snapshot apply handled membership only; defer mesh completion when pairwise
     * signaling for [remoteModuleId] is still outstanding.
     */
    fun shouldDeferMeshCompletionAfterSnapshot(
        session: TalkbackSession,
        remoteModuleId: ModuleId
    ): Boolean {
        val obligation = obligationForRemotePeer(session, remoteModuleId) ?: return false
        return !obligation.signalingSatisfied
    }

    fun hasUnsatisfiedObligation(session: TalkbackSession): Boolean =
        session.pairwiseMeshAdmissionObligations.values.any { !it.signalingSatisfied }

    fun obligationCounts(session: TalkbackSession): ObligationCounts {
        val values = session.pairwiseMeshAdmissionObligations.values
        return ObligationCounts(
            requiredEdges = values.size,
            signalingSatisfiedEdges = values.count { it.signalingSatisfied }
        )
    }

    private fun allUnorderedPairs(members: Set<ModuleId>): Set<PairwiseMeshEdgeKey> {
        val sorted = members.map { it.value }.sorted()
        val edges = linkedSetOf<PairwiseMeshEdgeKey>()
        for (i in sorted.indices) {
            for (j in i + 1 until sorted.size) {
                edges.add(PairwiseMeshEdgeKey(sorted[i], sorted[j]))
            }
        }
        return edges
    }
}

package com.talkback.core.session

/**
 * Phase 1b-4: [ConferenceTopologySnapshot.actualMediaEdges] admission contract.
 *
 * Chain:
 * ```text
 * Anchor admission decision (1b-3)
 *         ↓
 * Topology snapshot
 *         ↓
 * ActualMediaEdgeSet admission (this contract)
 * ```
 *
 * **ICE is evaluation-only.** [MediaEdgeAdmissionCause] must never include ICE states.
 * See docs/analysis/0056-phase-1b-contract-fixtures.md F3/F6; 1b-4 fixtures M1–M8.
 */
enum class MediaEdgeAdmissionCause {
    /** Anchor-star projection from admitted topology (roster + anchor). */
    TOPOLOGY_PROJECTION,
    /** Member admit/evict under same anchor authority. */
    ROSTER_EDGE_CHANGE,
    /** Anchor failover with full edge-set replacement. */
    ANCHOR_FAILOVER
}

/** Runtime ICE evaluation — never mutates snapshot by itself. */
sealed class IceObservationEvaluation {
    /** Peer-peer or non-star observation (F3 forward). */
    data object NotTopologyEdge : IceObservationEvaluation()

    /** Star edge present in snapshot; transient ICE must not evict (F3 reverse / F6). */
    data class StillAdmitted(val edge: MediaEdge) : IceObservationEvaluation()

    /** ICE may be up, but edge not in ActualMediaEdgeSet until authorized transaction. */
    data class ConnectedButNotAdmitted(val edge: MediaEdge) : IceObservationEvaluation()
}

sealed class MediaEdgeAdmissionResult {
    data class Admitted(val snapshot: ConferenceTopologySnapshot) : MediaEdgeAdmissionResult()
    data class Rejected(val reason: String) : MediaEdgeAdmissionResult()
    data object NoOp : MediaEdgeAdmissionResult()
}

object ConferenceMediaEdgeAdmissionContract {

    fun evaluateIceObservation(
        snapshot: ConferenceTopologySnapshot,
        observation: IceEdgeObservation
    ): IceObservationEvaluation {
        val edge = ConferenceTopologyContract.mediaEdgeForObservation(observation, snapshot)
            ?: return IceObservationEvaluation.NotTopologyEdge
        return if (ConferenceTopologyContract.edgeStillAdmitted(edge, snapshot)) {
            IceObservationEvaluation.StillAdmitted(edge)
        } else {
            IceObservationEvaluation.ConnectedButNotAdmitted(edge)
        }
    }

    /** F6: ICE restart / reconnecting must not rewrite topology or generation. */
    fun topologyUnchangedByIceTransient(
        snapshot: ConferenceTopologySnapshot,
        observation: IceEdgeObservation
    ): Boolean {
        val nextGeneration = ConferenceTopologyContract.requiredMeshGenerationAfterEdgeChange(
            previousEdges = snapshot.actualMediaEdges,
            nextEdges = snapshot.actualMediaEdges,
            previousGeneration = snapshot.meshGeneration
        )
        if (nextGeneration != snapshot.meshGeneration) return false
        return when (evaluateIceObservation(snapshot, observation)) {
            is IceObservationEvaluation.StillAdmitted -> true
            is IceObservationEvaluation.ConnectedButNotAdmitted -> true
            IceObservationEvaluation.NotTopologyEdge -> true
        }
    }

    /** Authorized: topology projection after anchor admission (1b-3 → snapshot edges). */
    fun admitFromTopologyProjection(anchorInput: AnchorAdmissionInput): MediaEdgeAdmissionResult {
        val snapshot = ConferenceTopologyContract.composeAnchorAdmission(anchorInput)
        return validateAndAdmit(snapshot)
    }

    /** Authorized: roster-driven edge set under fixed anchor. */
    fun admitFromRosterChange(
        current: ConferenceTopologySnapshot,
        newMembers: List<String>
    ): MediaEdgeAdmissionResult {
        if (current.topologyMode != ConferenceTopologyMode.ANCHOR) {
            return MediaEdgeAdmissionResult.Rejected("roster edge admission requires ANCHOR mode")
        }
        val anchor = current.anchorId
            ?: return MediaEdgeAdmissionResult.Rejected("anchor required")
        val distinct = newMembers.distinct()
        if (anchor !in distinct) {
            return MediaEdgeAdmissionResult.Rejected("anchor not in members")
        }
        val nextEdges = ConferenceTopologyContract.anchorStarEdges(anchor, distinct)
        if (nextEdges == current.actualMediaEdges && distinct == current.members) {
            return MediaEdgeAdmissionResult.NoOp
        }
        val nextGeneration = ConferenceTopologyContract.requiredMeshGenerationAfterEdgeChange(
            previousEdges = current.actualMediaEdges,
            nextEdges = nextEdges,
            previousGeneration = current.meshGeneration
        )
        val candidate = current.copy(
            members = distinct,
            actualMediaEdges = nextEdges,
            meshGeneration = nextGeneration
        )
        return validateAndAdmit(candidate)
    }

    /** Authorized: anchor failover replaces star (F5). */
    fun admitFromAnchorFailover(
        current: ConferenceTopologySnapshot,
        newAnchorId: String
    ): MediaEdgeAdmissionResult {
        val candidate = ConferenceTopologyContract.failoverSnapshot(current, newAnchorId)
        return validateAndAdmit(candidate)
    }

    /**
     * Forbidden path (1b-4 boundary): ICE observations must not drive edge-set mutation.
     * Production must call this instead of inventing a second ICE→admit API.
     */
    fun rejectIceDrivenAdmission(
        snapshot: ConferenceTopologySnapshot,
        observation: IceEdgeObservation
    ): MediaEdgeAdmissionResult.Rejected = MediaEdgeAdmissionResult.Rejected(
        "ICE observation cannot mutate ActualMediaEdgeSet conferenceId=${snapshot.conferenceId} " +
            "(local=${observation.localModuleId} remote=${observation.remoteModuleId} " +
            "connected=${observation.iceConnected})"
    )

    private fun validateAndAdmit(candidate: ConferenceTopologySnapshot): MediaEdgeAdmissionResult =
        when (val validity = ConferenceTopologyContract.validateSnapshot(candidate)) {
            is SnapshotValidity.Invalid -> MediaEdgeAdmissionResult.Rejected(validity.reason)
            SnapshotValidity.Valid -> MediaEdgeAdmissionResult.Admitted(candidate)
        }
}

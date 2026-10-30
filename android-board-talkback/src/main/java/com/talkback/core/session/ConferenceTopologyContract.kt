package com.talkback.core.session

/**
 * Phase 1b contract types only. Not Coordinator wiring.
 * See docs/analysis/0056-phase-1b-contract-fixtures.md (F1–F8).
 */
enum class ConferenceTopologyMode {
    MESH,
    ANCHOR
}

/** Topology-level admitted edge (not [ConferenceEdgeKey] execution key). */
data class MediaEdge(
    val anchorModuleId: String,
    val remoteModuleId: String
) {
    init {
        require(anchorModuleId.isNotBlank())
        require(remoteModuleId.isNotBlank())
        require(anchorModuleId != remoteModuleId)
    }
}

data class ConferenceTopologySnapshot(
    val conferenceId: String,
    val rosterEpoch: Long,
    val anchorEpoch: Long,
    val anchorId: String?,
    val meshGeneration: Long,
    val topologyMode: ConferenceTopologyMode,
    /** Session host — metadata only; not media authority. */
    val hostModuleId: String,
    val members: List<String>,
    val actualMediaEdges: Set<MediaEdge>
)

/** Runtime ICE observation; never equals topology admission by itself. */
data class IceEdgeObservation(
    val localModuleId: String,
    val remoteModuleId: String,
    val iceConnected: Boolean,
    val iceReconnecting: Boolean = false
)

sealed class SnapshotValidity {
    data object Valid : SnapshotValidity()
    data class Invalid(val reason: String) : SnapshotValidity()
}

data class AnchorAdmissionInput(
    val conferenceId: String,
    val hostModuleId: String,
    val anchorId: String,
    val members: List<String>,
    val rosterEpoch: Long = 1L,
    val anchorEpoch: Long = 100L,
    val meshGeneration: Long = 1L
)

/**
 * Pure Phase 1b contract functions. No Recovery / Coordinator / Bus.
 */
object ConferenceTopologyContract {

    fun anchorStarEdges(anchorId: String, members: List<String>): Set<MediaEdge> =
        members
            .filter { it != anchorId }
            .map { remote -> MediaEdge(anchorModuleId = anchorId, remoteModuleId = remote) }
            .toSet()

    fun composeAnchorAdmission(input: AnchorAdmissionInput): ConferenceTopologySnapshot {
        val distinctMembers = input.members.distinct()
        require(input.anchorId in distinctMembers) {
            "anchor must be roster member"
        }
        return ConferenceTopologySnapshot(
            conferenceId = input.conferenceId,
            rosterEpoch = input.rosterEpoch,
            anchorEpoch = input.anchorEpoch,
            anchorId = input.anchorId,
            meshGeneration = input.meshGeneration,
            topologyMode = ConferenceTopologyMode.ANCHOR,
            hostModuleId = input.hostModuleId,
            members = distinctMembers,
            actualMediaEdges = anchorStarEdges(input.anchorId, distinctMembers)
        )
    }

    fun validateSnapshot(snapshot: ConferenceTopologySnapshot): SnapshotValidity {
        if (snapshot.members.isEmpty()) {
            return SnapshotValidity.Invalid("empty members")
        }
        if (snapshot.meshGeneration < 0) {
            return SnapshotValidity.Invalid("negative meshGeneration")
        }
        if (snapshot.topologyMode == ConferenceTopologyMode.ANCHOR) {
            val anchor = snapshot.anchorId
                ?: return SnapshotValidity.Invalid("anchorId required for ANCHOR")
            if (anchor !in snapshot.members) {
                return SnapshotValidity.Invalid("anchor not in members")
            }
            for (edge in snapshot.actualMediaEdges) {
                if (edge.anchorModuleId != anchor) {
                    return SnapshotValidity.Invalid("edge anchor mismatch")
                }
                if (edge.remoteModuleId !in snapshot.members) {
                    return SnapshotValidity.Invalid("remote not member")
                }
                if (edge.remoteModuleId == anchor) {
                    return SnapshotValidity.Invalid("self edge")
                }
            }
            val expected = anchorStarEdges(anchor, snapshot.members)
            if (snapshot.actualMediaEdges != expected) {
                return SnapshotValidity.Invalid("edges must equal anchor star for ANCHOR mode")
            }
        }
        return SnapshotValidity.Valid
    }

    /**
     * Maps an ICE observation to a topology [MediaEdge] if one exists under ANCHOR rules.
     * Peer-peer observations (neither endpoint is anchor) return null.
     */
    fun mediaEdgeForObservation(
        observation: IceEdgeObservation,
        snapshot: ConferenceTopologySnapshot
    ): MediaEdge? {
        if (snapshot.topologyMode != ConferenceTopologyMode.ANCHOR) return null
        val anchor = snapshot.anchorId ?: return null
        return when (observation.localModuleId) {
            anchor -> MediaEdge(anchor, observation.remoteModuleId)
            observation.remoteModuleId -> if (observation.localModuleId == anchor) {
                MediaEdge(anchor, observation.remoteModuleId)
            } else {
                null
            }
            else -> if (observation.remoteModuleId == anchor) {
                MediaEdge(anchor, observation.localModuleId)
            } else {
                null
            }
        }
    }

    fun isAdmittedByTopology(
        observation: IceEdgeObservation,
        snapshot: ConferenceTopologySnapshot
    ): Boolean {
        val edge = mediaEdgeForObservation(observation, snapshot) ?: return false
        return edge in snapshot.actualMediaEdges
    }

    fun edgeStillAdmitted(edge: MediaEdge, snapshot: ConferenceTopologySnapshot): Boolean =
        edge in snapshot.actualMediaEdges

    fun requiredMeshGenerationAfterEdgeChange(
        previousEdges: Set<MediaEdge>,
        nextEdges: Set<MediaEdge>,
        previousGeneration: Long
    ): Long = if (previousEdges == nextEdges) previousGeneration else previousGeneration + 1L

    fun failoverSnapshot(
        previous: ConferenceTopologySnapshot,
        newAnchorId: String
    ): ConferenceTopologySnapshot {
        require(newAnchorId in previous.members) { "new anchor must be member" }
        return previous.copy(
            anchorId = newAnchorId,
            anchorEpoch = previous.anchorEpoch + 1L,
            meshGeneration = previous.meshGeneration + 1L,
            actualMediaEdges = anchorStarEdges(newAnchorId, previous.members)
        )
    }

    fun isStaleSnapshot(
        candidate: ConferenceTopologySnapshot,
        current: ConferenceTopologySnapshot
    ): Boolean = when {
        candidate.anchorEpoch < current.anchorEpoch -> true
        candidate.anchorEpoch > current.anchorEpoch -> false
        candidate.meshGeneration < current.meshGeneration -> true
        else -> false
    }
}


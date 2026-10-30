package com.talkback.core.session

/**
 * Phase 1b-5: MESH / ANCHOR mode boundary and transition semantics.
 *
 * Answers (contract only — no Coordinator / Recovery / field):
 * - MESH [ConferenceTopologySnapshot.actualMediaEdges] is **empty** (no pairwise mesh in snapshot).
 * - MESH → ANCHOR replaces empty set with anchor-star via topology projection (not merge / not ICE).
 * - ANCHOR → MESH clears admitted edges; prior anchor edges do not carry forward.
 * - Mode change always bumps [ConferenceTopologySnapshot.meshGeneration] (mapping §9).
 * - [ConferenceTopologySnapshot.anchorEpoch] is inactive (0) in MESH; active in ANCHOR.
 * - No staged / partial topology during transition (F7).
 * - CPP: MESH consumers use session observation path; ANCHOR uses authority snapshot (1b-2).
 */
enum class TopologyModeTransition {
    UNCHANGED_MESH,
    UNCHANGED_ANCHOR,
    MESH_TO_ANCHOR,
    ANCHOR_TO_MESH
}

enum class ConferencePresenceReadPath {
    /** Below threshold — CPP does not read authority anchor topology (1b-2). */
    MESH_SESSION_OBSERVATION,
    /** At/above threshold — CPP reads immutable authority snapshot. */
    ANCHOR_AUTHORITY_SNAPSHOT
}

data class ModeTransitionInput(
    val conferenceId: String,
    val hostModuleId: String,
    val members: List<String>,
    val rosterEpoch: Long,
    val meshGeneration: Long,
    val anchorEpoch: Long,
    val previousSnapshot: ConferenceTopologySnapshot?,
    val decision: AnchorAdmissionDecision
)

sealed class ModeTransitionResult {
    data class AdmittedMesh(val snapshot: ConferenceTopologySnapshot) : ModeTransitionResult()
    data class AdmittedAnchor(val snapshot: ConferenceTopologySnapshot) : ModeTransitionResult()
    data class Rejected(val reason: String) : ModeTransitionResult()
    data object NoOp : ModeTransitionResult()
}

object ConferenceTopologyModeTransitionContract {

    fun detectTransition(
        previous: ConferenceTopologySnapshot?,
        decision: AnchorAdmissionDecision
    ): TopologyModeTransition {
        val previousMode = previous?.topologyMode
        return when (decision) {
            AnchorAdmissionDecision.Mesh -> when (previousMode) {
                null, ConferenceTopologyMode.MESH -> TopologyModeTransition.UNCHANGED_MESH
                ConferenceTopologyMode.ANCHOR -> TopologyModeTransition.ANCHOR_TO_MESH
            }
            is AnchorAdmissionDecision.Anchor -> when (previousMode) {
                null, ConferenceTopologyMode.MESH -> TopologyModeTransition.MESH_TO_ANCHOR
                ConferenceTopologyMode.ANCHOR -> TopologyModeTransition.UNCHANGED_ANCHOR
            }
            is AnchorAdmissionDecision.Rejected -> TopologyModeTransition.UNCHANGED_MESH
        }
    }

    fun presenceReadPath(mode: ConferenceTopologyMode): ConferencePresenceReadPath =
        when (mode) {
            ConferenceTopologyMode.MESH -> ConferencePresenceReadPath.MESH_SESSION_OBSERVATION
            ConferenceTopologyMode.ANCHOR -> ConferencePresenceReadPath.ANCHOR_AUTHORITY_SNAPSHOT
        }

    fun meshGenerationAfterModeChange(
        previousGeneration: Long,
        transition: TopologyModeTransition
    ): Long = when (transition) {
        TopologyModeTransition.MESH_TO_ANCHOR,
        TopologyModeTransition.ANCHOR_TO_MESH -> previousGeneration + 1L
        TopologyModeTransition.UNCHANGED_MESH,
        TopologyModeTransition.UNCHANGED_ANCHOR -> previousGeneration
    }

    fun composeMeshSnapshot(
        conferenceId: String,
        hostModuleId: String,
        members: List<String>,
        rosterEpoch: Long,
        meshGeneration: Long
    ): ConferenceTopologySnapshot {
        val distinct = members.distinct()
        return ConferenceTopologySnapshot(
            conferenceId = conferenceId,
            rosterEpoch = rosterEpoch,
            anchorEpoch = 0L,
            anchorId = null,
            meshGeneration = meshGeneration,
            topologyMode = ConferenceTopologyMode.MESH,
            hostModuleId = hostModuleId,
            members = distinct,
            actualMediaEdges = emptySet()
        )
    }

    fun validateModeSnapshot(snapshot: ConferenceTopologySnapshot): SnapshotValidity =
        when (snapshot.topologyMode) {
            ConferenceTopologyMode.MESH -> validateMeshSnapshot(snapshot)
            ConferenceTopologyMode.ANCHOR -> ConferenceTopologyContract.validateSnapshot(snapshot)
        }

    fun validateMeshSnapshot(snapshot: ConferenceTopologySnapshot): SnapshotValidity {
        if (snapshot.topologyMode != ConferenceTopologyMode.MESH) {
            return SnapshotValidity.Invalid("expected MESH mode")
        }
        if (snapshot.actualMediaEdges.isNotEmpty()) {
            return SnapshotValidity.Invalid("MESH ActualMediaEdgeSet must be empty")
        }
        if (snapshot.anchorId != null) {
            return SnapshotValidity.Invalid("MESH snapshot must not carry anchorId")
        }
        if (snapshot.anchorEpoch != 0L) {
            return SnapshotValidity.Invalid("anchorEpoch inactive in MESH")
        }
        if (snapshot.members.isEmpty()) {
            return SnapshotValidity.Invalid("empty members")
        }
        if (snapshot.hostModuleId !in snapshot.members) {
            return SnapshotValidity.Invalid("host not in members")
        }
        return SnapshotValidity.Valid
    }

    /**
     * Sole contract entry for mode transition snapshot materialization.
     * Does not perform ICE evaluation or partial edge staging.
     */
    fun applyModeTransition(input: ModeTransitionInput): ModeTransitionResult {
        if (input.hostModuleId !in input.members.distinct()) {
            return ModeTransitionResult.Rejected("host not in members")
        }
        val transition = detectTransition(input.previousSnapshot, input.decision)
        return when (input.decision) {
            AnchorAdmissionDecision.Mesh -> admitMesh(input, transition)
            is AnchorAdmissionDecision.Anchor -> admitAnchor(input, transition, input.decision)
            is AnchorAdmissionDecision.Rejected -> ModeTransitionResult.Rejected(input.decision.reason)
        }
    }

    private fun admitMesh(
        input: ModeTransitionInput,
        transition: TopologyModeTransition
    ): ModeTransitionResult {
        val generation = meshGenerationAfterModeChange(input.meshGeneration, transition)
        val candidate = composeMeshSnapshot(
            conferenceId = input.conferenceId,
            hostModuleId = input.hostModuleId,
            members = input.members,
            rosterEpoch = input.rosterEpoch,
            meshGeneration = generation
        )
        if (input.previousSnapshot == candidate) {
            return ModeTransitionResult.NoOp
        }
        return when (val validity = validateMeshSnapshot(candidate)) {
            is SnapshotValidity.Invalid -> ModeTransitionResult.Rejected(validity.reason)
            SnapshotValidity.Valid -> ModeTransitionResult.AdmittedMesh(candidate)
        }
    }

    private fun admitAnchor(
        input: ModeTransitionInput,
        transition: TopologyModeTransition,
        decision: AnchorAdmissionDecision.Anchor
    ): ModeTransitionResult {
        val generation = meshGenerationAfterModeChange(input.meshGeneration, transition)
        val anchorInput = AnchorAdmissionInput(
            conferenceId = input.conferenceId,
            hostModuleId = input.hostModuleId,
            anchorId = decision.anchorId,
            members = input.members.distinct(),
            rosterEpoch = input.rosterEpoch,
            anchorEpoch = input.anchorEpoch.coerceAtLeast(AnchorRanking.INITIAL_ANCHOR_EPOCH),
            meshGeneration = generation
        )
        return when (val media = ConferenceMediaEdgeAdmissionContract.admitFromTopologyProjection(anchorInput)) {
            is MediaEdgeAdmissionResult.Admitted -> {
                if (transition == TopologyModeTransition.MESH_TO_ANCHOR &&
                    input.previousSnapshot?.actualMediaEdges?.isNotEmpty() == true
                ) {
                    return ModeTransitionResult.Rejected("MESH snapshot must not carry edges into transition")
                }
                ModeTransitionResult.AdmittedAnchor(media.snapshot)
            }
            is MediaEdgeAdmissionResult.Rejected -> ModeTransitionResult.Rejected(media.reason)
            MediaEdgeAdmissionResult.NoOp -> ModeTransitionResult.NoOp
        }
    }

    /** Prior anchor-star edges must not survive ANCHOR → MESH (contract view of invalidation). */
    fun anchorEdgesInvalidated(previous: ConferenceTopologySnapshot): Set<MediaEdge> =
        if (previous.topologyMode == ConferenceTopologyMode.ANCHOR) previous.actualMediaEdges else emptySet()
}

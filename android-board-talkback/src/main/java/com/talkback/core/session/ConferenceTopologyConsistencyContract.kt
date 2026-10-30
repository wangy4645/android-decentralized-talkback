package com.talkback.core.session

/**
 * Phase 1b-6: downstream consistency contract (S1–S8).
 *
 * Pure functions only — no Coordinator / Recovery / field wiring.
 * See docs/analysis/0056-phase-1b-6-consistency-contract.md
 */
object ConferenceTopologyConsistencyContract {

    /** Tokens that must not appear in topology diagnostic lines (S3). */
    val DIAGNOSTIC_FORBIDDEN_TOKENS = setOf(
        "iceConnected",
        "iceReconnecting",
        "IceEdgeObservation"
    )

    data class DownstreamTopologyView(
        val conferenceId: String,
        val topologyMode: ConferenceTopologyMode,
        val anchorId: String?,
        val anchorEpoch: Long,
        val meshGeneration: Long,
        val rosterEpoch: Long,
        val members: List<String>,
        val actualMediaEdgeCount: Int,
        val presenceReadPath: ConferencePresenceReadPath,
        val diagnosticLine: String
    )

    sealed class BindResult {
        data class Ok(val view: DownstreamTopologyView) : BindResult()
        data class Rejected(val reason: String) : BindResult()
    }

    /**
     * S4: single-snapshot atomic consistency (F7 + mode validators).
     */
    fun validateAtomicConsistency(snapshot: ConferenceTopologySnapshot): SnapshotValidity =
        ConferenceTopologyModeTransitionContract.validateModeSnapshot(snapshot)

    /**
     * Sole downstream bind entry for Presence / UI / Diagnostic field sourcing (S1–S3, S7, S8).
     */
    fun bindDownstreamView(snapshot: ConferenceTopologySnapshot): BindResult {
        return when (val validity = validateAtomicConsistency(snapshot)) {
            is SnapshotValidity.Invalid -> BindResult.Rejected(validity.reason)
            SnapshotValidity.Valid -> BindResult.Ok(
                DownstreamTopologyView(
                    conferenceId = snapshot.conferenceId,
                    topologyMode = snapshot.topologyMode,
                    anchorId = snapshot.anchorId,
                    anchorEpoch = snapshot.anchorEpoch,
                    meshGeneration = snapshot.meshGeneration,
                    rosterEpoch = snapshot.rosterEpoch,
                    members = snapshot.members,
                    actualMediaEdgeCount = snapshot.actualMediaEdges.size,
                    presenceReadPath = ConferenceTopologyModeTransitionContract.presenceReadPath(
                        snapshot.topologyMode
                    ),
                    diagnosticLine = diagnosticFromSnapshot(snapshot)
                )
            )
        }
    }

    /** S4: downstream layer must not merge fields from two snapshots. */
    fun attemptCrossSnapshotBind(
        primary: ConferenceTopologySnapshot,
        secondary: ConferenceTopologySnapshot
    ): SnapshotValidity {
        if (primary.conferenceId != secondary.conferenceId) {
            return SnapshotValidity.Invalid("cross-snapshot bind forbidden")
        }
        if (primary === secondary) {
            return validateAtomicConsistency(primary)
        }
        val hybrid = primary.copy(
            anchorId = secondary.anchorId,
            anchorEpoch = secondary.anchorEpoch,
            meshGeneration = primary.meshGeneration,
            actualMediaEdges = secondary.actualMediaEdges,
            topologyMode = secondary.topologyMode
        )
        val validity = validateAtomicConsistency(hybrid)
        return if (validity is SnapshotValidity.Valid) {
            SnapshotValidity.Invalid("cross-snapshot bind forbidden")
        } else {
            SnapshotValidity.Invalid("cross-snapshot bind forbidden")
        }
    }

    /** S5: after failover publish, downstream must reflect current anchor only. */
    fun failoverDownstreamReflectsCurrent(
        previous: ConferenceTopologySnapshot,
        current: ConferenceTopologySnapshot
    ): Boolean {
        if (!ConferenceTopologyContract.isStaleSnapshot(previous, current)) return false
        val bind = bindDownstreamView(current)
        if (bind !is BindResult.Ok) return false
        val view = bind.view
        return view.anchorId == current.anchorId &&
            view.anchorEpoch == current.anchorEpoch &&
            view.meshGeneration == current.meshGeneration &&
            view.anchorId != previous.anchorId
    }

    /** S6: MESH downstream must not expose fabricated anchor or admitted edges. */
    fun validateMeshDownstreamInvariants(view: DownstreamTopologyView): SnapshotValidity {
        if (view.topologyMode != ConferenceTopologyMode.MESH) {
            return SnapshotValidity.Invalid("expected MESH view")
        }
        if (view.anchorId != null) {
            return SnapshotValidity.Invalid("MESH view must not carry anchorId")
        }
        if (view.anchorEpoch != 0L) {
            return SnapshotValidity.Invalid("MESH view must not carry active anchorEpoch")
        }
        if (view.actualMediaEdgeCount != 0) {
            return SnapshotValidity.Invalid("MESH view must not carry admitted edges")
        }
        if (view.presenceReadPath != ConferencePresenceReadPath.MESH_SESSION_OBSERVATION) {
            return SnapshotValidity.Invalid("MESH must use session observation path")
        }
        return SnapshotValidity.Valid
    }

    /** S3: diagnostic is snapshot → log only. */
    fun diagnosticFromSnapshot(snapshot: ConferenceTopologySnapshot): String =
        ConferenceTopologySnapshotLog.format(snapshot)

    fun diagnosticIsSnapshotOnly(line: String): Boolean =
        DIAGNOSTIC_FORBIDDEN_TOKENS.none { token -> line.contains(token, ignoreCase = true) }

    /** S2 helper: lineage generation exposed to UI bind path. */
    fun uiLineageGeneration(snapshot: ConferenceTopologySnapshot): Long? =
        when (val bind = bindDownstreamView(snapshot)) {
            is BindResult.Ok -> bind.view.meshGeneration
            is BindResult.Rejected -> null
        }

    /** S7: downstream reads must not mutate snapshot. */
    fun <T> readWithoutMutatingSnapshot(
        snapshot: ConferenceTopologySnapshot,
        read: (ConferenceTopologySnapshot) -> T
    ): Pair<ConferenceTopologySnapshot, T> {
        val copyBefore = snapshot.copy(
            members = snapshot.members.toList(),
            actualMediaEdges = snapshot.actualMediaEdges.toSet()
        )
        val result = read(snapshot)
        val copyAfter = snapshot.copy(
            members = snapshot.members.toList(),
            actualMediaEdges = snapshot.actualMediaEdges.toSet()
        )
        return copyBefore to result.also {
            require(copyBefore == copyAfter) { "snapshot mutated during downstream read" }
        }
    }

    /** S8: repeated bind yields identical views. */
    fun repeatedBindStable(snapshot: ConferenceTopologySnapshot, iterations: Int = 3): Boolean {
        require(iterations >= 2) { "iterations must be >= 2" }
        val first = bindDownstreamView(snapshot)
        repeat(iterations - 1) {
            if (bindDownstreamView(snapshot) != first) return false
        }
        val line = diagnosticFromSnapshot(snapshot)
        repeat(iterations - 1) {
            if (diagnosticFromSnapshot(snapshot) != line) return false
        }
        return true
    }

    fun presenceSnapshotLineageFromTopology(
        snapshot: ConferenceTopologySnapshot,
        iceConnectedRemoteIds: Set<String> = emptySet(),
        producedAtMs: Long = 100_000L
    ): ConferencePresenceSnapshot? {
        if (snapshot.topologyMode != ConferenceTopologyMode.ANCHOR) return null
        val anchorId = snapshot.anchorId ?: return null
        return ConferencePresenceFactsAdapter.snapshotFromLocalObservation(
            conferenceId = snapshot.conferenceId,
            producerModuleId = anchorId,
            rosterEpoch = snapshot.rosterEpoch,
            anchorEpoch = snapshot.anchorEpoch,
            meshGeneration = snapshot.meshGeneration,
            producedAtMs = producedAtMs,
            localModuleId = snapshot.hostModuleId,
            iceConnectedRemoteIds = iceConnectedRemoteIds
        )
    }
}

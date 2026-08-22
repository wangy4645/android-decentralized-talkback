package com.talkback.core.session

import androidx.annotation.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 1b-1/1b-4: single publish owner for [ConferenceTopologySnapshot].
 * All [ConferenceTopologySnapshot.actualMediaEdges] changes route through
 * [ConferenceMediaEdgeAdmissionContract] then capacity evaluate then atomic install —
 * no second truth store. Capacity is a gate, not a producer.
 */
class ConferenceTopologyAuthority(
    private val onLog: ((String) -> Unit)? = null
) {
    private val snapshotsByConferenceId = ConcurrentHashMap<String, ConferenceTopologySnapshot>()
    private val previousSnapshotsByConferenceId = ConcurrentHashMap<String, ConferenceTopologySnapshot>()
    private val authorizedTransitionsByConferenceId = ConcurrentHashMap<String, AuthorizedTransition>()

    sealed class PublishResult {
        data class Published(
            val snapshot: ConferenceTopologySnapshot,
            val mediaEdgeCause: MediaEdgeAdmissionCause? = null,
            val modeTransition: TopologyModeTransition? = null,
            val authorizedTransition: AuthorizedTransition? = null
        ) : PublishResult()
        data class Rejected(val reason: String) : PublishResult()
        data object Unchanged : PublishResult()
    }

    fun currentSnapshot(conferenceId: String): ConferenceTopologySnapshot? =
        snapshotsByConferenceId[conferenceId]

    /** Snapshot displaced by the last successful publish (same transaction as [currentAuthorizedTransition]). */
    fun previousSnapshot(conferenceId: String): ConferenceTopologySnapshot? =
        previousSnapshotsByConferenceId[conferenceId]

    fun currentAuthorizedTransition(conferenceId: String): AuthorizedTransition? =
        authorizedTransitionsByConferenceId[conferenceId]

    fun clear(conferenceId: String) {
        snapshotsByConferenceId.remove(conferenceId)
        previousSnapshotsByConferenceId.remove(conferenceId)
        authorizedTransitionsByConferenceId.remove(conferenceId)
    }

    /** M1 / first anchor publish: topology projection (not ICE). */
    fun publishTopologyProjection(anchorInput: AnchorAdmissionInput): PublishResult =
        when (val admission = ConferenceMediaEdgeAdmissionContract.admitFromTopologyProjection(anchorInput)) {
            is MediaEdgeAdmissionResult.Admitted ->
                installFromMediaEdgeAdmission(admission.snapshot, MediaEdgeAdmissionCause.TOPOLOGY_PROJECTION)
            is MediaEdgeAdmissionResult.Rejected -> PublishResult.Rejected(admission.reason)
            MediaEdgeAdmissionResult.NoOp -> PublishResult.Unchanged
        }

    /** M5/M6: roster-driven edge set under fixed anchor. */
    fun publishRosterEdgeChange(conferenceId: String, newMembers: List<String>): PublishResult {
        val current = snapshotsByConferenceId[conferenceId]
            ?: return PublishResult.Rejected("no current snapshot for roster edge change")
        return when (val admission = ConferenceMediaEdgeAdmissionContract.admitFromRosterChange(current, newMembers)) {
            is MediaEdgeAdmissionResult.Admitted ->
                installFromMediaEdgeAdmission(admission.snapshot, MediaEdgeAdmissionCause.ROSTER_EDGE_CHANGE)
            is MediaEdgeAdmissionResult.Rejected -> PublishResult.Rejected(admission.reason)
            MediaEdgeAdmissionResult.NoOp -> PublishResult.Unchanged
        }
    }

    /** M8: anchor failover replaces admitted edge set. */
    fun publishAnchorFailover(conferenceId: String, newAnchorId: String): PublishResult {
        val current = snapshotsByConferenceId[conferenceId]
            ?: return PublishResult.Rejected("no current snapshot for anchor failover")
        return when (val admission = ConferenceMediaEdgeAdmissionContract.admitFromAnchorFailover(current, newAnchorId)) {
            is MediaEdgeAdmissionResult.Admitted ->
                installFromMediaEdgeAdmission(admission.snapshot, MediaEdgeAdmissionCause.ANCHOR_FAILOVER)
            is MediaEdgeAdmissionResult.Rejected -> PublishResult.Rejected(admission.reason)
            MediaEdgeAdmissionResult.NoOp -> PublishResult.Unchanged
        }
    }

    /** @deprecated Use [publishTopologyProjection] — 1b-4 media edge admission path. */
    fun publishAnchorAdmission(input: AnchorAdmissionInput): PublishResult =
        publishTopologyProjection(input)

    /** T1/T4: publish MESH or post-transition snapshot (empty ActualMediaEdgeSet). */
    fun publishModeTransition(
        snapshot: ConferenceTopologySnapshot,
        transition: TopologyModeTransition
    ): PublishResult {
        when (val validity = ConferenceTopologyModeTransitionContract.validateModeSnapshot(snapshot)) {
            is SnapshotValidity.Invalid -> return PublishResult.Rejected(validity.reason)
            SnapshotValidity.Valid -> Unit
        }
        val current = snapshotsByConferenceId[snapshot.conferenceId]
        if (current == snapshot) {
            return PublishResult.Unchanged
        }
        if (current != null && isStaleModeTransition(snapshot, current, transition)) {
            return PublishResult.Rejected("stale mode transition snapshot")
        }
        return installAtomic(
            candidate = snapshot,
            previous = current,
            mediaEdgeCause = null,
            modeTransition = transition
        )
    }

    private fun rejectIfCapacityInvalid(
        candidate: ConferenceTopologySnapshot
    ): PublishResult.Rejected? =
        when (val cap = ConferenceCapacityGateContract.evaluate(candidate)) {
            is ConferenceCapacityEvaluation.Rejected -> PublishResult.Rejected(cap.reason)
            ConferenceCapacityEvaluation.MeshAdmitted,
            is ConferenceCapacityEvaluation.AnchorAdmitted -> null
        }

    private fun isStaleModeTransition(
        candidate: ConferenceTopologySnapshot,
        current: ConferenceTopologySnapshot,
        transition: TopologyModeTransition
    ): Boolean = when (transition) {
        TopologyModeTransition.MESH_TO_ANCHOR,
        TopologyModeTransition.ANCHOR_TO_MESH ->
            candidate.meshGeneration < current.meshGeneration
        TopologyModeTransition.UNCHANGED_MESH,
        TopologyModeTransition.UNCHANGED_ANCHOR ->
            ConferenceTopologyContract.isStaleSnapshot(candidate, current)
    }

    private fun installFromMediaEdgeAdmission(
        candidate: ConferenceTopologySnapshot,
        cause: MediaEdgeAdmissionCause
    ): PublishResult {
        when (val validity = ConferenceTopologyContract.validateSnapshot(candidate)) {
            is SnapshotValidity.Invalid -> return PublishResult.Rejected(validity.reason)
            SnapshotValidity.Valid -> Unit
        }
        val current = snapshotsByConferenceId[candidate.conferenceId]
        if (current != null) {
            if (ConferenceTopologyContract.isStaleSnapshot(candidate, current)) {
                return PublishResult.Rejected("stale snapshot")
            }
            if (candidate == current) {
                return PublishResult.Unchanged
            }
        }
        return installAtomic(
            candidate = candidate,
            previous = current,
            mediaEdgeCause = cause,
            modeTransition = null
        )
    }

    private fun installAtomic(
        candidate: ConferenceTopologySnapshot,
        previous: ConferenceTopologySnapshot?,
        mediaEdgeCause: MediaEdgeAdmissionCause?,
        modeTransition: TopologyModeTransition?
    ): PublishResult {
        rejectIfCapacityInvalid(candidate)?.let { return it }
        val authorized = when (
            val composed = ConferenceAuthorizedTransitionContract.compose(previous, candidate)
        ) {
            is AuthorizedTransitionCompose.Invalid ->
                return PublishResult.Rejected(composed.reason)
            AuthorizedTransitionCompose.None -> null
            is AuthorizedTransitionCompose.Emitted -> composed.transition
        }
        val conferenceId = candidate.conferenceId
        snapshotsByConferenceId[conferenceId] = candidate
        if (previous != null) {
            previousSnapshotsByConferenceId[conferenceId] = previous
        } else {
            previousSnapshotsByConferenceId.remove(conferenceId)
        }
        if (authorized != null) {
            authorizedTransitionsByConferenceId[conferenceId] = authorized
        } else {
            authorizedTransitionsByConferenceId.remove(conferenceId)
        }
        onLog?.invoke(ConferenceTopologySnapshotLog.format(candidate))
        return PublishResult.Published(
            snapshot = candidate,
            mediaEdgeCause = mediaEdgeCause,
            modeTransition = modeTransition,
            authorizedTransition = authorized
        )
    }

    /** Test-only entry to assert validate rejects half-topology before install (F7). */
    @VisibleForTesting
    internal fun publishValidatedForTest(candidate: ConferenceTopologySnapshot): PublishResult =
        installFromMediaEdgeAdmission(candidate, MediaEdgeAdmissionCause.TOPOLOGY_PROJECTION)
}

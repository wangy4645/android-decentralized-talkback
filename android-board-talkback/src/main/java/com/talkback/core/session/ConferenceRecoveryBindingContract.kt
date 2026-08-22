package com.talkback.core.session

/**
 * Phase 2-0: recovery binding contract (R1–R8).
 *
 * Pure projection: topology snapshot → recovery eligibility.
 * Does not call Recovery Controller, ICE, Health, or Coordinator.
 * See docs/analysis/0056-phase-2-0-recovery-binding-contract.md
 */
enum class RecoveryTargetSource {
    CURRENT_GENERATION,
    AUTHORIZED_TRANSITION
}

data class AdmittedRecoveryTarget internal constructor(
    val conferenceId: String,
    val mediaEdge: MediaEdge,
    val meshGeneration: Long,
    val anchorEpoch: Long,
    val source: RecoveryTargetSource
)

data class AuthorizedTransition(
    val fromMeshGeneration: Long,
    val toMeshGeneration: Long,
    val fromAnchorEpoch: Long,
    val toAnchorEpoch: Long,
    val affectedEdges: Set<MediaEdge>
)

data class RecoveryTargetSnapshot(
    val conferenceId: String,
    val rosterEpoch: Long,
    val anchorEpoch: Long,
    val meshGeneration: Long,
    val targets: Set<AdmittedRecoveryTarget>
)

sealed class TransitionValidity {
    data object Valid : TransitionValidity()
    data class Invalid(val reason: String) : TransitionValidity()
}

object ConferenceRecoveryBindingContract {

    fun project(
        snapshot: ConferenceTopologySnapshot,
        previousSnapshot: ConferenceTopologySnapshot? = null,
        authorizedTransitions: Set<AuthorizedTransition> = emptySet()
    ): RecoveryTargetSnapshot =
        RecoveryEdgeProvider.project(snapshot, previousSnapshot, authorizedTransitions)

    fun isEligible(
        edge: MediaEdge,
        snapshot: ConferenceTopologySnapshot,
        previousSnapshot: ConferenceTopologySnapshot? = null,
        authorizedTransitions: Set<AuthorizedTransition> = emptySet()
    ): Boolean = RecoveryEdgeProvider.project(snapshot, previousSnapshot, authorizedTransitions)
        .targets
        .any { it.mediaEdge == edge }

    fun isEligibleTarget(
        target: AdmittedRecoveryTarget,
        snapshot: ConferenceTopologySnapshot,
        previousSnapshot: ConferenceTopologySnapshot? = null,
        authorizedTransitions: Set<AuthorizedTransition> = emptySet()
    ): Boolean {
        if (target.conferenceId != snapshot.conferenceId) return false
        if (target.meshGeneration != snapshot.meshGeneration) return false
        return isEligible(target.mediaEdge, snapshot, previousSnapshot, authorizedTransitions)
    }

    fun validateAuthorizedTransition(
        transition: AuthorizedTransition,
        snapshot: ConferenceTopologySnapshot,
        previousSnapshot: ConferenceTopologySnapshot?
    ): TransitionValidity {
        if (transition.affectedEdges.isEmpty()) {
            return TransitionValidity.Invalid("empty affectedEdges (no wildcard)")
        }
        if (transition.toMeshGeneration != snapshot.meshGeneration) {
            return TransitionValidity.Invalid("toMeshGeneration must equal current snapshot")
        }
        if (transition.fromMeshGeneration >= transition.toMeshGeneration) {
            return TransitionValidity.Invalid("fromMeshGeneration must be less than toMeshGeneration")
        }
        if (transition.toAnchorEpoch != snapshot.anchorEpoch) {
            return TransitionValidity.Invalid("toAnchorEpoch must equal current snapshot")
        }
        if (previousSnapshot != null) {
            if (transition.fromMeshGeneration != previousSnapshot.meshGeneration) {
                return TransitionValidity.Invalid("fromMeshGeneration must equal previous snapshot")
            }
            if (transition.fromAnchorEpoch != previousSnapshot.anchorEpoch) {
                return TransitionValidity.Invalid("fromAnchorEpoch must equal previous snapshot")
            }
            if (!previousSnapshot.actualMediaEdges.containsAll(transition.affectedEdges)) {
                return TransitionValidity.Invalid("affectedEdges must be subset of previous ActualMediaEdgeSet")
            }
        }
        return TransitionValidity.Valid
    }

    fun iceObservationIsRecoverable(
        observation: IceEdgeObservation,
        snapshot: ConferenceTopologySnapshot,
        previousSnapshot: ConferenceTopologySnapshot? = null,
        authorizedTransitions: Set<AuthorizedTransition> = emptySet()
    ): Boolean {
        if (!ConferenceTopologyContract.isAdmittedByTopology(observation, snapshot)) {
            return false
        }
        val edge = ConferenceTopologyContract.mediaEdgeForObservation(observation, snapshot) ?: return false
        return isEligible(edge, snapshot, previousSnapshot, authorizedTransitions)
    }

    /**
     * Phase 2-3 consume-only: ConferenceEdgeKey remote vs Provider projection.
     * Controller MUST NOT re-derive targets; it only asks this question.
     */
    fun admitsConferenceRemote(
        targets: RecoveryTargetSnapshot,
        sessionId: String,
        remoteModuleId: String
    ): Boolean {
        if (targets.conferenceId != sessionId) return false
        return targets.targets.any { target ->
            target.meshGeneration == targets.meshGeneration &&
                (
                    target.mediaEdge.anchorModuleId == remoteModuleId ||
                        target.mediaEdge.remoteModuleId == remoteModuleId
                    )
        }
    }

    fun admitsMediaEdge(
        targets: RecoveryTargetSnapshot,
        sessionId: String,
        edge: MediaEdge
    ): Boolean {
        if (targets.conferenceId != sessionId) return false
        return targets.targets.any {
            it.meshGeneration == targets.meshGeneration && it.mediaEdge == edge
        }
    }

    fun bindConferenceEdgeKey(
        sessionId: String,
        localModuleId: String,
        target: AdmittedRecoveryTarget
    ): ConferenceEdgeKey {
        val remote = if (localModuleId == target.mediaEdge.anchorModuleId) {
            target.mediaEdge.remoteModuleId
        } else {
            target.mediaEdge.anchorModuleId
        }
        return ConferenceEdgeKey(sessionId, remote)
    }
}

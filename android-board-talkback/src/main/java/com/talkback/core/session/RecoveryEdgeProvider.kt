package com.talkback.core.session

/**
 * Phase 2-1: sole producer of [AdmittedRecoveryTarget].
 *
 * Stateless `f(snapshot[, previous, transitions])`.
 * MUST NOT create PeerConnection, retry, watchdog, mutate topology,
 * infer transitions, or touch Recovery Controller lifecycle.
 */
object RecoveryEdgeProvider {

    fun project(
        snapshot: ConferenceTopologySnapshot,
        previousSnapshot: ConferenceTopologySnapshot? = null,
        authorizedTransitions: Set<AuthorizedTransition> = emptySet()
    ): RecoveryTargetSnapshot {
        val current = currentGenerationTargets(snapshot)
        val transition = transitionTargets(snapshot, previousSnapshot, authorizedTransitions)
        return RecoveryTargetSnapshot(
            conferenceId = snapshot.conferenceId,
            rosterEpoch = snapshot.rosterEpoch,
            anchorEpoch = snapshot.anchorEpoch,
            meshGeneration = snapshot.meshGeneration,
            targets = current + transition
        )
    }

    private fun admit(
        snapshot: ConferenceTopologySnapshot,
        edge: MediaEdge,
        source: RecoveryTargetSource
    ) = AdmittedRecoveryTarget(
        conferenceId = snapshot.conferenceId,
        mediaEdge = edge,
        meshGeneration = snapshot.meshGeneration,
        anchorEpoch = snapshot.anchorEpoch,
        source = source
    )

    private fun currentGenerationTargets(snapshot: ConferenceTopologySnapshot): Set<AdmittedRecoveryTarget> {
        if (snapshot.topologyMode == ConferenceTopologyMode.MESH) return emptySet()
        return snapshot.actualMediaEdges.map { edge ->
            admit(snapshot, edge, RecoveryTargetSource.CURRENT_GENERATION)
        }.toSet()
    }

    private fun transitionTargets(
        snapshot: ConferenceTopologySnapshot,
        previousSnapshot: ConferenceTopologySnapshot?,
        authorizedTransitions: Set<AuthorizedTransition>
    ): Set<AdmittedRecoveryTarget> {
        if (snapshot.topologyMode != ConferenceTopologyMode.ANCHOR) return emptySet()
        return authorizedTransitions.flatMap { transition ->
            when (
                ConferenceRecoveryBindingContract.validateAuthorizedTransition(
                    transition,
                    snapshot,
                    previousSnapshot
                )
            ) {
                is TransitionValidity.Invalid -> emptySet()
                TransitionValidity.Valid -> transition.affectedEdges
                    .filter { it !in snapshot.actualMediaEdges }
                    .map { edge ->
                        admit(snapshot, edge, RecoveryTargetSource.AUTHORIZED_TRANSITION)
                    }
            }
        }.toSet()
    }
}

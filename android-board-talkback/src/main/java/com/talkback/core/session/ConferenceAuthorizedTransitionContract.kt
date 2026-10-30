package com.talkback.core.session

/**
 * Phase 2-4: compose/validate [AuthorizedTransition] from two topology snapshots.
 *
 * Topology-generation fact only. Not Recovery policy, grace, ICE, or completion.
 * TopologyAuthority is the future producer (impl not authorized in this drop).
 * Recovery MUST NOT call compose — it only consumes an explicit transition set.
 *
 * See docs/analysis/0056-phase-2-4-authorized-transition-contract.md
 */
sealed class AuthorizedTransitionCompose {
    data object None : AuthorizedTransitionCompose()
    data class Emitted(val transition: AuthorizedTransition) : AuthorizedTransitionCompose()
    data class Invalid(val reason: String) : AuthorizedTransitionCompose()
}

object ConferenceAuthorizedTransitionContract {

    fun compose(
        previous: ConferenceTopologySnapshot?,
        current: ConferenceTopologySnapshot
    ): AuthorizedTransitionCompose {
        if (previous == null) return AuthorizedTransitionCompose.None
        if (previous.conferenceId != current.conferenceId) {
            return AuthorizedTransitionCompose.Invalid("conferenceId mismatch")
        }
        val displaced = previous.actualMediaEdges - current.actualMediaEdges
        val genUnchanged = previous.meshGeneration == current.meshGeneration
        val epochUnchanged = previous.anchorEpoch == current.anchorEpoch
        if (displaced.isEmpty() && genUnchanged && epochUnchanged) {
            return AuthorizedTransitionCompose.None
        }
        if (displaced.isEmpty()) {
            return AuthorizedTransitionCompose.None
        }
        if (current.meshGeneration <= previous.meshGeneration) {
            return AuthorizedTransitionCompose.Invalid("current meshGeneration must exceed previous")
        }
        val transition = AuthorizedTransition(
            fromMeshGeneration = previous.meshGeneration,
            toMeshGeneration = current.meshGeneration,
            fromAnchorEpoch = previous.anchorEpoch,
            toAnchorEpoch = current.anchorEpoch,
            affectedEdges = displaced
        )
        return when (
            ConferenceRecoveryBindingContract.validateAuthorizedTransition(
                transition,
                current,
                previous
            )
        ) {
            is TransitionValidity.Invalid -> AuthorizedTransitionCompose.Invalid("composed transition invalid")
            TransitionValidity.Valid -> AuthorizedTransitionCompose.Emitted(transition)
        }
    }
}

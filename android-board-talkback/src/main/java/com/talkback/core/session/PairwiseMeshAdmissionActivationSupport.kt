package com.talkback.core.session

import com.talkback.core.model.ModuleId

/**
 * #180-C — evaluate unsatisfied pairwise obligations and route offerer activation only.
 */
object PairwiseMeshAdmissionActivationSupport {

    sealed interface ActivationDecision {
        data object NoAction : ActivationDecision
        data class Deferred(val reason: String) : ActivationDecision
        data class IssueInvite(val remoteModuleId: String) : ActivationDecision
    }

    data class ActivationEvaluationInput(
        val localModuleId: ModuleId,
        val obligation: PairwiseMeshAdmissionObligation?,
        val peerEdgeReady: Boolean,
        val sessionValid: Boolean,
        val signalingInFlight: Boolean,
        val iceConnected: Boolean,
        val remoteDiscovered: Boolean,
        val cooldownElapsed: Boolean
    )

    fun shouldUsePairwiseAdmissionInvite(
        session: TalkbackSession,
        remoteModuleId: ModuleId
    ): Boolean {
        val obligation = PairwiseMeshAdmissionSupport.obligationForRemotePeer(session, remoteModuleId)
            ?: return false
        return !obligation.signalingSatisfied && obligation.offerer == session.local.moduleId
    }

    fun isAnswererAwaitingPairwiseOffer(
        session: TalkbackSession,
        remoteModuleId: ModuleId
    ): Boolean {
        val obligation = PairwiseMeshAdmissionSupport.obligationForRemotePeer(session, remoteModuleId)
            ?: return false
        return !obligation.signalingSatisfied && obligation.answerer == session.local.moduleId
    }

    fun evaluate(input: ActivationEvaluationInput): ActivationDecision {
        if (!input.sessionValid) return ActivationDecision.NoAction
        val obligation = input.obligation ?: return ActivationDecision.NoAction
        if (obligation.signalingSatisfied) return ActivationDecision.NoAction
        if (obligation.answerer == input.localModuleId) {
            return ActivationDecision.Deferred("ANSWERER_AWAITING_OFFER")
        }
        if (obligation.offerer != input.localModuleId) {
            return ActivationDecision.NoAction
        }
        if (input.iceConnected) return ActivationDecision.NoAction
        if (!input.peerEdgeReady) {
            return ActivationDecision.Deferred("PEER_EDGE_NOT_READY")
        }
        if (input.signalingInFlight) {
            return ActivationDecision.Deferred("SIGNALING_IN_FLIGHT")
        }
        if (!input.remoteDiscovered) {
            return ActivationDecision.Deferred("REMOTE_NOT_DISCOVERED")
        }
        if (!input.cooldownElapsed) {
            return ActivationDecision.Deferred("COOLDOWN")
        }
        return ActivationDecision.IssueInvite(obligation.answerer.value)
    }

    fun unsatisfiedOffererPeerIds(session: TalkbackSession): List<String> =
        session.pairwiseMeshAdmissionObligations.values
            .asSequence()
            .filter { !it.signalingSatisfied && it.offerer == session.local.moduleId }
            .map { it.answerer.value }
            .sorted()
            .toList()
}

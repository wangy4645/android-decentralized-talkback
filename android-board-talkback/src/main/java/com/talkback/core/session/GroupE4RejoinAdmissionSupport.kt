package com.talkback.core.session

import com.talkback.core.model.EndpointAddress

/**
 * ADR-0053 E4 — pure formerly-admitted peer rejoin admission evaluator (Q4).
 * Coordinator applies [E4RejoinAdmissionDecision] via the existing GROUP_INVITE path.
 */
object GroupE4RejoinAdmissionSupport {

    sealed interface E4RejoinAdmissionDecision {
        data object NoAction : E4RejoinAdmissionDecision
        data class Deferred(val reason: String) : E4RejoinAdmissionDecision
        data class IssueInvite(val moduleId: String, val endpoint: EndpointAddress) : E4RejoinAdmissionDecision
    }

    data class EvaluationInput(
        val admittedPeerHistory: Map<String, FormerAdmittedPeer>,
        val canonicalModuleIds: Set<String>,
        val pendingInviteeModuleIds: Set<String>,
        val reachableModuleIds: Set<String>,
        val authorityAdmissible: Boolean,
        val isMembershipAuthority: Boolean
    )

    fun evaluate(input: EvaluationInput): List<E4RejoinAdmissionDecision> {
        if (!input.isMembershipAuthority) return emptyList()
        val candidates = input.admittedPeerHistory.keys.filter { moduleId ->
            moduleId !in input.canonicalModuleIds &&
                moduleId !in input.pendingInviteeModuleIds &&
                moduleId in input.reachableModuleIds
        }
        if (candidates.isEmpty()) return emptyList()
        if (!input.authorityAdmissible) {
            return listOf(E4RejoinAdmissionDecision.Deferred("AUTHORITY_NOT_ADMISSIBLE"))
        }
        return candidates.mapNotNull { moduleId ->
            val former = input.admittedPeerHistory[moduleId] ?: return@mapNotNull null
            E4RejoinAdmissionDecision.IssueInvite(moduleId, former.endpoint)
        }
    }

    fun isFormerlyAdmittedNotInCanonicalRoster(session: TalkbackSession, moduleId: String): Boolean =
        moduleId in session.admittedPeerHistory &&
            moduleId !in GroupMembershipSupport.canonicalMemberModuleIds(session).map { it.value }
}

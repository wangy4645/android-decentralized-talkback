package com.talkback.core.session

import com.talkback.core.model.ModuleId

/**
 * #181 Phase 1-A — pure admission domain classifier.
 * Chooses bootstrap vs pairwise mesh vs none from session/membership/edge facts only.
 * Does not inspect ICE, SDP, authority, or planner roles.
 */
object AdmissionClassificationSupport {

    data class AdmissionClassificationContext(
        val hasActiveGroupSession: Boolean,
        val peerInCanonicalRoster: Boolean,
        val requiredEdgeExists: Boolean,
        val edgeSignalingSatisfied: Boolean,
        val coldAdmissionRequired: Boolean
    )

    data class AdmissionClassificationResult(
        val domain: GroupAdmissionDomain,
        val reason: String
    )

    fun classify(context: AdmissionClassificationContext): AdmissionClassificationResult {
        if (context.coldAdmissionRequired) {
            return AdmissionClassificationResult(
                domain = GroupAdmissionDomain.BOOTSTRAP,
                reason = "COLD_ADMISSION_REQUIRED"
            )
        }
        if (context.hasActiveGroupSession &&
            context.peerInCanonicalRoster &&
            context.requiredEdgeExists &&
            !context.edgeSignalingSatisfied
        ) {
            return AdmissionClassificationResult(
                domain = GroupAdmissionDomain.PAIRWISE_MESH,
                reason = "CANONICAL_PEER_UNSATISFIED_EDGE"
            )
        }
        return AdmissionClassificationResult(
            domain = GroupAdmissionDomain.NONE,
            reason = "NO_ADMISSION_DOMAIN"
        )
    }

    fun contextForPeer(
        session: TalkbackSession,
        peerModuleId: ModuleId,
        coldAdmissionRequired: Boolean
    ): AdmissionClassificationContext {
        val hasActiveGroupSession = session.type == SessionType.GROUP && session.accepted
        val canonicalMembers = GroupMembershipSupport.canonicalMemberModuleIds(session)
        val peerInCanonicalRoster = peerModuleId in canonicalMembers
        val requiredEdges = if (hasActiveGroupSession) {
            PairwiseMeshAdmissionSupport.localRequiredEdges(
                topology = session.mediaTopology,
                anchorModuleId = session.anchorModuleId,
                canonicalMembers = canonicalMembers,
                localModuleId = session.local.moduleId
            )
        } else {
            emptySet()
        }
        val edgeKey = PairwiseMeshEdgeKey.of(session.local.moduleId, peerModuleId)
        val requiredEdgeExists = edgeKey in requiredEdges
        val edgeSignalingSatisfied = peerModuleId.value in session.meshCompletedModules
        return AdmissionClassificationContext(
            hasActiveGroupSession = hasActiveGroupSession,
            peerInCanonicalRoster = peerInCanonicalRoster,
            requiredEdgeExists = requiredEdgeExists,
            edgeSignalingSatisfied = edgeSignalingSatisfied,
            coldAdmissionRequired = coldAdmissionRequired
        )
    }
}

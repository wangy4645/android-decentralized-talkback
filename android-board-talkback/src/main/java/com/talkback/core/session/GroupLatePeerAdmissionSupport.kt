package com.talkback.core.session

/**
 * Late-peer admission guards (HELLO → existing per-peer invite path).
 * HELLO ≠ membership; discovery only selects admission candidates.
 */
object GroupLatePeerAdmissionSupport {

    data class CandidateInput(
        val peerModuleId: String,
        val helloChannelId: String?,
        val sessionChannelId: String?,
        val hasAcceptedGroupSession: Boolean,
        val peerInCanonicalRoster: Boolean,
        val isAdmissionOwner: Boolean,
        val topologyReadiness: String,
        val peerIsLocal: Boolean
    )

    sealed interface Decision {
        data class Admit(val peerModuleId: String) : Decision
        data class Skip(val reason: String) : Decision
    }

    object SkipReason {
        const val HELLO_CHANNEL_ABSENT = "HELLO_CHANNEL_ABSENT"
        const val NO_GROUP_SESSION = "NO_GROUP_SESSION"
        const val CHANNEL_MISMATCH = "CHANNEL_MISMATCH"
        const val PEER_IS_LOCAL = "PEER_IS_LOCAL"
        const val ALREADY_CANONICAL = "ALREADY_CANONICAL"
        const val NOT_AUTHORITY_OR_OFFERER = "NOT_AUTHORITY_OR_OFFERER"
        const val TOPOLOGY_DISCOVERING = "TOPOLOGY_DISCOVERING"
        const val TOPOLOGY_MEMBERSHIP_PENDING = "TOPOLOGY_MEMBERSHIP_PENDING"
    }

    private val admissionEligibleReadiness = setOf("BUILDING", "OPERATIONAL")

    fun isTopologyAdmissionEligible(topologyReadiness: String): Boolean =
        topologyReadiness in admissionEligibleReadiness

    fun evaluate(input: CandidateInput): Decision {
        if (input.peerIsLocal) {
            return Decision.Skip(SkipReason.PEER_IS_LOCAL)
        }
        if (!input.hasAcceptedGroupSession) {
            return Decision.Skip(SkipReason.NO_GROUP_SESSION)
        }
        val helloChannel = input.helloChannelId
        val sessionChannel = input.sessionChannelId
        if (helloChannel.isNullOrBlank() || sessionChannel.isNullOrBlank() || helloChannel != sessionChannel) {
            return Decision.Skip(SkipReason.CHANNEL_MISMATCH)
        }
        if (input.peerInCanonicalRoster) {
            return Decision.Skip(SkipReason.ALREADY_CANONICAL)
        }
        if (!input.isAdmissionOwner) {
            return Decision.Skip(SkipReason.NOT_AUTHORITY_OR_OFFERER)
        }
        if (!isTopologyAdmissionEligible(input.topologyReadiness)) {
            val reason = when (input.topologyReadiness) {
                "DISCOVERING" -> SkipReason.TOPOLOGY_DISCOVERING
                "MEMBERSHIP_PENDING" -> SkipReason.TOPOLOGY_MEMBERSHIP_PENDING
                else -> "TOPOLOGY_${input.topologyReadiness}"
            }
            return Decision.Skip(reason)
        }
        return Decision.Admit(input.peerModuleId)
    }
}

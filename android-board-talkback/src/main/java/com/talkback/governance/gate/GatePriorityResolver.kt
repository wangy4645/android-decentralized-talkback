package com.talkback.governance.gate

import com.talkback.governance.capability.Capability

/**
 * Global, stable ordering for primary block reason selection (ADR-0015).
 * Must not depend on OperationPolicy.requires declaration order or collection iteration order.
 */
object GatePriorityResolver {
    private val readinessOrder: List<ReadinessReason> = listOf(
        ReadinessReason.RoutingNotReady,
        ReadinessReason.AuthorityNotReady,
        ReadinessReason.MembershipNotReady,
        ReadinessReason.ConferenceNotReady,
        ReadinessReason.MediaNotReady,
        ReadinessReason.DirectoryNotReady
    )

    private val policyOrder: List<PolicyReason> = listOf(
        PolicyReason.CooldownActive,
        PolicyReason.ChannelBusy,
        PolicyReason.RateLimited,
        PolicyReason.RoleRestricted,
        PolicyReason.OperationNotAllowed,
        PolicyReason.TransitionInProgress
    )

    data class ResolvedBlock(
        val primary: BlockReason,
        val additional: List<BlockReason>,
        val blockingCapability: Capability?
    )

    fun resolve(reasons: List<BlockReason>, blockingCapabilities: Map<BlockReason, Capability>): ResolvedBlock {
        if (reasons.isEmpty()) {
            error("resolve() requires at least one block reason")
        }
        if (reasons.size == 1) {
            val only = reasons.single()
            return ResolvedBlock(only, emptyList(), blockingCapabilities[only])
        }
        val sorted = reasons.sortedWith(comparator)
        val primary = sorted.first()
        return ResolvedBlock(primary, sorted.drop(1), blockingCapabilities[primary])
    }

    private val comparator: Comparator<BlockReason> = Comparator { a, b ->
        val categoryCmp = categoryRank(a.category).compareTo(categoryRank(b.category))
        if (categoryCmp != 0) return@Comparator categoryCmp
        when (a) {
            is ReadinessReason -> {
                val bReadiness = b as? ReadinessReason
                    ?: return@Comparator -1
                readinessOrder.indexOf(a).compareTo(readinessOrder.indexOf(bReadiness))
            }
            is PolicyReason -> {
                val bPolicy = b as? PolicyReason
                    ?: return@Comparator 1
                policyOrder.indexOf(a).compareTo(policyOrder.indexOf(bPolicy))
            }
            else -> a.code.compareTo(b.code)
        }
    }

    private fun categoryRank(category: BlockCategory): Int = when (category) {
        BlockCategory.READINESS -> 0
        BlockCategory.POLICY -> 1
    }
}

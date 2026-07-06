package com.talkback.governance.gate

import com.talkback.governance.capability.Capability
import com.talkback.governance.capability.CapabilityProbe
import com.talkback.governance.capability.CapabilityReadiness
import com.talkback.governance.capability.CapabilitySnapshot
import com.talkback.governance.capability.blocksAdmission
import com.talkback.governance.capability.capabilitySnapshot
import com.talkback.governance.transition.TransitionId
import com.talkback.governance.transition.TransitionRecord
import com.talkback.governance.transition.TransitionTrigger

class OperationGate(
    private val policyProvider: (Operation) -> OperationPolicyRule = OperationPolicy::rule
) {
    fun canStart(
        operation: Operation,
        channelId: String,
        snapshot: CapabilitySnapshot,
        activeTransition: TransitionRecord?,
        nowMs: Long = System.currentTimeMillis(),
        extraPolicyBlocks: List<PolicyBlock> = emptyList()
    ): GateDecision {
        val policy = policyProvider(operation)
        val transitionId = activeTransition?.id ?: TransitionId.NONE
        val reasons = LinkedHashMap<BlockReason, com.talkback.governance.capability.Capability?>()

        policy.requiredCapabilities.forEach { capability ->
            val readiness = snapshot.readiness(capability)
            if (readiness.blocksAdmissionForPtt(operation, capability)) {
                reasons[CapabilityBlockReasons.readinessReason(capability)] = capability
            }
        }

        if (policy.blocksDuringActiveTransition && activeTransition?.isActive == true) {
            val inviteDuringMeetingStart =
                operation == Operation.MEETING_INVITE &&
                    activeTransition.trigger == TransitionTrigger.MEETING_START
            if (!inviteDuringMeetingStart) {
                reasons[PolicyReason.TransitionInProgress] = null
            }
        }

        extraPolicyBlocks.forEach { block ->
            reasons[block.reason] = null
        }

        if (reasons.isEmpty()) {
            return GateDecision.Allow(transitionId)
        }

        val blockReasons = reasons.keys.toList()
        val resolved = GatePriorityResolver.resolve(
            reasons = blockReasons,
            blockingCapabilities = reasons.filterValues { it != null }
                .mapKeys { it.key }
                .mapValues { it.value!! }
        )
        val retryAfterMs = extraPolicyBlocks
            .firstOrNull { it.reason == resolved.primary }
            ?.retryAfterMs

        return GateDecision.Blocked(
            primaryReason = resolved.primary,
            additionalReasons = resolved.additional,
            category = resolved.primary.category,
            blockingCapability = resolved.blockingCapability,
            retryAfterMs = retryAfterMs,
            transitionId = transitionId
        )
    }

    private fun CapabilityReadiness.blocksAdmissionForPtt(
        operation: Operation,
        capability: Capability
    ): Boolean =
        when {
            this == CapabilityReadiness.READY -> false
            operation == Operation.PTT &&
                capability in PTT_RECONCILING_ALLOWED_CAPABILITIES &&
                this == CapabilityReadiness.RECONCILING -> false
            else -> blocksAdmission()
        }

    private companion object {
        val PTT_RECONCILING_ALLOWED_CAPABILITIES = setOf(
            Capability.Membership,
            Capability.Authority,
            Capability.Routing
        )
    }
}

data class PolicyBlock(
    val reason: PolicyReason,
    val retryAfterMs: Long? = null
)

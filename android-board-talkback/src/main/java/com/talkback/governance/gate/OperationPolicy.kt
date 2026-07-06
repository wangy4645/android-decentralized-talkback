package com.talkback.governance.gate

import com.talkback.governance.capability.Capability

enum class Operation {
    PTT,
    MEETING_INVITE,
    MEETING_JOIN,
    SINGLE_CALL,
    GROUP_BOOTSTRAP
}

data class OperationPolicyRule(
    val requiredCapabilities: Set<Capability>,
    val blocksDuringActiveTransition: Boolean = true,
    val operationTimeoutMs: Long? = null
)

object OperationPolicy {
    private val rules: Map<Operation, OperationPolicyRule> = mapOf(
        Operation.PTT to OperationPolicyRule(
            requiredCapabilities = setOf(
                Capability.Membership,
                Capability.Authority,
                Capability.Routing
            )
        ),
        Operation.MEETING_INVITE to OperationPolicyRule(
            requiredCapabilities = setOf(Capability.Conference),
            blocksDuringActiveTransition = true
        ),
        Operation.MEETING_JOIN to OperationPolicyRule(
            requiredCapabilities = emptySet(),
            blocksDuringActiveTransition = false
        ),
        Operation.SINGLE_CALL to OperationPolicyRule(
            requiredCapabilities = setOf(
                Capability.Directory,
                Capability.Routing
            )
        ),
        Operation.GROUP_BOOTSTRAP to OperationPolicyRule(
            requiredCapabilities = emptySet(),
            blocksDuringActiveTransition = false
        )
    )

    fun rule(operation: Operation): OperationPolicyRule =
        rules[operation]
            ?: error("OperationPolicy missing rule for $operation")
}

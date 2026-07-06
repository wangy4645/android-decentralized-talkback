package com.talkback.governance.gate

import com.talkback.governance.capability.Capability
import com.talkback.governance.transition.TransitionId

sealed interface GateDecision {
    val transitionId: TransitionId

    data class Allow(override val transitionId: TransitionId) : GateDecision

    data class Blocked(
        val primaryReason: BlockReason,
        val additionalReasons: List<BlockReason>,
        val category: BlockCategory,
        val blockingCapability: Capability?,
        val retryAfterMs: Long?,
        override val transitionId: TransitionId
    ) : GateDecision {
        val allReasons: List<BlockReason> = listOf(primaryReason) + additionalReasons
    }
}

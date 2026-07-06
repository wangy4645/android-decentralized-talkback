package com.talkback.governance.transition

import com.talkback.core.model.EndpointId

/**
 * ADR-0017: explicit MEETING_START intent snapshot. Immutable after [DeclarationPhase.FROZEN].
 */
data class MeetingStartDeclaration(
    val mode: MeetingMode,
    val expectedInviteTargets: Set<EndpointId>,
    val inviteDispatchFinished: Boolean,
    val phase: DeclarationPhase
) {
    val isFrozen: Boolean get() = phase == DeclarationPhase.FROZEN

    companion object {
        fun validateConsistency(mode: MeetingMode, targets: Set<EndpointId>): String? =
            when {
                mode == MeetingMode.SOLO_HOST && targets.isNotEmpty() -> "solo_with_targets"
                mode == MeetingMode.MULTI_PARTY && targets.isEmpty() -> "multi_without_targets"
                else -> null
            }

        fun open(mode: MeetingMode, targets: Set<EndpointId>): MeetingStartDeclaration? {
            if (validateConsistency(mode, targets) != null) return null
            return MeetingStartDeclaration(
                mode = mode,
                expectedInviteTargets = targets,
                inviteDispatchFinished = false,
                phase = DeclarationPhase.OPEN
            )
        }

        fun frozen(
            mode: MeetingMode,
            targets: Set<EndpointId>,
            inviteDispatchFinished: Boolean
        ): MeetingStartDeclaration? {
            if (validateConsistency(mode, targets) != null) return null
            return MeetingStartDeclaration(
                mode = mode,
                expectedInviteTargets = targets,
                inviteDispatchFinished = inviteDispatchFinished,
                phase = DeclarationPhase.FROZEN
            )
        }
    }
}

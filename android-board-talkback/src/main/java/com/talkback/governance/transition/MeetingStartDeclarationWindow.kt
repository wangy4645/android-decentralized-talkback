package com.talkback.governance.transition

import com.talkback.core.model.EndpointId

/**
 * ADR-0017: Declaration Owner window for a single MEETING_START transition on one channel.
 */
class MeetingStartDeclarationWindow {
    private var declaration: MeetingStartDeclaration? = null

    fun current(): MeetingStartDeclaration? = declaration

    fun open(mode: MeetingMode, targets: Set<EndpointId>): MeetingStartDeclaration? {
        if (declaration != null) return null
        val opened = MeetingStartDeclaration.open(mode, targets) ?: return null
        declaration = opened
        return opened
    }

    fun freeze(inviteDispatchFinished: Boolean): MeetingStartDeclaration? {
        val current = declaration ?: return null
        if (current.isFrozen) return null
        val frozen = MeetingStartDeclaration.frozen(
            current.mode,
            current.expectedInviteTargets,
            inviteDispatchFinished
        ) ?: return null
        declaration = frozen
        return frozen
    }

    fun clear() {
        declaration = null
    }
}

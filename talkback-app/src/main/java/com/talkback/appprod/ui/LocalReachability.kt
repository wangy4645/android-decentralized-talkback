package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceMembershipLifecycle

/**
 * Sole presentation owner for per-peer user-visible reachability (ADR-0028 R30-J).
 * Pure projection — no timers, latch, or cache.
 */
object LocalReachability {

    enum class MembershipState {
        JOINED,
        INVITED,
        LEFT
    }

    enum class ParticipantPresenceState {
        ONLINE,
        RECONNECTING,
        JOINING,
        OFFLINE,
        LEFT
    }

    data class Result(
        val state: ParticipantPresenceState
    )

    @Suppress("UNUSED_PARAMETER")
    fun resolve(
        membership: MembershipState,
        receivePathLive: Boolean,
        recovering: Boolean,
        mediaUnavailable: Boolean,
        everConnected: Boolean
    ): Result {
        if (membership == MembershipState.LEFT) {
            return Result(ParticipantPresenceState.LEFT)
        }
        if (receivePathLive) {
            return Result(ParticipantPresenceState.ONLINE)
        }
        if (mediaUnavailable || everConnected) {
            return Result(ParticipantPresenceState.RECONNECTING)
        }
        return Result(ParticipantPresenceState.JOINING)
    }

    fun ConferenceMembershipLifecycle.toMembershipState(): MembershipState =
        when (this) {
            ConferenceMembershipLifecycle.JOINED -> MembershipState.JOINED
            ConferenceMembershipLifecycle.INVITED -> MembershipState.INVITED
            ConferenceMembershipLifecycle.LEFT,
            ConferenceMembershipLifecycle.PRUNED,
            ConferenceMembershipLifecycle.REJOIN_REQUIRED -> MembershipState.LEFT
        }
}

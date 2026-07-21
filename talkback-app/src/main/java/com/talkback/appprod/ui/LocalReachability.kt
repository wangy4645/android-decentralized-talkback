package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceMembershipLifecycle

/**
 * Sole presentation owner for per-peer user-visible reachability (ADR-0028 R30-J,
 * composition rules frozen by ADR-0030 Presence Projection Contract).
 * Pure projection — no timers, latch, or cache. No projection output may feed back in.
 *
 * ADR-0030 Rule 2: edge signals (recovering, mediaUnavailable) veto media
 * receivePathLive; media cannot imply ONLINE while edge is recovering/unavailable.
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

    fun resolve(
        membership: MembershipState,
        receivePathLive: Boolean,
        recovering: Boolean,
        mediaUnavailable: Boolean,
        mediaEverLive: Boolean
    ): Result {
        if (membership == MembershipState.LEFT) {
            return Result(ParticipantPresenceState.LEFT)
        }
        if (recovering || mediaUnavailable) {
            return Result(ParticipantPresenceState.RECONNECTING)
        }
        if (receivePathLive) {
            return Result(ParticipantPresenceState.ONLINE)
        }
        if (mediaEverLive) {
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

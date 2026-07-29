package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceMembershipLifecycle

/**
 * Local reachability composition (ADR-0028 / ADR-0030).
 * Pure projection — no timers, latch, or cache.
 *
 * **ADR-0034:** user-visible connectivity copy (pill / reconnecting|syncing|degraded)
 * is owned by [UserVisibleConnectivityProjection]. This resolver remains for membership
 * LEFT / JOINING diagnostics and historical presence synthesis; its Rule 2 output
 * **MUST NOT** be consumed as the sole reconnecting UX truth when media is usable.
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

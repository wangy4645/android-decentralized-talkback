package com.talkback.core.session

/**
 * Authority-only sources for [com.talkback.app.TalkbackCoordinator.removeConferenceParticipant].
 *
 * ADR-0023 R29-B: conference membership mutation is authority-owned. Recovery failures are
 * **not** mutation sources — they belong to [ConferenceEdgeRecoveryController] facts only.
 */
enum class AuthorityMembershipMutationSource {
    /** Remote peer left; local node applied host/peer GROUP_LEAVE signal. */
    AUTHORITY_GROUP_LEAVE,

    /** Host authoritative prune (ICE unhealthy / health cleanup tick). */
    AUTHORITY_PRUNE,

    /** Local user initiated leave (reserved — current leave path tears down local session). */
    USER_LEAVE,

    /** Host terminated the conference (reserved — current path uses hangupInternal). */
    HOST_TERMINATE
}

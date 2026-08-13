package com.talkback.core.session

/**
 * Payload-semantic classification for [com.talkback.core.model.SignalType.GROUP_INVITE].
 * Orthogonal to [GroupAdmissionDomain] (which admission lifecycle owns the peer).
 */
enum class GroupInvitePayloadSemantic {
    BOOTSTRAP_SDP_INVITE,
    MEMBERSHIP_SNAPSHOT_ONLY,
    PAIRWISE_MESH_SDP_INVITE,
    REJOIN_SDP_INVITE
}

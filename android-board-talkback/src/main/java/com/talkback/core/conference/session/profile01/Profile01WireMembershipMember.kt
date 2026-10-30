package com.talkback.core.conference.session.profile01

/**
 * One entry from a Profile 01 MembershipView (moduleId + membershipIncarnationId).
 */
data class Profile01WireMembershipMember(
    val moduleId: String,
    val membershipIncarnationId: ByteArray,
)

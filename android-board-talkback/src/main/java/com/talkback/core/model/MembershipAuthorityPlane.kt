package com.talkback.core.model

/**
 * Membership control-plane identity for channel authority digest observations.
 * GROUP and CONFERENCE generations on the same channel are not comparable.
 */
enum class MembershipAuthorityPlane {
    GROUP,
    CONFERENCE
}

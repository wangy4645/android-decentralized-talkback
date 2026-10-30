package com.talkback.core.session

import com.talkback.core.model.MembershipAuthorityPlane
import com.talkback.core.model.TopologyDigest

/**
 * Classifies an authority HELLO digest onto GROUP vs CONFERENCE without inferring
 * remote meeting end from ChannelMode. Wire HELLO has no plane field; matching
 * against local plane digests is the only reliable signal.
 *
 * When both planes are live and the digest matches neither, ignore — do not
 * treat it as MEETING_END or overwrite CONFERENCE.
 */
object HelloAuthorityDigestClassifier {

    data class SessionDigest(
        val sessionFamilyId: String,
        val digest: TopologyDigest
    )

    data class Decision(
        val observe: Boolean,
        val plane: MembershipAuthorityPlane?,
        val sessionFamilyId: String?,
        val reason: String
    )

    fun classify(
        incoming: TopologyDigest,
        conference: SessionDigest?,
        group: SessionDigest?
    ): Decision {
        val matchConference = conference != null && sameGeneration(conference.digest, incoming)
        val matchGroup = group != null && sameGeneration(group.digest, incoming)
        return when {
            matchConference -> observe(
                MembershipAuthorityPlane.CONFERENCE,
                conference!!.sessionFamilyId,
                "HELLO_MATCHES_CONFERENCE"
            )
            matchGroup -> observe(
                MembershipAuthorityPlane.GROUP,
                group!!.sessionFamilyId,
                "HELLO_MATCHES_GROUP"
            )
            conference != null && group != null ->
                ignore("UNCLASSIFIED_HELLO_WHILE_BOTH_PLANES_LIVE")
            conference != null -> observe(
                MembershipAuthorityPlane.CONFERENCE,
                conference.sessionFamilyId,
                "HELLO_CONFERENCE_ONLY_CONTEXT"
            )
            group != null -> observe(
                MembershipAuthorityPlane.GROUP,
                group.sessionFamilyId,
                "HELLO_GROUP_ONLY_CONTEXT"
            )
            else -> ignore("NO_MEMBERSHIP_CONTEXT")
        }
    }

    private fun sameGeneration(left: TopologyDigest, right: TopologyDigest): Boolean =
        left.rosterEpoch == right.rosterEpoch && left.memberHash == right.memberHash

    private fun observe(
        plane: MembershipAuthorityPlane,
        sessionFamilyId: String,
        reason: String
    ): Decision = Decision(
        observe = true,
        plane = plane,
        sessionFamilyId = sessionFamilyId,
        reason = reason
    )

    private fun ignore(reason: String): Decision = Decision(
        observe = false,
        plane = null,
        sessionFamilyId = null,
        reason = reason
    )
}

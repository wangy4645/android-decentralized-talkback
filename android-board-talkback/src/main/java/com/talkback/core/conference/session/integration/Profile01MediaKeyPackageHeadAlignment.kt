package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.Profile01MembershipGeneration
import com.talkback.core.conference.session.profile01.wire.Profile01WireMediaKeyPackage
import com.talkback.core.conference.session.profile01.wire.toHexLower

/**
 * Distinguishes recoverable MEDIA_KEY_PACKAGE / membership-head reordering from a true
 * context conflict. Does not relax [com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator].
 */
object Profile01MediaKeyPackageHeadAlignment {
    enum class Disposition {
        /** Package matches current head — ingest path should apply. */
        MATCH,

        /** No membership generation yet. */
        AWAIT_CONVERGENCE,

        /** Package is for a later (epoch, version, mediaKeyEpoch) than the current head. */
        HEAD_NOT_YET_REACHED,

        /** Head is already at/past this package, or same tuple with a different digest. */
        CONFLICT,

        /** Package conferenceId is not the bound session conference. */
        CROSS_SESSION,
    }

    fun classifyRejectedPackage(
        rejectReason: String,
        wire: Profile01WireMediaKeyPackage,
        current: Profile01MembershipGeneration?,
        sessionBoundConferenceIdHex: String?,
    ): Disposition {
        val packageConferenceIdHex = wire.conferenceId.toHexLower()
        val bound =
            classifyBoundConference(sessionBoundConferenceIdHex, packageConferenceIdHex)
        if (bound != null) return bound
        return when (rejectReason) {
            "MEMBERSHIP_NOT_CONVERGED" -> Disposition.AWAIT_CONVERGENCE
            "MEMBERSHIP_CONTEXT_MISMATCH" ->
                classifyTupleAgainstCurrent(wire, current, packageConferenceIdHex)
            else -> Disposition.CONFLICT
        }
    }

    fun classifyAgainstHead(
        wire: Profile01WireMediaKeyPackage,
        current: Profile01MembershipGeneration?,
        sessionBoundConferenceIdHex: String?,
    ): Disposition {
        val packageConferenceIdHex = wire.conferenceId.toHexLower()
        val bound =
            classifyBoundConference(sessionBoundConferenceIdHex, packageConferenceIdHex)
        if (bound != null) return bound
        return classifyTupleAgainstCurrent(wire, current, packageConferenceIdHex)
    }

    private fun classifyBoundConference(
        sessionBoundConferenceIdHex: String?,
        packageConferenceIdHex: String,
    ): Disposition? {
        if (sessionBoundConferenceIdHex != null &&
            sessionBoundConferenceIdHex != packageConferenceIdHex
        ) {
            return Disposition.CROSS_SESSION
        }
        return null
    }

    private fun classifyTupleAgainstCurrent(
        wire: Profile01WireMediaKeyPackage,
        current: Profile01MembershipGeneration?,
        packageConferenceIdHex: String,
    ): Disposition {
        if (current == null) {
            return Disposition.AWAIT_CONVERGENCE
        }
        if (current.conferenceId != packageConferenceIdHex) {
            return Disposition.CROSS_SESSION
        }
        val cmp =
            compareHeadTuple(
                conferenceEpoch = wire.conferenceEpoch,
                membershipVersion = wire.membershipVersion,
                mediaKeyEpoch = wire.mediaKeyEpoch,
                otherConferenceEpoch = current.conferenceEpoch,
                otherMembershipVersion = current.membershipVersion,
                otherMediaKeyEpoch = current.mediaKeyEpoch,
            )
        return when {
            cmp > 0 -> Disposition.HEAD_NOT_YET_REACHED
            cmp < 0 -> Disposition.CONFLICT
            else ->
                if (wire.membershipFactDigest.contentEquals(current.generationFactDigest)) {
                    Disposition.MATCH
                } else {
                    Disposition.CONFLICT
                }
        }
    }

    internal fun compareHeadTuple(
        conferenceEpoch: Long,
        membershipVersion: Long,
        mediaKeyEpoch: Long,
        otherConferenceEpoch: Long,
        otherMembershipVersion: Long,
        otherMediaKeyEpoch: Long,
    ): Int {
        val epochCmp = conferenceEpoch.compareTo(otherConferenceEpoch)
        if (epochCmp != 0) return epochCmp
        val versionCmp = membershipVersion.compareTo(otherMembershipVersion)
        if (versionCmp != 0) return versionCmp
        return mediaKeyEpoch.compareTo(otherMediaKeyEpoch)
    }
}

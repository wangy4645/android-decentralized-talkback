package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.Profile01MembershipGeneration
import com.talkback.core.conference.session.profile01.Profile01WireMembershipMember
import com.talkback.core.conference.session.profile01.wire.Profile01WireMediaKeyPackage
import org.junit.Assert.assertEquals
import org.junit.Test

class Profile01MediaKeyPackageHeadAlignmentTest {
    @Test
    fun mismatch_packageAheadOfHead_isRecoverable() {
        val disposition =
            Profile01MediaKeyPackageHeadAlignment.classifyRejectedPackage(
                rejectReason = "MEMBERSHIP_CONTEXT_MISMATCH",
                wire = packageAt(conferenceEpoch = 1, membershipVersion = 2, mediaKeyEpoch = 3),
                current = generation(conferenceEpoch = 1, membershipVersion = 1, mediaKeyEpoch = 2),
                sessionBoundConferenceIdHex = CONFERENCE,
            )
        assertEquals(Profile01MediaKeyPackageHeadAlignment.Disposition.HEAD_NOT_YET_REACHED, disposition)
    }

    @Test
    fun sameTupleMatchingDigest_isMatch() {
        val digest = ByteArray(32) { 1 }
        val disposition =
            Profile01MediaKeyPackageHeadAlignment.classifyAgainstHead(
                wire =
                    packageAt(conferenceEpoch = 1, membershipVersion = 2, mediaKeyEpoch = 3).let {
                        it.copy(membershipFactDigest = digest)
                    },
                current =
                    generation(conferenceEpoch = 1, membershipVersion = 2, mediaKeyEpoch = 3).copy(
                        generationFactDigest = digest,
                    ),
                sessionBoundConferenceIdHex = CONFERENCE,
            )
        assertEquals(Profile01MediaKeyPackageHeadAlignment.Disposition.MATCH, disposition)
    }

    @Test
    fun mismatch_sameTupleDifferentDigest_isConflict() {
        val disposition =
            Profile01MediaKeyPackageHeadAlignment.classifyRejectedPackage(
                rejectReason = "MEMBERSHIP_CONTEXT_MISMATCH",
                wire = packageAt(conferenceEpoch = 1, membershipVersion = 2, mediaKeyEpoch = 3),
                current = generation(conferenceEpoch = 1, membershipVersion = 2, mediaKeyEpoch = 3),
                sessionBoundConferenceIdHex = CONFERENCE,
            )
        assertEquals(Profile01MediaKeyPackageHeadAlignment.Disposition.CONFLICT, disposition)
    }

    @Test
    fun mismatch_packageBehindHead_isConflict() {
        val disposition =
            Profile01MediaKeyPackageHeadAlignment.classifyRejectedPackage(
                rejectReason = "MEMBERSHIP_CONTEXT_MISMATCH",
                wire = packageAt(conferenceEpoch = 1, membershipVersion = 1, mediaKeyEpoch = 2),
                current = generation(conferenceEpoch = 1, membershipVersion = 2, mediaKeyEpoch = 3),
                sessionBoundConferenceIdHex = CONFERENCE,
            )
        assertEquals(Profile01MediaKeyPackageHeadAlignment.Disposition.CONFLICT, disposition)
    }

    @Test
    fun mismatch_otherConferenceOnBoundSession_isCrossSession() {
        val disposition =
            Profile01MediaKeyPackageHeadAlignment.classifyRejectedPackage(
                rejectReason = "MEMBERSHIP_CONTEXT_MISMATCH",
                wire = packageAt(conferenceEpoch = 1, membershipVersion = 2, mediaKeyEpoch = 3),
                current = generation(conferenceEpoch = 1, membershipVersion = 1, mediaKeyEpoch = 2),
                sessionBoundConferenceIdHex = "bb".repeat(16),
            )
        assertEquals(Profile01MediaKeyPackageHeadAlignment.Disposition.CROSS_SESSION, disposition)
    }

    @Test
    fun membershipNotConverged_awaitsHead() {
        val disposition =
            Profile01MediaKeyPackageHeadAlignment.classifyRejectedPackage(
                rejectReason = "MEMBERSHIP_NOT_CONVERGED",
                wire = packageAt(conferenceEpoch = 1, membershipVersion = 2, mediaKeyEpoch = 3),
                current = null,
                sessionBoundConferenceIdHex = CONFERENCE,
            )
        assertEquals(Profile01MediaKeyPackageHeadAlignment.Disposition.AWAIT_CONVERGENCE, disposition)
    }

    @Test
    fun decryptFailure_isTerminalConflict() {
        val disposition =
            Profile01MediaKeyPackageHeadAlignment.classifyRejectedPackage(
                rejectReason = "GCM_AUTH_FAIL",
                wire = packageAt(conferenceEpoch = 1, membershipVersion = 2, mediaKeyEpoch = 3),
                current = generation(conferenceEpoch = 1, membershipVersion = 1, mediaKeyEpoch = 2),
                sessionBoundConferenceIdHex = CONFERENCE,
            )
        assertEquals(Profile01MediaKeyPackageHeadAlignment.Disposition.CONFLICT, disposition)
    }

    private fun generation(
        conferenceEpoch: Long,
        membershipVersion: Long,
        mediaKeyEpoch: Long,
    ) = Profile01MembershipGeneration(
        conferenceId = CONFERENCE,
        conferenceEpoch = conferenceEpoch,
        membershipVersion = membershipVersion,
        mediaKeyEpoch = mediaKeyEpoch,
        generationFactDigest = ByteArray(32) { 1 },
        creationFactDigest = ByteArray(32) { 2 },
        members = listOf(Profile01WireMembershipMember("M01", byteArrayOf(1))),
        mediaKeyCommitment = ByteArray(32) { 3 },
    )

    private fun packageAt(
        conferenceEpoch: Long,
        membershipVersion: Long,
        mediaKeyEpoch: Long,
    ) = Profile01WireMediaKeyPackage(
        signedFactBytes = byteArrayOf(1),
        factDigest = ByteArray(32) { 9 },
        fullCanonicalBytes = byteArrayOf(1),
        conferenceId = CONFERENCE_BYTES,
        conferenceEpoch = conferenceEpoch,
        ownerModuleId = "M01",
        membershipVersion = membershipVersion,
        mediaKeyEpoch = mediaKeyEpoch,
        membershipFactDigest = ByteArray(32) { 8 },
        mediaKeyCommitment = ByteArray(32) { 7 },
        recipientModuleId = "M02",
        packageIdentity = ByteArray(16),
        wrappedPek = ByteArray(8),
        gcmNonce = ByteArray(12),
        ciphertext = ByteArray(8),
        gcmTag = ByteArray(16),
        recipientKeyVersion = 1L,
    )

    companion object {
        private const val CONFERENCE = "aa111111111111111111111111111111"
        private val CONFERENCE_BYTES =
            ByteArray(16) { index ->
                CONFERENCE.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
    }
}

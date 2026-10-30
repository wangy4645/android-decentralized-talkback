package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.Profile01MembershipGeneration

/**
 * Single committed membership snapshot for one conference. Head, supplement,
 * SOURCE, catalog/wiring, and member READY must derive from this object.
 */
data class ConferenceMediaCommit(
    val sessionId: String,
    val conferenceIdHex: String,
    val conferenceEpoch: Long,
    val membershipVersion: Long,
    val mediaKeyEpoch: Long,
    val membershipFactDigestHex: String,
    val members: List<String>,
) {
    val headDigest: String =
        Profile01HeadDigest.compute(
            conferenceEpoch = conferenceEpoch,
            membershipVersion = membershipVersion,
            mediaKeyEpoch = mediaKeyEpoch,
            membershipFactDigestHex = membershipFactDigestHex,
        )

    fun memberSet(): Set<String> = members.toSet()

    fun toHeadSnapshot(): Profile01AuthoritativeHeadSnapshot =
        Profile01AuthoritativeHeadSnapshot(
            conferenceIdHex = conferenceIdHex,
            sessionId = sessionId,
            conferenceEpoch = conferenceEpoch,
            membershipVersion = membershipVersion,
            mediaKeyEpoch = mediaKeyEpoch,
            membershipFactDigestHex = membershipFactDigestHex,
        )

    companion object {
        fun from(
            generation: Profile01MembershipGeneration,
            sessionId: String,
        ): ConferenceMediaCommit =
            ConferenceMediaCommit(
                sessionId = sessionId,
                conferenceIdHex = generation.conferenceId,
                conferenceEpoch = generation.conferenceEpoch,
                membershipVersion = generation.membershipVersion,
                mediaKeyEpoch = generation.mediaKeyEpoch,
                membershipFactDigestHex = Profile01HeadDigest.hexAscii(generation.generationFactDigest),
                members = generation.members.map { it.moduleId }.distinct(),
            )
    }
}

enum class Profile01CommitResyncReason {
    LATE_JOIN,
    STALE_HEAD,
    REJOIN,
}

object Profile01CommitReadiness {
    fun evaluate(
        commit: ConferenceMediaCommit,
        localModuleId: String,
        localHeadDigest: String?,
        currentHeadSupplementInstalled: Boolean,
        expectedModuleIds: Set<String>,
        boundCatalogModuleIds: Set<String>,
    ): Profile01CommitReadinessResult {
        val missing = mutableListOf<String>()
        val expected = commit.memberSet()
        if (localHeadDigest != commit.headDigest) {
            missing += "COMMIT_HEAD_MISMATCH"
        }
        if (!currentHeadSupplementInstalled) {
            missing += "COMMIT_SUPPLEMENT_NOT_INSTALLED"
        }
        if (expectedModuleIds != expected) {
            missing += "CATALOG_EXPECTED_NOT_AT_COMMIT"
        }
        val remoteExpected = expected.filter { it != localModuleId }.toSet()
        if (!boundCatalogModuleIds.containsAll(remoteExpected)) {
            missing += "CATALOG_WIRING_NOT_AT_COMMIT"
        }
        return Profile01CommitReadinessResult(
            ready = missing.isEmpty(),
            missing = missing,
            headDigest = commit.headDigest,
            expectedMembers = expected,
        )
    }
}

data class Profile01CommitReadinessResult(
    val ready: Boolean,
    val missing: List<String>,
    val headDigest: String,
    val expectedMembers: Set<String>,
)

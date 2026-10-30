package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.session.integration.cutover.MulticastAudibleCutoverReadiness
import com.talkback.core.conference.session.profile01.Profile01MembershipGeneration
import com.talkback.core.conference.session.profile01.Profile01WireMembershipMember
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Profile01CommitConvergenceDeskTest {
    private val sessionId = "commit-convergence"
    private lateinit var wiring: ConferenceSessionMediaWiring

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        assertTrue(
            ConferenceSessionMediaBridge.startSession(
                SessionMediaWiringHarness.sessionFact(sessionId, generation = 1L, mediaKeyEpoch = 2L),
            ),
        )
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
        MeetingProductMediaShadow.enabled = false
    }

    @Test
    fun commit_headDigestBindsEpochsAndMembershipSet() {
        val commit = commitOf(members = listOf("M01", "M02", "M04", "M05"), version = 3L, epoch = 4L)
        assertEquals("2|3|4|ab", commit.headDigest)
        assertEquals(setOf("M01", "M02", "M04", "M05"), commit.memberSet())
    }

    @Test
    fun catalogExpected_followsCommitMembers_notInstalledSources() {
        val commit = commitOf(members = listOf("M01", "M02", "M04", "M05"))
        assertTrue(
            wiring.expectPresentMembers(sessionId, commit.members, "MEMBERSHIP_COMMIT"),
        )
        assertTrue(wiring.installMember(sessionId, SessionMediaWiringHarness.memberBinding("M01", mediaKeyEpoch = 2L)))
        val snap = wiring.runtimeSnapshot(sessionId)!!
        assertEquals(1, snap.catalogEntries)
        assertEquals(4, snap.expectedMembers)
        assertEquals(commit.memberSet(), wiring.expectedCatalog(sessionId)!!.moduleIds())
    }

    @Test
    fun membershipShrink_prunesWiringOutsideCommit() {
        assertTrue(
            wiring.expectPresentMembers(sessionId, listOf("M01", "M02", "M04"), "MEMBERSHIP_COMMIT"),
        )
        assertTrue(wiring.installMember(sessionId, SessionMediaWiringHarness.memberBinding("M01", mediaKeyEpoch = 2L)))
        assertTrue(wiring.installMember(sessionId, SessionMediaWiringHarness.memberBinding("M04", mediaKeyEpoch = 2L)))
        wiring.pruneCatalogOutsideCommit(sessionId, setOf("M01", "M02"))
        assertTrue(
            wiring.expectPresentMembers(sessionId, listOf("M01", "M02"), "MEMBERSHIP_COMMIT"),
        )
        val snap = wiring.runtimeSnapshot(sessionId)!!
        assertEquals(1, snap.catalogEntries)
        assertEquals(2, snap.expectedMembers)
        assertEquals(setOf("M01", "M02"), wiring.expectedCatalog(sessionId)!!.moduleIds())
    }

    @Test
    fun commitReadiness_notReadyUntilHeadSupplementAndCatalogMatch() {
        val commit = commitOf(members = listOf("M01", "M05"))
        val missingHead =
            Profile01CommitReadiness.evaluate(
                commit = commit,
                localModuleId = "M01",
                localHeadDigest = "stale",
                currentHeadSupplementInstalled = true,
                expectedModuleIds = commit.memberSet(),
                boundCatalogModuleIds = setOf("M05"),
            )
        assertFalse(missingHead.ready)
        assertTrue(missingHead.missing.contains("COMMIT_HEAD_MISMATCH"))

        val missingCatalog =
            Profile01CommitReadiness.evaluate(
                commit = commit,
                localModuleId = "M01",
                localHeadDigest = commit.headDigest,
                currentHeadSupplementInstalled = true,
                expectedModuleIds = setOf("M01"),
                boundCatalogModuleIds = emptySet(),
            )
        assertFalse(missingCatalog.ready)
        assertTrue(missingCatalog.missing.contains("CATALOG_EXPECTED_NOT_AT_COMMIT"))
        assertTrue(missingCatalog.missing.contains("CATALOG_WIRING_NOT_AT_COMMIT"))

        val ready =
            Profile01CommitReadiness.evaluate(
                commit = commit,
                localModuleId = "M01",
                localHeadDigest = commit.headDigest,
                currentHeadSupplementInstalled = true,
                expectedModuleIds = commit.memberSet(),
                boundCatalogModuleIds = setOf("M05"),
            )
        assertTrue(ready.ready)
        assertTrue(ready.missing.isEmpty())
    }

    @Test
    fun cutover_withCommit_requiresCatalogAtCommit() {
        val commit = commitOf(members = listOf("M01", "M05"))
        val evaluated =
            MulticastAudibleCutoverReadiness.evaluate(
                sessionId = sessionId,
                localModuleId = "M01",
                commit = commit,
                localHeadDigest = commit.headDigest,
                currentHeadSupplementInstalled = true,
            )
        assertFalse(evaluated.ready)
        assertTrue(evaluated.missing.contains("CATALOG_EXPECTED_NOT_AT_COMMIT"))
    }

    private fun commitOf(
        members: List<String>,
        version: Long = 3L,
        epoch: Long = 4L,
    ): ConferenceMediaCommit =
        ConferenceMediaCommit.from(
            generation =
                Profile01MembershipGeneration(
                    conferenceId = "cc",
                    conferenceEpoch = 2L,
                    membershipVersion = version,
                    mediaKeyEpoch = epoch,
                    generationFactDigest = byteArrayOf(0xab.toByte()),
                    creationFactDigest = byteArrayOf(0x01),
                    members =
                        members.map { id ->
                            Profile01WireMembershipMember(id, ByteArray(16))
                        },
                    mediaKeyCommitment = ByteArray(32),
                ),
            sessionId = sessionId,
        )
}

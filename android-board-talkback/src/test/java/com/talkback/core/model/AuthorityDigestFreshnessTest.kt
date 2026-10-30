package com.talkback.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorityDigestFreshnessTest {

    @Test
    fun notStale_whenPreviousNull() {
        val next = TopologyDigest(rosterEpoch = 1L, anchorEpoch = 1L, memberHash = 11)
        assertFalse(AuthorityDigestFreshness.isStaleReplacement(null, next))
    }

    @Test
    fun notStale_whenSameEpochAndHash() {
        val digest = TopologyDigest(rosterEpoch = 1L, anchorEpoch = 1L, memberHash = 11)
        assertFalse(AuthorityDigestFreshness.isStaleReplacement(digest, digest.copy()))
    }

    @Test
    fun stale_whenEpochDiffers() {
        val previous = TopologyDigest(rosterEpoch = 3L, anchorEpoch = 1L, memberHash = 99)
        val next = TopologyDigest(rosterEpoch = 1L, anchorEpoch = 1L, memberHash = 11)
        assertTrue(AuthorityDigestFreshness.isStaleReplacement(previous, next))
    }

    @Test
    fun stale_whenHashDiffers() {
        val previous = TopologyDigest(rosterEpoch = 1L, anchorEpoch = 1L, memberHash = 99)
        val next = TopologyDigest(rosterEpoch = 1L, anchorEpoch = 1L, memberHash = 11)
        assertTrue(AuthorityDigestFreshness.isStaleReplacement(previous, next))
    }

    @Test
    fun notStale_whenPlanesDiffer() {
        val previous = TopologyDigest(rosterEpoch = 5L, anchorEpoch = 100L, memberHash = 1783685497)
        val next = TopologyDigest(rosterEpoch = 1L, anchorEpoch = 0L, memberHash = -289621531)
        assertFalse(
            AuthorityDigestFreshness.isStaleReplacement(
                previous,
                next,
                MembershipAuthorityPlane.CONFERENCE,
                MembershipAuthorityPlane.GROUP
            )
        )
    }

    @Test
    fun notStale_whenSessionFamilyDiffers() {
        val previous = TopologyDigest(rosterEpoch = 5L, anchorEpoch = 100L, memberHash = 1)
        val next = TopologyDigest(rosterEpoch = 1L, anchorEpoch = 100L, memberHash = 2)
        assertFalse(
            AuthorityDigestFreshness.isStaleReplacement(
                previous,
                next,
                MembershipAuthorityPlane.CONFERENCE,
                MembershipAuthorityPlane.CONFERENCE,
                previousSessionFamilyId = "cnf-old",
                nextSessionFamilyId = "cnf-new"
            )
        )
    }

    @Test
    fun stale_whenSamePlaneAndFamilyEpochDiffers() {
        val previous = TopologyDigest(rosterEpoch = 5L, anchorEpoch = 100L, memberHash = 1)
        val next = TopologyDigest(rosterEpoch = 6L, anchorEpoch = 100L, memberHash = 1)
        assertTrue(
            AuthorityDigestFreshness.isStaleReplacement(
                previous,
                next,
                MembershipAuthorityPlane.CONFERENCE,
                MembershipAuthorityPlane.CONFERENCE,
                previousSessionFamilyId = "cnf-1",
                nextSessionFamilyId = "cnf-1"
            )
        )
    }

    @Test
    fun formatInvalidated_includesPreviousAndNewEpoch() {
        val previous = AuthorityDigestObservation(
            channelId = "CH-01",
            authorityId = "M01",
            digest = TopologyDigest(rosterEpoch = 3L, anchorEpoch = 1L, memberHash = -925203082),
            observedAtMs = 1L,
            source = AuthorityDigestSource.HELLO,
            membershipContext = "GROUP",
            plane = MembershipAuthorityPlane.GROUP,
            sessionFamilyId = "grp:CH-01"
        )
        val next = AuthorityDigestObservation(
            channelId = "CH-01",
            authorityId = "M01",
            digest = TopologyDigest(rosterEpoch = 1L, anchorEpoch = 1L, memberHash = -528664596),
            observedAtMs = 2L,
            source = AuthorityDigestSource.MEMBERSHIP_SNAPSHOT_APPLY,
            membershipContext = "CONFERENCE",
            plane = MembershipAuthorityPlane.CONFERENCE,
            sessionFamilyId = "cnf-1"
        )
        val line = AuthorityDigestFreshness.formatInvalidated("CH-01", previous, next)
        assertTrue(line.startsWith("STALE_AUTHORITY_DIGEST_INVALIDATED"))
        assertTrue(line.contains("previousEpoch=3"))
        assertTrue(line.contains("newEpoch=1"))
        assertTrue(line.contains("previousSource=HELLO"))
        assertTrue(line.contains("newSource=MEMBERSHIP_SNAPSHOT_APPLY"))
        assertEquals(true, line.contains("newContext=CONFERENCE"))
        assertTrue(line.contains("previousPlane=GROUP"))
        assertTrue(line.contains("newPlane=CONFERENCE"))
    }
}

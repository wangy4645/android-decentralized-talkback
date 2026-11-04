package com.talkback.core.conference.session.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRetrySameHeadCoalescerTest {
    private val coalescer = SourceRetrySameHeadCoalescer()
    private val headA = SourceRetrySameHeadCoalescer.HeadKey(
        sessionId = "s1",
        localModuleId = "M02",
        mediaKeyEpoch = 4L,
        membershipVersion = 3L,
        headDigestHex = "aaa",
    )
    private val headB = headA.copy(membershipVersion = 4L, mediaKeyEpoch = 5L, headDigestHex = "bbb")

    @Test
    fun sameHeadGapRetriesCoalesceAfterFirstSettled() {
        assertFalse(coalescer.shouldCoalesce(headA))
        coalescer.begin(headA)
        assertFalse(
            coalescer.complete(
                headA,
                SourceOriginBuildOutcome.ORIGIN_SOURCE_GAP,
                grantFollowUp = true,
            ),
        )
        assertTrue(coalescer.shouldCoalesce(headA))
        assertTrue(coalescer.shouldCoalesce(headA))
        assertEquals(2L, coalescer.coalescedCount(headA))
        assertTrue(coalescer.shouldLogCoalesced(headA))
        assertFalse(coalescer.shouldLogCoalesced(headA))
        assertTrue(coalescer.settledGap(headA))
    }

    @Test
    fun newMembershipHeadIsNotCoalesced() {
        coalescer.begin(headA)
        coalescer.complete(headA, SourceOriginBuildOutcome.ORIGIN_SOURCE_GAP, grantFollowUp = true)
        assertTrue(coalescer.shouldCoalesce(headA))
        assertFalse(coalescer.shouldCoalesce(headB))
        coalescer.begin(headB)
        coalescer.complete(headB, SourceOriginBuildOutcome.ORIGIN_SOURCE_GAP, grantFollowUp = true)
        assertTrue(coalescer.shouldCoalesce(headB))
    }

    @Test
    fun builtOutcomeDoesNotCoalesceLaterAttempts() {
        coalescer.begin(headA)
        coalescer.complete(headA, SourceOriginBuildOutcome.BUILT, grantFollowUp = true)
        assertFalse(coalescer.shouldCoalesce(headA))
    }

    @Test
    fun inFlightRedundantTriggerGrantsOneFollowUpThenDrops() {
        coalescer.begin(headA)
        assertTrue(coalescer.shouldCoalesce(headA))
        assertTrue(coalescer.shouldCoalesce(headA))
        val follow = coalescer.complete(headA, SourceOriginBuildOutcome.ORIGIN_SOURCE_GAP, grantFollowUp = true)
        assertTrue(follow)
        coalescer.begin(headA)
        assertTrue(coalescer.shouldCoalesce(headA))
        val secondFollow = coalescer.complete(headA, SourceOriginBuildOutcome.ORIGIN_SOURCE_GAP, grantFollowUp = false)
        assertFalse(secondFollow)
        assertTrue(coalescer.shouldCoalesce(headA))
    }

    @Test
    fun clearSessionDropsSettledGap() {
        coalescer.begin(headA)
        coalescer.complete(headA, SourceOriginBuildOutcome.ORIGIN_SOURCE_GAP, grantFollowUp = true)
        coalescer.clearSession("s1")
        assertFalse(coalescer.shouldCoalesce(headA))
    }
}

package com.talkback.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R29-F (#82): rejoin eligibility is a membership-lifecycle table only.
 * Media / recovery facts are intentionally absent from this surface.
 */
class ConferenceRejoinEligibilityTest {

    @Test
    fun invited_left_pruned_areEligible() {
        assertTrue(ConferenceRejoinEligibility.isEligible(ConferenceMembershipLifecycle.INVITED))
        assertTrue(ConferenceRejoinEligibility.isEligible(ConferenceMembershipLifecycle.LEFT))
        assertTrue(ConferenceRejoinEligibility.isEligible(ConferenceMembershipLifecycle.PRUNED))
    }

    @Test
    fun joined_neverEligible() {
        assertFalse(ConferenceRejoinEligibility.isEligible(ConferenceMembershipLifecycle.JOINED))
    }

    @Test
    fun rejoinRequired_reservedEligible_notExercisedByResolver() {
        // Predicate admits REJOIN_REQUIRED for future host migration; #82 must not produce it.
        assertTrue(ConferenceRejoinEligibility.isEligible(ConferenceMembershipLifecycle.REJOIN_REQUIRED))
    }
}

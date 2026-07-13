package com.talkback.core.session

/**
 * Conference membership lifecycle facts used for rejoin eligibility (R29-F / #82).
 *
 * Distinct from invite/media enums: this is the committed membership plane only.
 * [REJOIN_REQUIRED] is reserved for future host migration and is not produced in #82.
 */
enum class ConferenceMembershipLifecycle {
    INVITED,
    JOINED,
    LEFT,
    PRUNED,
    REJOIN_REQUIRED
}

/**
 * R29-F: Conference rejoin eligibility MUST derive from membership lifecycle only.
 * Recovery / media facts MUST NOT appear here.
 */
object ConferenceRejoinEligibility {
    fun isEligible(lifecycle: ConferenceMembershipLifecycle): Boolean =
        when (lifecycle) {
            ConferenceMembershipLifecycle.INVITED,
            ConferenceMembershipLifecycle.LEFT,
            ConferenceMembershipLifecycle.PRUNED,
            ConferenceMembershipLifecycle.REJOIN_REQUIRED -> true
            ConferenceMembershipLifecycle.JOINED -> false
        }
}

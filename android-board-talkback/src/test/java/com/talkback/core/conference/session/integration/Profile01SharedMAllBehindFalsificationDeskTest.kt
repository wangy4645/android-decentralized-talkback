package com.talkback.core.conference.session.integration

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Track 1 — one desk falsification. Field-isomorphic to Q3A 16:27:31.
 *
 * Question: with monotonic shared M, no jump to target, and no fabricated media,
 * does a legal forward transition exist?
 *
 * This test must stay RED against any claim that candidate selection alone
 * can advance the clock. It does not authorize a production fallback.
 */
class Profile01SharedMAllBehindFalsificationDeskTest {
    @Test
    fun q3aFieldSnapshot_sharedMForwardTransition_isStructurallyUnsatisfiable() {
        val legal =
            legalForwardMixSlots(
                lastResolved = LAST_RESOLVED,
                target = TARGET,
                latestTrusted =
                    mapOf(
                        "M01" to M01_LATEST,
                        "M03" to M03_LATEST,
                        "M04" to M04_LATEST,
                    ),
            )
        assertTrue(
            "STRUCTURALLY UNSATISFIABLE: legal=$legal " +
                "(max latest ${maxOf(M01_LATEST, M03_LATEST, M04_LATEST)} < lastResolved $LAST_RESOLVED)",
            legal.isEmpty(),
        )
    }

    /**
     * Next M is legal only when it is strictly after [lastResolved], at or before [target],
     * and some selected source already holds trusted media at that mix slot.
     * No timeout, hysteresis, re-anchor, clock jump, or cursor catch-up.
     */
    private fun legalForwardMixSlots(
        lastResolved: Long,
        target: Long,
        latestTrusted: Map<String, Long>,
    ): List<Long> {
        if (target <= lastResolved) return emptyList()
        return (lastResolved + 1..target).filter { next ->
            latestTrusted.values.any { latest -> latest >= next }
        }
    }

    private companion object {
        const val LAST_RESOLVED = 21064L
        const val TARGET = 21121L
        const val M01_LATEST = 20938L
        const val M03_LATEST = 20849L
        const val M04_LATEST = 20637L
    }
}

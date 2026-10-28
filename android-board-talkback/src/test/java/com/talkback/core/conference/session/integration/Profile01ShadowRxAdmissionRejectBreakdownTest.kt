package com.talkback.core.conference.session.integration

import org.junit.Assert.assertEquals
import org.junit.Test

class Profile01ShadowRxAdmissionRejectBreakdownTest {
    @Test
    fun rejectBucketKey_usesFrozenClassAndReason() {
        assertEquals(
            "UNKNOWN_SSRC|no catalog binding",
            Profile01ShadowRuntimeObservability.rejectBucketKey(
                frozenClass = "UNKNOWN_SSRC",
                reason = "no catalog binding",
            ),
        )
        assertEquals(
            "NONE|SESSION_INGRESS_BLOCKED",
            Profile01ShadowRuntimeObservability.rejectBucketKey(
                frozenClass = null,
                reason = "SESSION_INGRESS_BLOCKED",
            ),
        )
    }

    @Test
    fun formatRejectBreakdown_sortsByCountDescThenKey() {
        val formatted =
            Profile01ShadowRuntimeObservability.formatRejectBreakdown(
                mapOf(
                    "UNKNOWN_SSRC|no catalog binding" to 47,
                    "REPLAY_REJECTED|duplicate packet index" to 3,
                    "NONE|SESSION_INGRESS_BLOCKED" to 50,
                ),
            )
        assertEquals(
            "NONE|SESSION_INGRESS_BLOCKED=50,UNKNOWN_SSRC|no catalog binding=47,REPLAY_REJECTED|duplicate packet index=3",
            formatted,
        )
    }
}

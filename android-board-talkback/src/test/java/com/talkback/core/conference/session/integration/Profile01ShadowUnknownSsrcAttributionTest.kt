package com.talkback.core.conference.session.integration

import org.junit.Assert.assertEquals
import org.junit.Test

class Profile01ShadowUnknownSsrcAttributionTest {
    @Test
    fun unknownSsrcAttributionBucketKey_includesSsrcAndEndpoint() {
        assertEquals(
            "1234567890|192.168.1.10:5004",
            Profile01ShadowRuntimeObservability.unknownSsrcAttributionBucketKey(
                ssrc = 1234567890,
                sourceEndpoint = "192.168.1.10:5004",
            ),
        )
        assertEquals(
            "42|UNKNOWN_ENDPOINT",
            Profile01ShadowRuntimeObservability.unknownSsrcAttributionBucketKey(
                ssrc = 42,
                sourceEndpoint = null,
            ),
        )
    }

    @Test
    fun formatUnknownSsrcAttributionSummary_sortsByRejectCountDesc() {
        val formatted =
            Profile01ShadowRuntimeObservability.formatUnknownSsrcAttributionSummary(
                listOf(
                    Profile01ShadowRuntimeObservability.UnknownSsrcAttributionBucket(
                        ssrc = 111,
                        sourceEndpoint = "10.0.0.2:5004",
                        rejectCount = 12,
                        mediaKeyEpoch = 3L,
                        conferenceEpoch = 2L,
                        membershipVersion = 5L,
                        catalogKnownSsrcs = "M01:100,M02:200",
                    ),
                    Profile01ShadowRuntimeObservability.UnknownSsrcAttributionBucket(
                        ssrc = 222,
                        sourceEndpoint = "10.0.0.3:5004",
                        rejectCount = 50,
                        mediaKeyEpoch = 3L,
                        conferenceEpoch = 2L,
                        membershipVersion = 5L,
                        catalogKnownSsrcs = "M01:100,M02:200",
                    ),
                ),
            )
        assertEquals(
            "ssrc=222|src=10.0.0.3:5004|reject=50|mke=3|confEpoch=2|memV=5|catalog=M01:100,M02:200;" +
                "ssrc=111|src=10.0.0.2:5004|reject=12|mke=3|confEpoch=2|memV=5|catalog=M01:100,M02:200",
            formatted,
        )
    }
}

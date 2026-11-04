package com.talkback.core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CoordinatorTaskAttributionTest {
    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        lines.clear()
        CoordinatorTaskAttribution.resetForTest { lines += it }
    }

    @After
    fun tearDown() {
        CoordinatorTaskAttribution.resetForTest(null)
    }

    @Test
    fun scopedSetsKindForNestedPostCapture() {
        var captured: CoordinatorTaskAttribution.Kind? = null
        val result =
            CoordinatorTaskAttribution.scoped(
                CoordinatorTaskAttribution.Kind.LATE_PEER,
                "path=PAIRWISE_REINVITE peer=M02",
            ) {
                captured = CoordinatorTaskAttribution.current()?.kind
                7
            }
        assertEquals(7, result)
        assertEquals(CoordinatorTaskAttribution.Kind.LATE_PEER, captured)
        assertEquals(null, CoordinatorTaskAttribution.current())
    }

    @Test
    fun nestedScopeRestoresParent() {
        CoordinatorTaskAttribution.scoped(CoordinatorTaskAttribution.Kind.OTHER, "parent") {
            assertEquals(CoordinatorTaskAttribution.Kind.OTHER, CoordinatorTaskAttribution.current()?.kind)
            CoordinatorTaskAttribution.scoped(CoordinatorTaskAttribution.Kind.OFFER, "child") {
                assertEquals(CoordinatorTaskAttribution.Kind.OFFER, CoordinatorTaskAttribution.current()?.kind)
            }
            assertEquals(CoordinatorTaskAttribution.Kind.OTHER, CoordinatorTaskAttribution.current()?.kind)
        }
        assertEquals(null, CoordinatorTaskAttribution.current())
    }

    @Test
    fun taskSeqMonotonic() {
        val a = CoordinatorTaskAttribution.nextSeq()
        val b = CoordinatorTaskAttribution.nextSeq()
        assertTrue(b > a)
        assertFalse(a == b)
    }

    @Test
    fun postRunEndAreSeparateFromSpan() {
        CoordinatorTaskAttribution.scoped(CoordinatorTaskAttribution.Kind.OFFER, "path=PEER_OFFER peer=M01") {
            val seq = CoordinatorTaskAttribution.nextSeq()
            CoordinatorTaskAttribution.post(seq, CoordinatorTaskAttribution.Kind.OFFER, "path=PEER_OFFER peer=M01", "ASYNC", 10)
            CoordinatorTaskAttribution.run(seq, CoordinatorTaskAttribution.Kind.OFFER, "path=PEER_OFFER peer=M01", "ASYNC", 50, 8)
            CoordinatorTaskAttribution.end(seq, CoordinatorTaskAttribution.Kind.OFFER, "path=PEER_OFFER peer=M01", "ASYNC", 50, 3, 7)
        }
        assertEquals(1, lines.count { it.startsWith("COORD_SPAN kind=OFFER") })
        assertEquals(1, lines.count { it.contains("stage=POST") })
        assertEquals(1, lines.count { it.contains("stage=RUN") && it.contains("waitMs=50") })
        assertEquals(1, lines.count { it.contains("stage=END") && it.contains("execMs=3") })
        assertTrue(lines.none { it.contains("kind=SHADOW") })
        assertTrue(lines.any { it.contains("stage=POST") && it.contains("thread=") })
    }

    @Test
    fun shadowSignedFactIdentityKeepsDigestAndHeadDistinct() {
        val line =
            CoordinatorTaskAttribution.shadowSignedFactIdentity(
                sessionId = "s",
                peerModuleId = "M02",
                factType = 11,
                factDigestHex = "aaa",
                conferenceEpoch = 4,
                membershipVersion = 3,
                mediaKeyEpoch = 4,
                headDigestHex = "bbb",
            )
        assertTrue(line.contains("factDigest=aaa"))
        assertTrue(line.contains("headDigest=bbb"))
        assertTrue(line.contains("membershipVersion=3"))
        assertTrue(line.contains("mediaKeyEpoch=4"))
        assertTrue(line.contains("factType=11"))
    }
}

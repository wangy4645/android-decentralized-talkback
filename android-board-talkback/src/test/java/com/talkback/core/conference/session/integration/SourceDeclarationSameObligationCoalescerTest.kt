package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.Profile01DirectedWireFixtures
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.conference.session.profile01.wire.Profile01WireConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDeclarationSameObligationCoalescerTest {
    private val coalescer = SourceDeclarationSameObligationCoalescer()
    private val keyA = SourceDeclarationSameObligationCoalescer.Key(
        sessionId = "s1",
        peerModuleId = "M01",
        membershipVersion = 3L,
        mediaKeyEpoch = 4L,
        factDigestHex = "aaa",
    )
    private val newDigest = keyA.copy(factDigestHex = "bbb")
    private val newPeer = keyA.copy(peerModuleId = "M02")
    private val newHead = keyA.copy(membershipVersion = 4L, mediaKeyEpoch = 5L, factDigestHex = "ccc")

    @Test
    fun queuedOrInFlightSameIdentitySkipsUntilRelease() {
        assertTrue(coalescer.tryOccupy(keyA))
        assertFalse(coalescer.tryOccupy(keyA))
        assertFalse(coalescer.tryOccupy(keyA))
        assertEquals(2L, coalescer.coalescedCount(keyA))
        assertTrue(coalescer.shouldLogCoalesced(keyA))
        assertFalse(coalescer.shouldLogCoalesced(keyA))
        assertEquals(2L, coalescer.release(keyA))
        assertFalse(coalescer.isOccupied(keyA))
        assertTrue(coalescer.tryOccupy(keyA))
        assertEquals(0L, coalescer.release(keyA))
    }

    @Test
    fun differentDigestPeerOrHeadDoNotShareOccupancy() {
        assertTrue(coalescer.tryOccupy(keyA))
        assertTrue(coalescer.tryOccupy(newDigest))
        assertTrue(coalescer.tryOccupy(newPeer))
        assertTrue(coalescer.tryOccupy(newHead))
        assertFalse(coalescer.tryOccupy(keyA))
        coalescer.release(keyA)
        coalescer.release(newDigest)
        coalescer.release(newPeer)
        coalescer.release(newHead)
    }

    @Test
    fun clearSessionDropsOccupancy() {
        assertTrue(coalescer.tryOccupy(keyA))
        coalescer.clearSession("s1")
        assertFalse(coalescer.isOccupied(keyA))
        assertTrue(coalescer.tryOccupy(keyA))
        coalescer.release(keyA)
    }

    @Test
    fun onlySourceDeclarationFactsFormAKey() {
        val source =
            Profile01WireCborDecoder.readObserveIdentity(
                Profile01DirectedWireFixtures.sourceDeclarationSignedFactBytes,
            )
        assertNotNull(source)
        assertEquals(Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION, source!!.factType)
        val sourceKey =
            SourceDeclarationSameObligationCoalescer.keyOrNull("s1", "M01", source)
        assertNotNull(sourceKey)
        assertEquals(source.factDigestHex, sourceKey!!.factDigestHex)
        assertEquals(source.membershipVersion, sourceKey.membershipVersion)
        assertEquals(source.mediaKeyEpoch, sourceKey.mediaKeyEpoch)

        assertNull(
            SourceDeclarationSameObligationCoalescer.keyOrNull(
                "s1",
                "M01",
                Profile01WireCborDecoder.readObserveIdentity(
                    Profile01DirectedWireFixtures.creationSignedFactBytes,
                ),
            ),
        )
        assertNull(
            SourceDeclarationSameObligationCoalescer.keyOrNull(
                "s1",
                "M01",
                Profile01WireCborDecoder.readObserveIdentity(
                    Profile01DirectedWireFixtures.membershipSignedFactBytes,
                ),
            ),
        )
        assertNull(
            SourceDeclarationSameObligationCoalescer.keyOrNull(
                "s1",
                "M01",
                Profile01WireCborDecoder.ObserveIdentity(
                    conferenceIdHex = "cid",
                    factType = Profile01WireConstants.FACT_TYPE_MEDIA_KEY_PACKAGE,
                    factDigestHex = "mk-digest",
                    conferenceEpoch = 1L,
                    membershipVersion = 3L,
                    mediaKeyEpoch = 4L,
                    headDigestHex = "head",
                ),
            ),
        )
    }
}

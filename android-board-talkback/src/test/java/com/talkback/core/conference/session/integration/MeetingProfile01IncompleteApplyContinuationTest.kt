package com.talkback.core.conference.session.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingProfile01IncompleteApplyContinuationTest {
    @Test
    fun retain_dedupesByConferenceEpochAndDigest() {
        val continuation = MeetingProfile01IncompleteApplyContinuation()
        val pending = samplePending()
        assertEquals(RetainPendingOutcome.RETAINED, continuation.retainPending(pending))
        assertEquals(RetainPendingOutcome.DEDUPED, continuation.retainPending(pending))
        assertEquals(1, continuation.pendingCount("conf-1"))
    }

    @Test
    fun detachForSupplementReady_consumesBeforeReentry() {
        val continuation = MeetingProfile01IncompleteApplyContinuation()
        continuation.retainPending(samplePending(mediaKeyEpoch = 2L))
        val detached = continuation.detachForSupplementReady("conf-1", 2L)
        assertEquals(1, detached.size)
        assertEquals(0, continuation.pendingCount("conf-1"))
        assertTrue(continuation.detachForSupplementReady("conf-1", 2L).isEmpty())
    }

    @Test
    fun discardForSession_removesSessionPending() {
        val continuation = MeetingProfile01IncompleteApplyContinuation()
        continuation.retainPending(samplePending(sessionId = "s1"))
        continuation.retainPending(samplePending(sessionId = "s2", factDigestHex = "digest-2"))
        continuation.discardForSession("s1", IncompleteDiscardReason.SESSION_UNREGISTERED)
        assertEquals(1, continuation.pendingCount("conf-1"))
    }

    private fun samplePending(
        sessionId: String = "session-1",
        mediaKeyEpoch: Long = 2L,
        factDigestHex: String = "digest-1",
    ): MeetingProfile01IncompleteApplyContinuation.PendingIncompleteCreationApply =
        MeetingProfile01IncompleteApplyContinuation.PendingIncompleteCreationApply(
            sessionId = sessionId,
            conferenceIdHex = "conf-1",
            mediaKeyEpoch = mediaKeyEpoch,
            factDigestHex = factDigestHex,
            signedFactBytes = byteArrayOf(0x01, 0x02),
            networkInterfaceName = "wlan0",
            channelId = "ch-1",
            retainedAtMs = System.currentTimeMillis(),
        )
}

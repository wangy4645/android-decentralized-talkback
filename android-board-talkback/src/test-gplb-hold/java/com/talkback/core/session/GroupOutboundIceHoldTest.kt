package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupOutboundIceHoldTest {
    private val hold = GroupOutboundIceHold()
    private val id1 = GroupOutboundIceHold.PcIdentity(pcGeneration = 1L, pcHash = 11)
    private val id2 = GroupOutboundIceHold.PcIdentity(pcGeneration = 2L, pcHash = 22)

    @Test
    fun candidateBeforeBinding_replaysInArrivalOrderOnSamePc() {
        assertEquals(
            GroupOutboundIceHold.Gate.Hold,
            hold.gate(liveOfferLineageId = null, identity = id1, pcStillLive = true, iceClosed = false),
        )
        assertEquals(GroupOutboundIceHold.Disposition.QUEUED, hold.enqueue("s", "M04", id1, "cand-a"))
        assertEquals(GroupOutboundIceHold.Disposition.QUEUED, hold.enqueue("s", "M04", id1, "cand-b"))
        val replayed = hold.takeForReplay("s", "M04", id1).map { it.candidate }
        assertEquals(listOf("cand-a", "cand-b"), replayed)
        assertEquals(0, hold.queuedCount("s", "M04", id1))
    }

    @Test
    fun queuedDuringGenerationChange_oldCandidatesNeverReplayOnNewPc() {
        hold.enqueue("s", "M04", id1, "old-host")
        val stale = hold.dropMismatched("s", "M04", id2)
        assertEquals(listOf("old-host"), stale.map { it.candidate })
        assertTrue(hold.takeForReplay("s", "M04", id2).isEmpty())
        assertEquals(0, hold.queuedCount("s", "M04", id1))
    }

    @Test
    fun staleEngineCallback_isDroppedNotQueued() {
        val gate = hold.gate(
            liveOfferLineageId = null,
            identity = id1,
            pcStillLive = false,
            iceClosed = false,
        )
        assertEquals(GroupOutboundIceHold.Gate.Drop(GroupOutboundIceHold.Disposition.STALE_DROPPED), gate)
        assertEquals(0, hold.queuedCount("s", "M04", id1))
    }

    @Test
    fun unknownPcIdentity_isRejectedNotGuessed() {
        val gate = hold.gate(
            liveOfferLineageId = null,
            identity = null,
            pcStillLive = true,
            iceClosed = false,
        )
        assertEquals(
            GroupOutboundIceHold.Gate.Drop(GroupOutboundIceHold.Disposition.IDENTITY_REJECTED),
            gate,
        )
    }

    @Test
    fun duplicateCandidate_isDroppedOnceQueued() {
        assertEquals(GroupOutboundIceHold.Disposition.QUEUED, hold.enqueue("s", "M04", id1, " cand-a "))
        assertEquals(
            GroupOutboundIceHold.Disposition.DUPLICATE_DROPPED,
            hold.enqueue("s", "M04", id1, "cand-a"),
        )
        hold.takeForReplay("s", "M04", id1)
        assertEquals(
            GroupOutboundIceHold.Disposition.DUPLICATE_DROPPED,
            hold.enqueue("s", "M04", id1, "cand-a"),
        )
    }

    @Test
    fun liveBindingPresent_staysOnDirectSendPath() {
        val gate = hold.gate(
            liveOfferLineageId = "GM1",
            identity = id1,
            pcStillLive = true,
            iceClosed = false,
        )
        assertEquals(GroupOutboundIceHold.Gate.SendNow("GM1"), gate)
        assertEquals(0, hold.queuedCount("s", "M04", id1))
    }

    @Test
    fun liveBindingPresent_doesNotHoldEvenIfIceClosed() {
        val gate = hold.gate(
            liveOfferLineageId = "GM1",
            identity = id1,
            pcStillLive = true,
            iceClosed = true,
        )
        assertEquals(GroupOutboundIceHold.Gate.SendNow("GM1"), gate)
    }

    @Test
    fun releaseInvalidatesQueuedCandidates() {
        hold.enqueue("s", "M04", id1, "cand-a")
        hold.enqueue("s", "M02", id1, "cand-b")
        val dropped = hold.invalidateSession("s")
        assertEquals(setOf("cand-a", "cand-b"), dropped.map { it.candidate }.toSet())
        assertTrue(hold.takeForReplay("s", "M04", id1).isEmpty())
    }

    @Test
    fun closedIce_isNotQueued() {
        val gate = hold.gate(
            liveOfferLineageId = null,
            identity = id1,
            pcStillLive = true,
            iceClosed = true,
        )
        assertEquals(GroupOutboundIceHold.Gate.Drop(GroupOutboundIceHold.Disposition.STALE_DROPPED), gate)
    }

    @Test
    fun arrivalOrderIsPreservedAndNotReSorted() {
        hold.enqueue("s", "M04", id1, "srflx")
        hold.enqueue("s", "M04", id1, "host")
        hold.enqueue("s", "M04", id1, "relay")
        assertEquals(
            listOf("srflx", "host", "relay"),
            hold.takeForReplay("s", "M04", id1).map { it.candidate },
        )
    }

    @Test
    fun overflowDoesNotGuessANewIdentity() {
        val bounded = GroupOutboundIceHold(maxQueuedPerIdentity = 1)
        assertEquals(GroupOutboundIceHold.Disposition.QUEUED, bounded.enqueue("s", "M04", id1, "a"))
        assertEquals(GroupOutboundIceHold.Disposition.SEND_FAILED, bounded.enqueue("s", "M04", id1, "b"))
        assertEquals(listOf("a"), bounded.takeForReplay("s", "M04", id1).map { it.candidate })
    }
}

package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.session.AdrALiveWatermarkFreezeContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-A desk contract only — no production strategy, no field, no probe hooks.
 *
 * Gates:
 * 1) burst loss: future RTP must not inflate effectiveLive / displacement past freeze.
 * 2) sparse loss: fill unfreezes; normal recovery displacement OK.
 * 3) no-loss / mild reorder: freeze not engaged; no artificial backlog signal.
 */
class AdrALiveWatermarkFreezeContractDeskTest {

    @Test
    fun burstLoss_futureRtpDoesNotAdvanceEffectiveLiveOrDisp() {
        val next = 100L
        // At hole open, live already at next+2 (within reorder cushion).
        var s =
            AdrALiveWatermarkFreezeContract.onHoleOpen(
                nextExpected = next,
                liveWatermark = next + 2,
            )
        assertTrue(s.holeOpen)
        assertEquals(next + 2, s.effectiveLiveWatermark)
        assertEquals(2L, s.displacement())

        // Field-like futures: +10 .. +16 arrive while hole still open.
        for (d in 10L..16L) {
            s = AdrALiveWatermarkFreezeContract.onIngressLive(s, next + d)
            assertEquals(
                "raw live may move",
                next + d,
                s.liveWatermark,
            )
            assertEquals(
                "effective live must stay frozen",
                next + 2,
                s.effectiveLiveWatermark,
            )
            assertEquals(
                "displacement must not amplify to 10–16",
                2L,
                s.displacement(),
            )
            assertTrue(
                "disp stays within MAX_REORDER",
                s.displacement() <= MediaJitterConstants.MAX_REORDER_PACKETS,
            )
        }
    }

    @Test
    fun sparseLoss_fillUnfreezes_andRecoveryCanTrackLive() {
        val next = 200L
        var s =
            AdrALiveWatermarkFreezeContract.onHoleOpen(
                nextExpected = next,
                liveWatermark = next + 1,
            )
        s = AdrALiveWatermarkFreezeContract.onIngressLive(s, next + 8)
        assertEquals(next + 1, s.effectiveLiveWatermark)

        // Hole filled at next; unfreeze. Live is already at next+8.
        s = AdrALiveWatermarkFreezeContract.onHoleFilled(s, nextExpected = next)
        assertFalse(s.holeOpen)
        assertEquals(next + 8, s.effectiveLiveWatermark)
        // After fill, cursor can advance under P1; displacement shrinks without forced PLC contract.
        s = AdrALiveWatermarkFreezeContract.onNextAdvance(s, newNext = next + 4, holeStillOpen = false)
        assertEquals(next + 4, s.nextExpected)
        assertEquals(4L, s.displacement())
        assertTrue(s.displacement() <= MediaJitterConstants.MAX_REORDER_PACKETS)
    }

    @Test
    fun noLoss_mildReorder_freezeNotEngaged_noArtificialBacklog() {
        // No hole: effective == live always.
        var s =
            AdrALiveWatermarkFreezeContract.State(
                nextExpected = 50L,
                liveWatermark = 50L,
                holeOpen = false,
                frozenWatermark = null,
            )
        // Mild reorder within 4: live ahead by 3.
        s = AdrALiveWatermarkFreezeContract.onIngressLive(s, 53L)
        assertFalse(s.holeOpen)
        assertEquals(53L, s.effectiveLiveWatermark)
        assertEquals(3L, s.displacement())
        assertTrue(s.displacement() <= MediaJitterConstants.MAX_REORDER_PACKETS)

        // Contiguous catch-up of next — no freeze artifact, gap closes.
        s = AdrALiveWatermarkFreezeContract.onNextAdvance(s, newNext = 53L, holeStillOpen = false)
        assertEquals(0L, s.displacement())
        assertEquals(null, s.frozenWatermark)
    }

    @Test
    fun holeOpen_freezeCapsAtNextPlusMaxReorder() {
        // If live already far at hole-open, freeze must not lock in the amplified watermark.
        val next = 300L
        val s =
            AdrALiveWatermarkFreezeContract.onHoleOpen(
                nextExpected = next,
                liveWatermark = next + 12,
            )
        assertEquals(next + MediaJitterConstants.MAX_REORDER_PACKETS, s.effectiveLiveWatermark)
        assertEquals(
            MediaJitterConstants.MAX_REORDER_PACKETS.toLong(),
            s.displacement(),
        )
    }
}

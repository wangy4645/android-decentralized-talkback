package com.talkback.core.conference.session

import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.MixedBlock
import com.talkback.core.conference.runtime.OpusCodecConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class D1EmptyTickPlayoutFillDeskTest {
    private val empty = MixedBlock(samples = shortArrayOf(), mixParticipantCount = 0, peakAbsFs = 0.0)
    private val nonEmpty =
        MixedBlock(
            samples = ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS) { 100 },
            mixParticipantCount = 1,
            peakAbsFs = 100.0,
        )

    @Test
    fun emptyBeforeFirstRealMix_skips() {
        val state = D1EmptyTickPlayoutFill.State()
        assertEquals(D1EmptyTickPlayoutFill.PlayoutWriteAction.SKIP, D1EmptyTickPlayoutFill.decide(empty, state))
    }

    @Test
    fun afterRealMix_emptyTicksFillUpToSixThenSkip() {
        val state = D1EmptyTickPlayoutFill.State()
        D1EmptyTickPlayoutFill.onSuccessfulWrite(D1EmptyTickPlayoutFill.PlayoutWriteAction.WRITE_MIXED_BLOCK, state)
        repeat(D1EmptyTickPlayoutFill.MAX_CONSECUTIVE_EMPTY_FILL_TICKS) { tick ->
            assertEquals(
                D1EmptyTickPlayoutFill.PlayoutWriteAction.WRITE_DIGITAL_SILENCE,
                D1EmptyTickPlayoutFill.decide(empty, state),
            )
            D1EmptyTickPlayoutFill.onSuccessfulWrite(D1EmptyTickPlayoutFill.PlayoutWriteAction.WRITE_DIGITAL_SILENCE, state)
            assertEquals(tick + 1, state.consecutiveEmptyFillTicks)
        }
        assertEquals(
            D1EmptyTickPlayoutFill.PlayoutWriteAction.SKIP,
            D1EmptyTickPlayoutFill.decide(empty, state),
        )
    }

    @Test
    fun realMixResetsEmptyFillCounter() {
        val state = D1EmptyTickPlayoutFill.State()
        D1EmptyTickPlayoutFill.onSuccessfulWrite(D1EmptyTickPlayoutFill.PlayoutWriteAction.WRITE_MIXED_BLOCK, state)
        repeat(3) {
            D1EmptyTickPlayoutFill.onSuccessfulWrite(D1EmptyTickPlayoutFill.PlayoutWriteAction.WRITE_DIGITAL_SILENCE, state)
        }
        assertEquals(3, state.consecutiveEmptyFillTicks)
        assertEquals(D1EmptyTickPlayoutFill.PlayoutWriteAction.WRITE_MIXED_BLOCK, D1EmptyTickPlayoutFill.decide(nonEmpty, state))
        D1EmptyTickPlayoutFill.onSuccessfulWrite(D1EmptyTickPlayoutFill.PlayoutWriteAction.WRITE_MIXED_BLOCK, state)
        assertEquals(0, state.consecutiveEmptyFillTicks)
    }

    @Test
    fun maxFillDurationAlignsWithPlayoutDelay() {
        assertEquals(
            MediaJitterConstants.MAX_PLAYOUT_DELAY_MS,
            D1EmptyTickPlayoutFill.maxFillDurationMs(),
        )
    }
}

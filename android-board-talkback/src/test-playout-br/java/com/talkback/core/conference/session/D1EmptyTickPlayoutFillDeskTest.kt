package com.talkback.core.conference.session

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
    fun afterRealMix_emptyTicksFillWithoutCap() {
        val state = D1EmptyTickPlayoutFill.State()
        D1EmptyTickPlayoutFill.onSuccessfulWrite(D1EmptyTickPlayoutFill.PlayoutWriteAction.WRITE_MIXED_BLOCK, state)
        val fifteenMinutesTicks = 15 * 60 * 50
        repeat(fifteenMinutesTicks) {
            assertEquals(
                D1EmptyTickPlayoutFill.PlayoutWriteAction.WRITE_DIGITAL_SILENCE,
                D1EmptyTickPlayoutFill.decide(empty, state),
            )
            D1EmptyTickPlayoutFill.onSuccessfulWrite(
                D1EmptyTickPlayoutFill.PlayoutWriteAction.WRITE_DIGITAL_SILENCE,
                state,
            )
        }
        assertEquals(
            D1EmptyTickPlayoutFill.PlayoutWriteAction.SKIP,
            D1EmptyTickPlayoutFill.decide(empty, D1EmptyTickPlayoutFill.State()),
        )
    }

    @Test
    fun realMixAfterSilence_writesMixedBlock() {
        val state = D1EmptyTickPlayoutFill.State()
        D1EmptyTickPlayoutFill.onSuccessfulWrite(D1EmptyTickPlayoutFill.PlayoutWriteAction.WRITE_MIXED_BLOCK, state)
        repeat(3) {
            D1EmptyTickPlayoutFill.onSuccessfulWrite(
                D1EmptyTickPlayoutFill.PlayoutWriteAction.WRITE_DIGITAL_SILENCE,
                state,
            )
        }
        assertEquals(
            D1EmptyTickPlayoutFill.PlayoutWriteAction.WRITE_MIXED_BLOCK,
            D1EmptyTickPlayoutFill.decide(nonEmpty, state),
        )
    }
}

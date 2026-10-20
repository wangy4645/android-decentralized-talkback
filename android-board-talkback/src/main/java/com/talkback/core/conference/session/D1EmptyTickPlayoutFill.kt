package com.talkback.core.conference.session

import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.MixedBlock
import com.talkback.core.conference.runtime.OpusCodecConstants

/**
 * D1 — bounded digital silence when mix output is empty but playout clock should advance.
 * See PROPOSAL-D1-EMPTY-TICK-PLAYOUT-20261019.txt.
 */
internal object D1EmptyTickPlayoutFill {
    const val MAX_CONSECUTIVE_EMPTY_FILL_TICKS: Int = 6

    val silenceFrameSamples: Int = OpusCodecConstants.FRAME_SAMPLES_20MS

    data class State(
        var hasWrittenRealMix: Boolean = false,
        var consecutiveEmptyFillTicks: Int = 0,
    )

    enum class PlayoutWriteAction {
        /** Do not call AudioTrack.write. */
        SKIP,
        /** Write [block] as-is. */
        WRITE_MIXED_BLOCK,
        /** Write digital silence (zeros), same frame length as production mix tick. */
        WRITE_DIGITAL_SILENCE,
    }

    fun decide(block: MixedBlock, state: State): PlayoutWriteAction {
        if (block.samples.isNotEmpty()) {
            return PlayoutWriteAction.WRITE_MIXED_BLOCK
        }
        if (!state.hasWrittenRealMix) {
            return PlayoutWriteAction.SKIP
        }
        if (state.consecutiveEmptyFillTicks >= MAX_CONSECUTIVE_EMPTY_FILL_TICKS) {
            return PlayoutWriteAction.SKIP
        }
        return PlayoutWriteAction.WRITE_DIGITAL_SILENCE
    }

    fun onSuccessfulWrite(action: PlayoutWriteAction, state: State) {
        when (action) {
            PlayoutWriteAction.WRITE_MIXED_BLOCK -> {
                state.hasWrittenRealMix = true
                state.consecutiveEmptyFillTicks = 0
            }
            PlayoutWriteAction.WRITE_DIGITAL_SILENCE -> {
                state.consecutiveEmptyFillTicks += 1
            }
            PlayoutWriteAction.SKIP -> Unit
        }
    }

    fun reset(state: State) {
        state.hasWrittenRealMix = false
        state.consecutiveEmptyFillTicks = 0
    }

    /** Aligns with [MediaJitterConstants.MAX_PLAYOUT_DELAY_MS] at 20ms/tick. */
    fun maxFillDurationMs(): Long =
        MAX_CONSECUTIVE_EMPTY_FILL_TICKS.toLong() * MediaJitterConstants.MEDIA_SLOT_MS
}

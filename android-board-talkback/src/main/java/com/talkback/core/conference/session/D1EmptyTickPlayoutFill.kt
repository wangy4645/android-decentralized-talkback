package com.talkback.core.conference.session

import com.talkback.core.conference.runtime.MixedBlock
import com.talkback.core.conference.runtime.OpusCodecConstants

/**
 * Bounded digital silence when mix output is empty but playout clock should advance.
 *
 * A1 (2026-11-04): after the first real mix write, empty ticks keep filling 20 ms zeros
 * with no consecutive cap. Pre-first-real-mix empty ticks still SKIP (join/hold window).
 */
internal object D1EmptyTickPlayoutFill {
    val silenceFrameSamples: Int = OpusCodecConstants.FRAME_SAMPLES_20MS

    data class State(
        var hasWrittenRealMix: Boolean = false,
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
        return PlayoutWriteAction.WRITE_DIGITAL_SILENCE
    }

    fun onSuccessfulWrite(action: PlayoutWriteAction, state: State) {
        when (action) {
            PlayoutWriteAction.WRITE_MIXED_BLOCK -> {
                state.hasWrittenRealMix = true
            }
            PlayoutWriteAction.WRITE_DIGITAL_SILENCE,
            PlayoutWriteAction.SKIP,
            -> Unit
        }
    }

    fun reset(state: State) {
        state.hasWrittenRealMix = false
    }
}

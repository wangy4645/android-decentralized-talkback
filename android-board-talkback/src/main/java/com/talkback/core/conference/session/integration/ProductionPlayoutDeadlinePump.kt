package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.MixedBlock

/**
 * Keeps AudioTrack on a 20 ms deadline: write the last prepared mix (or a bounded
 * silence substitute) without waiting for this tick's decode.
 */
internal class ProductionPlayoutDeadlinePump {
    @Volatile
    private var pending: MixedBlock? = null
    private var lastWritten: MixedBlock? = null
    private var consecutiveSame = 0

    fun publishPrepared(block: MixedBlock) {
        if (block.samples.isNotEmpty()) {
            pending = block
        }
    }

    fun blockForDeadlineWrite(): MixedBlock {
        val prepared = pending
        if (prepared == null || prepared.samples.isEmpty()) {
            lastWritten = null
            consecutiveSame = 0
            return EMPTY
        }
        if (prepared === lastWritten) {
            consecutiveSame += 1
            if (consecutiveSame > MAX_SAME_FRAME_REPEATS) {
                return EMPTY
            }
        } else {
            lastWritten = prepared
            consecutiveSame = 0
        }
        return prepared
    }

    companion object {
        const val MAX_SAME_FRAME_REPEATS: Int = 1
        val EMPTY: MixedBlock = MixedBlock(shortArrayOf(), 0, 0.0)
    }
}

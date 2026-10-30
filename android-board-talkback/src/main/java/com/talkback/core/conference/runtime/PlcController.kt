package com.talkback.core.conference.runtime

/**
 * Profile 03 Q4 PLC (E2b-03). Synthesis is counted only — no Opus/PCM.
 *
 * C-E2B03-04: requires decode eligibility; only after Q3 deadline-lost slot;
 * max 5 consecutive; 6th → silence/gap; exhaustion ≠ fence/revoke.
 */
class PlcController {
    private var consecutivePlcFrames: Int = 0

    fun consecutiveCount(): Int = consecutivePlcFrames

    fun onDecodedRealFrame() {
        consecutivePlcFrames = 0
    }

    /**
     * @return PLC_SYNTHESIS or SILENCE_GAP
     */
    fun onDeadlineLostSlot(decodeEligible: Boolean): SlotPullDisposition {
        if (!decodeEligible) {
            consecutivePlcFrames = 0
            return SlotPullDisposition.SILENCE_GAP
        }
        if (consecutivePlcFrames >= MediaJitterConstants.MAX_CONSECUTIVE_PLC_FRAMES) {
            return SlotPullDisposition.SILENCE_GAP
        }
        consecutivePlcFrames += 1
        return SlotPullDisposition.PLC_SYNTHESIS
    }

    fun reset() {
        consecutivePlcFrames = 0
    }
}

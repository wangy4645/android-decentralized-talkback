package com.talkback.core.conference.runtime

/**
 * C-E2B04-01 / C-E2B04-04 warm-up gate.
 * DecoderWarmupMs is a maximum allowance — never a mandatory sleep.
 */
object WarmupGate {
    enum class Decision {
        /** PCM usable now — mixer eligibility may proceed immediately. */
        MIX_ELIGIBLE_NOW,

        /** Still warming; within DecoderWarmupMs bound. */
        WARMING,

        /**
         * Bound expired without usable PCM.
         * Stops this transition only — MUST NOT fence/revoke/Top-K/Health/PLC.
         */
        TRANSITION_EXPIRED,
    }

    fun evaluate(
        pcm: PcmFrame?,
        slot: LiveDecoderSlot,
        nowMs: Long,
    ): Decision {
        if (pcm != null && pcm.usableForMix) {
            return Decision.MIX_ELIGIBLE_NOW
        }
        if (nowMs <= slot.warmupDeadlineMs) {
            return Decision.WARMING
        }
        return Decision.TRANSITION_EXPIRED
    }
}

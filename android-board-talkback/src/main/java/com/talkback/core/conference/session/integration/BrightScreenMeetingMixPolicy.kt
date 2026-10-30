package com.talkback.core.conference.session.integration

/**
 * Bright-screen meeting audible fix (2026-10) — production defaults with explicit rollback.
 *
 * Field flag [MulticastMixInputHeadroomFieldExperiment] overrides production headroom when active.
 */
object BrightScreenMeetingMixPolicy {
    /** Production mix-input attenuation (dB ≤ 0). Not −6 dB; tunable without redeploy if wired from config later. */
    const val DEFAULT_PRODUCTION_MIX_INPUT_HEADROOM_DB: Double = -3.0

    enum class MixCombineMode {
        /** Legacy: sum then ÷N, instantaneous block peak scale to 0.89 FS. */
        EQUAL_WEIGHT_AVERAGE,

        /** A/B candidate: linear sum + smoothed soft peak limiter to 0.89 FS. */
        LINEAR_SUM_SOFT_LIMIT,

        /** Field candidate: equal sum ÷ max(1, active speech sources); speech = frame rms > −45 dBFS. */
        ACTIVE_SPEECH_EQUAL_WEIGHT,
    }

    @Volatile
    var productionMixInputHeadroomEnabled: Boolean = true

    @Volatile
    var productionMixInputHeadroomDb: Double = DEFAULT_PRODUCTION_MIX_INPUT_HEADROOM_DB

    /**
     * Production default (2026-10-27, five-device field): ACTIVE_SPEECH_EQUAL_WEIGHT. EQUAL_WEIGHT_AVERAGE
     * divided by every source with a frame (silent members included), costing a lone speaker ~12 dB.
     * Rollback without rebuild: `files/mix_combine_field.flag` = `AVG` ([MixCombineModeFieldExperiment]).
     */
    val DEFAULT_PRODUCTION_MIX_COMBINE_MODE: MixCombineMode = MixCombineMode.ACTIVE_SPEECH_EQUAL_WEIGHT

    @Volatile
    var productionMixCombineMode: MixCombineMode = DEFAULT_PRODUCTION_MIX_COMBINE_MODE

    /** Narrow Top-K fallback when ranked Top-K has no pullable frame but V=1 sources do. */
    @Volatile
    var playableTopKFallbackEnabled: Boolean = true

    /** D1 bounded wait + effective Top-K exclusion for unpullable ranked sources. */
    @Volatile
    var playableTopKDeferralEnabled: Boolean = true

    fun resetForTest() {
        productionMixInputHeadroomEnabled = true
        productionMixInputHeadroomDb = DEFAULT_PRODUCTION_MIX_INPUT_HEADROOM_DB
        productionMixCombineMode = DEFAULT_PRODUCTION_MIX_COMBINE_MODE
        playableTopKFallbackEnabled = true
        playableTopKDeferralEnabled = true
    }
}

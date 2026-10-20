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

    @Volatile
    var productionMixCombineMode: MixCombineMode = MixCombineMode.EQUAL_WEIGHT_AVERAGE

    /** Narrow Top-K fallback when ranked Top-K has no pullable frame but V=1 sources do. */
    @Volatile
    var playableTopKFallbackEnabled: Boolean = true

    /** D1 bounded wait + effective Top-K exclusion for unpullable ranked sources. */
    @Volatile
    var playableTopKDeferralEnabled: Boolean = true

    fun resetForTest() {
        productionMixInputHeadroomEnabled = true
        productionMixInputHeadroomDb = DEFAULT_PRODUCTION_MIX_INPUT_HEADROOM_DB
        productionMixCombineMode = MixCombineMode.EQUAL_WEIGHT_AVERAGE
        playableTopKFallbackEnabled = true
        playableTopKDeferralEnabled = true
    }
}

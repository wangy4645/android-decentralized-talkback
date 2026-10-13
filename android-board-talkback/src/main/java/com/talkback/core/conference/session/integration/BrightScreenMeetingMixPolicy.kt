package com.talkback.core.conference.session.integration

/**
 * Bright-screen meeting audible fix (2026-10) — production defaults with explicit rollback.
 *
 * Field flag [MulticastMixInputHeadroomFieldExperiment] overrides production headroom when active.
 */
object BrightScreenMeetingMixPolicy {
    /** Production mix-input attenuation (dB ≤ 0). Not −6 dB; tunable without redeploy if wired from config later. */
    const val DEFAULT_PRODUCTION_MIX_INPUT_HEADROOM_DB: Double = -3.0

    @Volatile
    var productionMixInputHeadroomEnabled: Boolean = true

    @Volatile
    var productionMixInputHeadroomDb: Double = DEFAULT_PRODUCTION_MIX_INPUT_HEADROOM_DB

    /** Narrow Top-K fallback when ranked Top-K has no pullable frame but V=1 sources do. */
    @Volatile
    var playableTopKFallbackEnabled: Boolean = true

    fun resetForTest() {
        productionMixInputHeadroomEnabled = true
        productionMixInputHeadroomDb = DEFAULT_PRODUCTION_MIX_INPUT_HEADROOM_DB
        playableTopKFallbackEnabled = true
    }
}

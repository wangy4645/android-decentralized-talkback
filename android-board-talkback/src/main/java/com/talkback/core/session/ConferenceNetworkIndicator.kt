package com.talkback.core.session

/**
 * Conference network-quality read model (ADR-0022 R27′-A / #80).
 * UI / ViewModel MUST consume this via the runtime facade — not [qosMonitor] ICE internals.
 */
enum class ConferenceNetworkIndicator {
    EXCELLENT,
    GOOD,
    DEGRADED,
    UNKNOWN;

    /** Legacy presentation strings used by meeting/talk badges. */
    fun toQualityLabel(): String = when (this) {
        EXCELLENT -> "Excellent"
        GOOD -> "Good"
        DEGRADED -> "Poor"
        UNKNOWN -> "N/A"
    }
}

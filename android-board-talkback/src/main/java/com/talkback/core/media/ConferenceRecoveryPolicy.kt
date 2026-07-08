package com.talkback.core.media

/**
 * Conference media recovery escalation parameters (ADR-0018 / ADR-CONF-001).
 * Policy lives here — not hard-coded in [MediaSessionManager].
 */
data class ConferenceRecoveryPolicy(
    val checkingTimeoutMs: Long = 8_000L,
    val maxIceRestartAttempts: Int = 2
)

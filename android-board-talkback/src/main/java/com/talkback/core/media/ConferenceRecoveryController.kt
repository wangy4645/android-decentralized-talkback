package com.talkback.core.media

/**
 * PR-1 stub: sole authority that may request PC recreation after failed recovery (ADR-0018).
 * Full ICE-restart escalation wired in PR-3; coordinator calls [requestRecovery] only.
 */
class ConferenceRecoveryController(
    private val policy: ConferenceRecoveryPolicy = ConferenceRecoveryPolicy(),
    private val sessionManager: MediaSessionManager
) {
    fun requestRecovery(channelId: String, moduleId: String, reason: String) {
        // PR-3: evaluate policy.checkingTimeoutMs / maxIceRestartAttempts, ICE restart, then recreate
    }

    fun requestRecreateIfNeeded(moduleId: String) {
        // PR-3: close + create after exhausted ICE restarts per ADR-0018 escalation
    }

    fun policy(): ConferenceRecoveryPolicy = policy
}

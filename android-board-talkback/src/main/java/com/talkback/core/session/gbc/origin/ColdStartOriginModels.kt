package com.talkback.core.session.gbc.origin

import com.talkback.core.session.gbc.ConvergenceEffect
import com.talkback.core.session.gbc.FactDeliveryResult

sealed class ColdStartOriginEligibility {
    data object Eligible : ColdStartOriginEligibility()

    sealed class NotEligible : ColdStartOriginEligibility() {
        data object NotBootstrapCandidate : NotEligible()

        data object LegalCurrentExists : NotEligible()

        data object IssuanceUnavailable : NotEligible()
    }
}

sealed class ColdStartOriginAttemptResult {
    data class Issued(
        val channelId: String,
        val generationIdentity: String,
        val effects: List<ConvergenceEffect>,
    ) : ColdStartOriginAttemptResult()

    data class Restored(
        val channelId: String,
        val generationIdentity: String,
        val effects: List<ConvergenceEffect>,
    ) : ColdStartOriginAttemptResult()

    data class Skipped(
        val reason: ColdStartOriginEligibility.NotEligible,
    ) : ColdStartOriginAttemptResult()

    data class Failed(
        val stage: String,
    ) : ColdStartOriginAttemptResult()
}

data class LocalOriginAcceptanceResult(
    val delivery: FactDeliveryResult,
    val effects: List<ConvergenceEffect>,
)

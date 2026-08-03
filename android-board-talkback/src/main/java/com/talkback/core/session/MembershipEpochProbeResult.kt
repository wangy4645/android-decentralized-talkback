package com.talkback.core.session

/**
 * ADR-0022 E.18.2: explicit membership epoch probe outcome for control reconciliation.
 *
 * `Unwired` is not evidence of convergence failure — it means no authority answered the question.
 */
internal sealed interface MembershipEpochProbeResult {

    data class Checked(
        val authorityId: String,
        val expectedEpoch: Long,
        val observedEpoch: Long,
        val converged: Boolean
    ) : MembershipEpochProbeResult

    data class Unwired(
        val reason: String
    ) : MembershipEpochProbeResult
}
package com.talkback.core.session

/**
 * Fresh observer-local edge facts for #175 post-obligation close convergence evaluation.
 * Values MUST be read at evaluation time — not reused from the close event.
 */
internal data class PostObligationCloseEdgeSnapshot(
    val sessionId: String,
    val remoteModuleId: String,
    val obligationGen: Long,
    val attempt: Long,
    val iceConnected: Boolean,
    val receivePathLive: Boolean,
    val mediaUnavailable: Boolean,
    val phase: EdgeRecoveryPhase,
    val lifecycleActive: Boolean,
    val membership: String
) {
    val result: PostObligationCloseEvalResult =
        if (isEdgeRecoveryTruthSatisfied()) {
            PostObligationCloseEvalResult.SATISFIED
        } else {
            PostObligationCloseEvalResult.UNSATISFIED
        }

    /** Transport + residency must both clear for local edge recovery truth. */
    fun isEdgeRecoveryTruthSatisfied(): Boolean = iceConnected && !mediaUnavailable
}

internal enum class PostObligationCloseEvalResult {
    SATISFIED,
    UNSATISFIED
}

internal enum class PostObligationCloseAdmissionOutcome {
    NO_ADMISSION,
    NONE
}

internal object PostObligationCloseAdmissionPolicy {
    fun evaluate(
        snapshot: PostObligationCloseEdgeSnapshot
    ): Pair<PostObligationCloseAdmissionOutcome, String?> =
        if (!snapshot.isEdgeRecoveryTruthSatisfied()) {
            PostObligationCloseAdmissionOutcome.NO_ADMISSION to "edge_unsatisfied"
        } else {
            PostObligationCloseAdmissionOutcome.NONE to null
        }
}

package com.talkback.core.session

/**
 * PR5-3 M0 — UVCP-facing completion projection surface (frozen contract).
 *
 * Contains only outcome + explanatory facts + identity metadata.
 * Must NOT carry Attempt/Intent/RecoveryPhase/Media/Activation machinery (Leakage L-1…L-5).
 *
 * M0: shadow emit / logging only — not consumed by UVCP render.
 */
data class EpisodeCompletionProjection(
    val sessionId: String,
    val remoteModuleId: String,
    val obligationGeneration: Long,
    val completionState: EpisodeCompletionState,
    val completionReason: String,
    val completionSource: String,
    val completionEpochMs: Long
) {
    fun toShadowLogLine(): String =
        "EPISODE_COMPLETION_PROJECTION_SHADOW" +
            " session=$sessionId" +
            " remote=$remoteModuleId" +
            " obligationGen=$obligationGeneration" +
            " completionState=$completionState" +
            " completionReason=$completionReason" +
            " completionSource=$completionSource" +
            " completionEpochMs=$completionEpochMs"
}

/**
 * ADR episode completion vocabulary only.
 * [OPEN] = non-terminal (obligation/episode not closed as RECOVERED/FAILED_FINAL) — not a parallel PENDING enum.
 */
enum class EpisodeCompletionState {
    RECOVERED,
    FAILED_FINAL,
    OPEN
}
package com.talkback.core.conference.runtime

/**
 * ADR-0058 Profile 03 Q5 frozen constants (E2b-02 subset).
 */
object MediaRuntimeConstants {
    const val TOP_K: Int = 4
    const val TOP_K_HOLD_MS: Long = 200L
    const val TOP_K_HYSTERESIS_LEVEL: Int = 3
}

/**
 * Authority-bearing runtime input. Created only by control/Source facts — never by packet arrival.
 */
data class AdmittedMediaSource(
    /** Deterministic lexical identity for Top-K tie-break only (no authority semantics). */
    val sourceIdentity: String,
    val incarnationId: Long,
    val ssrc: Int = 0,
)

enum class ExecutionFenceState {
    OPEN,
    HARD_FENCED,
}

data class InstalledExecution(
    val source: AdmittedMediaSource,
    val fence: ExecutionFenceState = ExecutionFenceState.OPEN,
) {
    val isExecutable: Boolean
        get() = fence == ExecutionFenceState.OPEN
}

/**
 * Authenticated V/level observation after Profile 02 admission.
 * Updating this MUST NOT create [AdmittedMediaSource].
 */
data class VoiceLevelObservation(
    val sourceIdentity: String,
    val incarnationId: Long,
    /** V bit: true = V=1 eligible candidate; false = V=0. */
    val voiceActive: Boolean,
    /** Lower is louder (RFC 6464-style). */
    val audioLevel: Int,
)

data class TopKMember(
    val sourceIdentity: String,
    val incarnationId: Long,
    val audioLevel: Int,
)

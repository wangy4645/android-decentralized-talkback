package com.talkback.core.session.gbc

/**
 * ADR-0057 Group Bootstrap Convergence — post-verification seam types.
 * Wire/crypto/timer NOT authorized; Facts enter as already-verified inputs.
 */

/** Local generation knowledge before/after Fact consumption. */
sealed class LocalGenerationKnowledge {
    data object Unknown : LocalGenerationKnowledge()

    data class Known(
        val generationIdentity: String,
        /** True iff this Module currently treats it as Current (may be wrong until Fact). */
        val treatedAsCurrent: Boolean,
    ) : LocalGenerationKnowledge()
}

/**
 * Immutable authoritative Fact semantics (GBC-S0/S3 + Grill Q7).
 * Current/successor attestation is part of the immutable set.
 */
data class AuthoritativeGenerationFact(
    val generationIdentity: String,
    /** Null iff genesis / no predecessor. */
    val predecessorGenerationIdentity: String?,
    val originAuthorityIdentity: String,
    /**
     * Attests this Fact's generation is Current and (when predecessor set)
     * is the authoritative successor that supersedes that predecessor.
     * MUST be covered by [semanticDigest] verification boundary (fixture seam).
     */
    val attestsCurrent: Boolean,
    val semanticDigest: String,
    val resolvesConflictSet: Set<String> = emptySet(),
) {
    val factIdentity: String get() = semanticDigest
}

enum class FactRelation {
    SAME,
    SUPERSEDED,
    UNKNOWN,
}

enum class ObligationState {
    OPEN,
    CLOSED,
}

enum class GenerationFenceState {
    NONE,
    FENCED,
}

data class AcquisitionObligation(
    val channelId: String,
    val state: ObligationState,
    val reason: String,
)

/**
 * Effects returned to Coordinator orchestration — Coordinator executes, does not invent truth.
 */
sealed class ConvergenceEffect {
    data class RequestFact(val channelId: String) : ConvergenceEffect()

    data class FenceGeneration(
        val channelId: String,
        val generationIdentity: String,
    ) : ConvergenceEffect()

    data class RealignToCurrent(
        val channelId: String,
        val currentGenerationIdentity: String,
    ) : ConvergenceEffect()

    data class ObligationClosed(val channelId: String) : ConvergenceEffect()
}

data class ConvergenceSnapshot(
    val channelId: String,
    val knowledge: LocalGenerationKnowledge,
    val acceptedCurrent: AuthoritativeGenerationFact?,
    val obligation: ObligationState,
    val fencedGenerations: Set<String>,
    val lastRelation: FactRelation?,
)

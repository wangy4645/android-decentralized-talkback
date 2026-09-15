package com.talkback.core.session.gbc

/**
 * Unverified Generation Fact candidate material (V1).
 * Internal semantic object — NOT a frozen wire schema.
 */
data class GenerationFactCandidate(
    val claimedFactIdentity: String,
    val claimedGenerationIdentity: String,
    val claimedPredecessorGenerationIdentity: String?,
    val claimedOriginAuthorityIdentity: String,
    val claimedAttestsCurrent: Boolean,
    val claimedSemanticDigest: String,
    /** Opaque carrier for injectable verifier / harness; not production wire layout. */
    val opaqueMaterial: String = "",
)

/**
 * Verification Boundary semantic outcomes (GBC-V1.2).
 * MUST NOT include SAME / SUPERSEDED / obligation / fence.
 */
sealed class VerificationOutcome {
    data class Success(
        val fact: AuthoritativeGenerationFact,
    ) : VerificationOutcome()

    data object Malformed : VerificationOutcome()

    data object Unverifiable : VerificationOutcome()

    data object VerifyFail : VerificationOutcome()

    data object IntegrityAnomaly : VerificationOutcome()

    fun isPromotable(): Boolean = this is Success
}

fun interface GenerationFactVerifier {
    fun verify(candidate: GenerationFactCandidate): VerificationOutcome
}

/**
 * Test / injectable verifier: may only emit Verification Boundary outcomes.
 * MUST NOT invent F1 / obligation / fence / convergence conclusions.
 */
class InjectableGenerationFactVerifier : GenerationFactVerifier {
    private val byOpaque = linkedMapOf<String, VerificationOutcome>()
    private val byDigest = linkedMapOf<String, VerificationOutcome>()
    private var defaultOutcome: VerificationOutcome = VerificationOutcome.Unverifiable

    fun stubOpaque(
        opaqueMaterial: String,
        outcome: VerificationOutcome,
    ) {
        byOpaque[opaqueMaterial] = outcome
    }

    fun stubDigest(
        digest: String,
        outcome: VerificationOutcome,
    ) {
        byDigest[digest] = outcome
    }

    fun stubDefault(outcome: VerificationOutcome) {
        defaultOutcome = outcome
    }

    fun clear() {
        byOpaque.clear()
        byDigest.clear()
        defaultOutcome = VerificationOutcome.Unverifiable
    }

    override fun verify(candidate: GenerationFactCandidate): VerificationOutcome {
        byOpaque[candidate.opaqueMaterial]?.let { return it }
        byDigest[candidate.claimedSemanticDigest]?.let { return it }
        return defaultOutcome
    }
}

/**
 * GBC-V1.1 sole promotion point: candidate → verified Fact or non-promotable outcome.
 * Does not own FactStore / F1 / Obligation.
 */
class VerificationBoundary(
    private val verifier: GenerationFactVerifier,
) {
    var lastOutcome: VerificationOutcome? = null
        private set

    fun admit(candidate: GenerationFactCandidate): VerificationOutcome {
        if (!isStructurallyAdmissible(candidate)) {
            return VerificationOutcome.Malformed.also { lastOutcome = it }
        }
        // V1.5: same claimed identity with inconsistent digest inside the candidate itself.
        if (candidate.claimedFactIdentity.isNotBlank() &&
            candidate.claimedSemanticDigest.isNotBlank() &&
            candidate.claimedFactIdentity != candidate.claimedSemanticDigest
        ) {
            // Identity claim must equal digest under our frozen Fact model (factIdentity = digest).
            return VerificationOutcome.IntegrityAnomaly.also { lastOutcome = it }
        }
        val outcome = verifier.verify(candidate)
        lastOutcome = outcome
        return when (outcome) {
            is VerificationOutcome.Success -> {
                // Verifier SUCCESS must still yield a Fact whose immutable fields match claims.
                val fact = outcome.fact
                if (fact.semanticDigest != candidate.claimedSemanticDigest ||
                    fact.generationIdentity != candidate.claimedGenerationIdentity
                ) {
                    VerificationOutcome.IntegrityAnomaly.also { lastOutcome = it }
                } else {
                    outcome
                }
            }
            else -> outcome
        }
    }

    private fun isStructurallyAdmissible(candidate: GenerationFactCandidate): Boolean =
        candidate.claimedGenerationIdentity.isNotBlank() &&
            candidate.claimedSemanticDigest.isNotBlank() &&
            candidate.claimedOriginAuthorityIdentity.isNotBlank()
}

/** Helpers for harness: promote claims into Fact on SUCCESS stubs. */
fun successFromCandidate(candidate: GenerationFactCandidate): VerificationOutcome.Success =
    VerificationOutcome.Success(
        AuthoritativeGenerationFact(
            generationIdentity = candidate.claimedGenerationIdentity,
            predecessorGenerationIdentity = candidate.claimedPredecessorGenerationIdentity,
            originAuthorityIdentity = candidate.claimedOriginAuthorityIdentity,
            attestsCurrent = candidate.claimedAttestsCurrent,
            semanticDigest = candidate.claimedSemanticDigest,
        ),
    )

package com.talkback.core.session.gbc

/**
 * GBC-F1 pure classifier. Output domain: SAME | SUPERSEDED | UNKNOWN only.
 * Does not invent PENDING/ADMISSION.
 */
object GenerationClassifier {
    fun classify(
        local: LocalGenerationKnowledge,
        fact: AuthoritativeGenerationFact,
    ): FactRelation {
        if (!fact.attestsCurrent) {
            return FactRelation.UNKNOWN
        }
        return when (local) {
            is LocalGenerationKnowledge.Unknown -> FactRelation.UNKNOWN
            is LocalGenerationKnowledge.Known -> {
                when {
                    local.generationIdentity == fact.generationIdentity -> FactRelation.SAME
                    fact.predecessorGenerationIdentity == local.generationIdentity ->
                        FactRelation.SUPERSEDED
                    else -> FactRelation.UNKNOWN
                }
            }
        }
    }

    /**
     * Whether [candidate] is proven non-Current given an already-accepted Current [accepted].
     */
    fun isProvenStaleAgainstAccepted(
        candidate: AuthoritativeGenerationFact,
        acceptedCurrent: AuthoritativeGenerationFact,
    ): Boolean {
        if (candidate.factIdentity == acceptedCurrent.factIdentity) return false
        if (!acceptedCurrent.attestsCurrent) return false
        // Accepted Current supersedes its predecessor; candidate equal to that predecessor is stale.
        if (acceptedCurrent.predecessorGenerationIdentity == candidate.generationIdentity) {
            return true
        }
        // Candidate claims Current but differs from accepted Current with no order → not auto-stale here.
        return false
    }
}

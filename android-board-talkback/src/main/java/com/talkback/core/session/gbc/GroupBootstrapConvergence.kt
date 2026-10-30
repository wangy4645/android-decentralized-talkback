package com.talkback.core.session.gbc

/**
 * Dedicated ADR-0057 convergence owner (C-R1).
 * Coordinator wires observations/effects; this Module owns generation truth.
 *
 * Wire/crypto/timer NOT in scope — Facts enter only as already-verified inputs.
 */
class GroupBootstrapConvergence(
    private val channelId: String,
    private val factStore: FactStore = FactStore(),
    private val obligations: AcquisitionObligationTracker = AcquisitionObligationTracker(),
) {
    private var localKnowledge: LocalGenerationKnowledge = LocalGenerationKnowledge.Unknown
    private val fencedGenerations = linkedSetOf<String>()
    private var lastRelation: FactRelation? = null
    private var activeCorrelation: String? = null

    /** Active acquisition exchange bookkeeping only (M5) — not Fact authority. */
    fun beginAcquisitionExchange(correlation: String) {
        activeCorrelation = correlation
    }

    fun expireAcquisitionExchange(correlation: String) {
        if (activeCorrelation == correlation) {
            activeCorrelation = null
        }
    }

    fun snapshot(): ConvergenceSnapshot =
        ConvergenceSnapshot(
            channelId = channelId,
            knowledge = localKnowledge,
            acceptedCurrent = factStore.current(),
            obligation = obligations.state(),
            fencedGenerations = fencedGenerations.toSet(),
            lastRelation = lastRelation,
        )

    fun factStore(): FactStore = factStore

    /**
     * D2: generation knowledge insufficient/inconsistent → obligation OPEN.
     * Does NOT read waitingForPrimary / retry counts (C-R2).
     */
    fun onAcquisitionRequired(reason: String): List<ConvergenceEffect> {
        if (obligations.isOpen()) {
            return listOf(ConvergenceEffect.RequestFact(channelId))
        }
        // Close only if already resolved Current knowledge exists (L3).
        if (hasResolvedCurrentKnowledge()) {
            return emptyList()
        }
        obligations.open(channelId, reason)
        return listOf(ConvergenceEffect.RequestFact(channelId))
    }

    /**
     * Observe local treated-as-Current generation (session/bootstrap observation).
     * Observation may open acquisition; it does not invent Fact authority.
     */
    fun onLocalGenerationObserved(
        generationIdentity: String?,
        treatedAsCurrent: Boolean,
    ): List<ConvergenceEffect> {
        localKnowledge =
            if (generationIdentity.isNullOrBlank()) {
                LocalGenerationKnowledge.Unknown
            } else {
                LocalGenerationKnowledge.Known(generationIdentity, treatedAsCurrent)
            }
        return when {
            localKnowledge is LocalGenerationKnowledge.Unknown ->
                onAcquisitionRequired("no_local_generation")
            treatedAsCurrent && factStore.current() == null ->
                onAcquisitionRequired("local_current_unverified")
            factStore.current() != null &&
                localKnowledge is LocalGenerationKnowledge.Known &&
                (localKnowledge as LocalGenerationKnowledge.Known).generationIdentity !=
                factStore.current()!!.generationIdentity ->
                onAcquisitionRequired("local_current_mismatch")
            else -> emptyList()
        }
    }

    /**
     * Consume a RESPONSE for exchange [correlation].
     * @param admissiblePath whether this RESPONSE has an independently admissible
     *   consumption path (M5/D): correlation match is neither necessary nor sufficient alone.
     */
    fun onFactResponse(
        correlation: String?,
        outcome: FactResponseOutcome,
        admissiblePath: Boolean,
    ): List<ConvergenceEffect> {
        when (outcome) {
            is FactResponseOutcome.Insufficient -> {
                // Bookkeeping: may ignore for expired exchange.
                if (correlation != null && correlation != activeCorrelation) {
                    // late / unrelated INSUFFICIENT for bookkeeping — still apply M7 below
                }
                obligations.onInsufficientOrAttemptTerminal()
                // M7: never regress accepted Current / reopen solely from INSUFFICIENT
                return emptyList()
            }
            is FactResponseOutcome.Fact -> {
                if (!admissiblePath) {
                    // Exchange bookkeeping ignore; no authority change from this path.
                    return emptyList()
                }
                return acceptVerifiedFact(outcome.fact)
            }
        }
    }

    /**
     * Direct post-verification ingress (fixture / authorized synthetic seam).
     */
    fun acceptVerifiedFact(fact: AuthoritativeGenerationFact): List<ConvergenceEffect> {
        val effects = mutableListOf<ConvergenceEffect>()
        val accepted = factStore.current()

        // M2: stale Fact against already-accepted Current
        if (accepted != null &&
            GenerationClassifier.isProvenStaleAgainstAccepted(fact, accepted)
        ) {
            lastRelation = FactRelation.UNKNOWN
            return effects
        }

        // M1/M3: identical Fact already retained → idempotent
        val existing = factStore.get(fact.factIdentity)
        if (existing != null && existing == fact && accepted?.factIdentity == fact.factIdentity) {
            lastRelation = FactRelation.SAME
            maybeCloseObligation(effects)
            return effects
        }

        val knowledgeSnapshot = localKnowledge
        val relation = GenerationClassifier.classify(knowledgeSnapshot, fact)
        lastRelation = relation

        when (knowledgeSnapshot) {
            is LocalGenerationKnowledge.Unknown -> {
                // Cold-start Fact adoption: establish Current knowledge without inventing F1 state.
                if (fact.attestsCurrent) {
                    factStore.retain(fact)
                    localKnowledge =
                        LocalGenerationKnowledge.Known(
                            generationIdentity = fact.generationIdentity,
                            treatedAsCurrent = true,
                        )
                    effects +=
                        ConvergenceEffect.RealignToCurrent(
                            channelId,
                            fact.generationIdentity,
                        )
                    maybeCloseObligation(effects)
                }
            }
            is LocalGenerationKnowledge.Known -> {
                when (relation) {
                    FactRelation.SAME -> {
                        factStore.retain(fact)
                        localKnowledge =
                            LocalGenerationKnowledge.Known(
                                generationIdentity = fact.generationIdentity,
                                treatedAsCurrent = true,
                            )
                        maybeCloseObligation(effects)
                    }
                    FactRelation.SUPERSEDED -> {
                        val localG = knowledgeSnapshot.generationIdentity
                        fencedGenerations.add(localG)
                        effects += ConvergenceEffect.FenceGeneration(channelId, localG)
                        // SUPERSEDED alone does not close (L3) — need usable Current.
                        if (fact.attestsCurrent) {
                            factStore.retain(fact)
                            localKnowledge =
                                LocalGenerationKnowledge.Known(
                                    generationIdentity = fact.generationIdentity,
                                    treatedAsCurrent = true,
                                )
                            effects +=
                                ConvergenceEffect.RealignToCurrent(
                                    channelId,
                                    fact.generationIdentity,
                                )
                            maybeCloseObligation(effects)
                        } else {
                            // Still need Current Fact
                            if (!obligations.isOpen()) {
                                obligations.open(channelId, "superseded_need_current")
                            }
                            effects += ConvergenceEffect.RequestFact(channelId)
                        }
                    }
                    FactRelation.UNKNOWN -> {
                        // Conflicting / insufficient order — keep UNKNOWN gating.
                        if (fact.attestsCurrent && accepted == null) {
                            if (!obligations.isOpen()) {
                                obligations.open(channelId, "insufficient_order")
                            }
                            effects += ConvergenceEffect.RequestFact(channelId)
                        }
                    }
                }
            }
        }
        return effects
    }

    /** L5: attempt terminal / no holder — obligation stays OPEN if unsatisfied. */
    fun onNoReachableHolderOrAttemptTerminal(): List<ConvergenceEffect> {
        obligations.onInsufficientOrAttemptTerminal()
        return if (obligations.isOpen()) {
            emptyList()
        } else if (!hasResolvedCurrentKnowledge()) {
            obligations.open(channelId, "no_holder_unsatisfied")
            emptyList()
        } else {
            emptyList()
        }
    }

    fun isGenerationFenced(generationIdentity: String): Boolean =
        generationIdentity in fencedGenerations

    fun isOriginateForbiddenWhileUnknown(): Boolean =
        localKnowledge is LocalGenerationKnowledge.Unknown ||
            (obligations.isOpen() && factStore.current() == null)

    private fun hasResolvedCurrentKnowledge(): Boolean {
        val cur = factStore.current() ?: return false
        val local = localKnowledge
        return local is LocalGenerationKnowledge.Known &&
            local.generationIdentity == cur.generationIdentity &&
            local.treatedAsCurrent
    }

    private fun maybeCloseObligation(effects: MutableList<ConvergenceEffect>) {
        if (!hasResolvedCurrentKnowledge()) return
        if (!obligations.isOpen()) return
        obligations.closeIfOpen()
        effects += ConvergenceEffect.ObligationClosed(channelId)
    }
}

sealed class FactResponseOutcome {
    data class Fact(val fact: AuthoritativeGenerationFact) : FactResponseOutcome()

    data object Insufficient : FactResponseOutcome()
}

package com.talkback.core.session.gbc

import java.util.concurrent.ConcurrentHashMap

/**
 * C-R1 Coordinator adapter: observation in → GBC decision → effect out.
 *
 * MUST NOT own Fact truth / F1 / obligation. Callers MUST NOT mutate generation
 * authority runtime state before [dispatch] returns effects from GBC.
 *
 * Wire/crypto/timer NOT authorized — [acceptVerifiedFact] is post-verification only.
 */
class GroupBootstrapConvergenceWiring(
    private val effectSink: (List<ConvergenceEffect>) -> Unit = {},
) {
    private val modules = ConcurrentHashMap<String, GroupBootstrapConvergence>()
    private val lastDispatchedEffects = ConcurrentHashMap<String, List<ConvergenceEffect>>()

    fun convergence(channelId: String): GroupBootstrapConvergence =
        modules.getOrPut(channelId) { GroupBootstrapConvergence(channelId) }

    fun snapshot(channelId: String): ConvergenceSnapshot = convergence(channelId).snapshot()

    fun lastEffects(channelId: String): List<ConvergenceEffect> =
        lastDispatchedEffects[channelId].orEmpty()

    /**
     * Local treated-as-Current observation only.
     * [generationIdentity] MUST NOT be sessionId / sessionLineageId / ICE proxy.
     */
    fun observeLocalGeneration(
        channelId: String,
        generationIdentity: String?,
        treatedAsCurrent: Boolean,
    ): List<ConvergenceEffect> =
        dispatch(
            channelId,
            convergence(channelId).onLocalGenerationObserved(generationIdentity, treatedAsCurrent),
        )

    /**
     * Open acquisition when unresolved. Idempotent while already OPEN (no tick spam).
     */
    fun observeAcquisitionIfUnresolved(
        channelId: String,
        reason: String,
    ): List<ConvergenceEffect> {
        val snap = snapshot(channelId)
        if (snap.acceptedCurrent != null && snap.obligation == ObligationState.CLOSED) {
            return emptyList()
        }
        if (snap.obligation == ObligationState.OPEN) {
            return emptyList()
        }
        return dispatch(channelId, convergence(channelId).onAcquisitionRequired(reason))
    }

    fun observeNoReachableHolder(channelId: String): List<ConvergenceEffect> =
        dispatch(channelId, convergence(channelId).onNoReachableHolderOrAttemptTerminal())

    /** Authorized post-verification Fact ingress (fixture / synthetic seam). */
    fun acceptVerifiedFact(
        channelId: String,
        fact: AuthoritativeGenerationFact,
    ): List<ConvergenceEffect> =
        dispatch(channelId, convergence(channelId).acceptVerifiedFact(fact))

    fun onFactResponse(
        channelId: String,
        correlation: String?,
        outcome: FactResponseOutcome,
        admissiblePath: Boolean,
    ): List<ConvergenceEffect> =
        dispatch(
            channelId,
            convergence(channelId).onFactResponse(correlation, outcome, admissiblePath),
        )

    fun beginAcquisitionExchange(channelId: String, correlation: String) {
        convergence(channelId).beginAcquisitionExchange(correlation)
    }

    fun expireAcquisitionExchange(channelId: String, correlation: String) {
        convergence(channelId).expireAcquisitionExchange(correlation)
    }

    /**
     * GBC decides first; only then notify the Coordinator effect sink.
     * Sink MUST execute downstream actions — MUST NOT invent F1/obligation/Current.
     */
    private fun dispatch(
        channelId: String,
        effects: List<ConvergenceEffect>,
    ): List<ConvergenceEffect> {
        lastDispatchedEffects[channelId] = effects
        if (effects.isNotEmpty()) {
            effectSink(effects)
        }
        return effects
    }
}

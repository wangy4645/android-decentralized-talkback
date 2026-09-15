package com.talkback.core.session.gbc

import java.util.concurrent.ConcurrentHashMap

/**
 * ADR-0057 Delivery + Verification orchestration.
 *
 * Authority path only:
 * candidate → VerificationBoundary → verified Fact → GBC wiring → effects
 *
 * Wire binding (FACT_*) is Coordinator-owned; this Module stays semantic.
 */
class FactDeliveryOrchestrator(
    private val wiring: GroupBootstrapConvergenceWiring,
    private val verificationBoundary: VerificationBoundary,
) {
    private val locatedHolders = ConcurrentHashMap<String, LinkedHashSet<String>>()
    private val pendingAcquisitions = ConcurrentHashMap<String, String>()
    private val wireAttemptedHolders = ConcurrentHashMap<String, LinkedHashSet<String>>()
    private val lastVerificationByChannel = ConcurrentHashMap<String, VerificationOutcome>()
    private val verificationMaterialByDigest = ConcurrentHashMap<String, String>()

    fun lastVerification(channelId: String): VerificationOutcome? =
        lastVerificationByChannel[channelId]

    fun locatedHolders(channelId: String): Set<String> =
        locatedHolders[channelId]?.toSet().orEmpty()

    fun pendingCorrelation(channelId: String): String? = pendingAcquisitions[channelId]

    fun wireAttemptedHolders(channelId: String, correlation: String): Set<String> =
        wireAttemptedHolders[attemptKey(channelId, correlation)]?.toSet().orEmpty()

    /**
     * V2.1: HELLO locate only — never promotes authority.
     *
     * @return true when [holderModuleId] was newly added to the locate set.
     */
    fun onHelloLocate(
        channelId: String,
        holderModuleId: String,
    ): Boolean {
        if (channelId.isBlank() || holderModuleId.isBlank()) return false
        val holders = locatedHolders.getOrPut(channelId) { linkedSetOf() }
        return holders.add(holderModuleId)
    }

    /**
     * ACQ-IA-T1: plan wire attempt for an OPEN obligation (RequestFact opportunity).
     * Does not open/close obligation — GBC already decided.
     */
    fun planWireAttemptForRequestFact(
        channelId: String,
        correlation: String,
        localModuleId: String,
    ): AcquisitionWirePlan {
        ensureAcquisitionExchange(channelId, correlation)
        return planWireAttempt(channelId, correlation, localModuleId)
    }

    /**
     * ACQ send-on-locate: new holder availability may create a new wire opportunity
     * while obligation remains OPEN.
     */
    fun planWireAttemptAfterHelloLocate(
        channelId: String,
        holderModuleId: String,
        localModuleId: String,
    ): AcquisitionWirePlan {
        if (!onHelloLocate(channelId, holderModuleId)) {
            return AcquisitionWirePlan.NoNewHolderLocate
        }
        val correlation = pendingAcquisitions[channelId] ?: defaultCorrelation(channelId)
        return planWireAttempt(channelId, correlation, localModuleId)
    }

    fun markHoldersWireAttempted(
        channelId: String,
        correlation: String,
        holderModuleIds: Collection<String>,
    ) {
        if (holderModuleIds.isEmpty()) return
        val attempted =
            wireAttemptedHolders.getOrPut(attemptKey(channelId, correlation)) { linkedSetOf() }
        attempted.addAll(holderModuleIds)
    }

    /**
     * V2.2 legacy schedule API — prefer [planWireAttemptForRequestFact].
     */
    fun scheduleAcquisition(
        channelId: String,
        correlation: String,
        reason: String = "request_fact",
    ): FactAcquisitionSchedule {
        ensureAcquisitionExchange(channelId, correlation)
        wiring.observeAcquisitionIfUnresolved(channelId, reason)
        val holders = eligibleHoldersForWire(channelId, correlation, localModuleId = null)
        return FactAcquisitionSchedule(
            channelId = channelId,
            correlation = correlation,
            selectedHolders = holders,
        )
    }

    /**
     * Inbound RESPONSE candidate path.
     * Verifies locally (V3.4) then enters GBC only on SUCCESS.
     */
    fun onCandidateResponse(
        channelId: String,
        correlation: String?,
        candidate: GenerationFactCandidate,
        admissiblePath: Boolean,
    ): FactDeliveryResult {
        if (!admissiblePath) {
            return FactDeliveryResult.IgnoredExchangeBookkeeping
        }
        val outcome = verificationBoundary.admit(candidate)
        lastVerificationByChannel[channelId] = outcome
        return when (outcome) {
            is VerificationOutcome.Success -> {
                verificationMaterialByDigest[outcome.fact.semanticDigest] = candidate.opaqueMaterial
                val effects =
                    wiring.onFactResponse(
                        channelId = channelId,
                        correlation = correlation,
                        outcome = FactResponseOutcome.Fact(outcome.fact),
                        admissiblePath = true,
                    )
                FactDeliveryResult.VerifiedAccepted(outcome.fact, effects)
            }
            else -> FactDeliveryResult.NotPromoted(outcome)
        }
    }

    fun onInsufficientResponse(
        channelId: String,
        correlation: String?,
        admissiblePath: Boolean,
    ): FactDeliveryResult {
        if (!admissiblePath) {
            return FactDeliveryResult.IgnoredExchangeBookkeeping
        }
        val effects =
            wiring.onFactResponse(
                channelId = channelId,
                correlation = correlation,
                outcome = FactResponseOutcome.Insufficient,
                admissiblePath = true,
            )
        return FactDeliveryResult.InsufficientHandled(effects)
    }

    /**
     * V3: authorized re-provide from retained Current only.
     * Returns opaque material retained at acceptance (S4) — empty if none.
     */
    fun onAuthorizedReprovideRequest(channelId: String): ReprovideResult {
        val fact = wiring.convergence(channelId).factStore().reProvideCurrent()
        return if (fact != null) {
            ReprovideResult.Fact(
                fact = fact,
                verificationMaterial = verificationMaterialByDigest[fact.semanticDigest].orEmpty(),
            )
        } else {
            ReprovideResult.Insufficient
        }
    }

    fun expireAcquisition(
        channelId: String,
        correlation: String,
    ) {
        wiring.expireAcquisitionExchange(channelId, correlation)
        if (pendingAcquisitions[channelId] == correlation) {
            pendingAcquisitions.remove(channelId)
        }
        wireAttemptedHolders.remove(attemptKey(channelId, correlation))
    }

    private fun planWireAttempt(
        channelId: String,
        correlation: String,
        localModuleId: String,
    ): AcquisitionWirePlan {
        if (wiring.snapshot(channelId).obligation != ObligationState.OPEN) {
            return AcquisitionWirePlan.ObligationClosed(channelId)
        }
        val holders = eligibleHoldersForWire(channelId, correlation, localModuleId)
        return if (holders.isEmpty()) {
            AcquisitionWirePlan.DeferredNoHolder(channelId, correlation)
        } else {
            AcquisitionWirePlan.Send(channelId, correlation, holders)
        }
    }

    private fun ensureAcquisitionExchange(channelId: String, correlation: String) {
        wiring.beginAcquisitionExchange(channelId, correlation)
        pendingAcquisitions[channelId] = correlation
    }

    private fun eligibleHoldersForWire(
        channelId: String,
        correlation: String,
        localModuleId: String?,
    ): List<String> {
        val attempted = wireAttemptedHolders[attemptKey(channelId, correlation)].orEmpty()
        return locatedHolders(channelId)
            .filter { holderId ->
                holderId.isNotBlank() &&
                    holderId != localModuleId &&
                    holderId !in attempted
            }
            .toList()
    }

    private fun attemptKey(channelId: String, correlation: String): String =
        "$channelId:$correlation"

    private fun defaultCorrelation(channelId: String): String = "gbc-acq-$channelId"
}

data class FactAcquisitionSchedule(
    val channelId: String,
    val correlation: String,
    val selectedHolders: List<String>,
)

sealed class FactDeliveryResult {
    data class VerifiedAccepted(
        val fact: AuthoritativeGenerationFact,
        val effects: List<ConvergenceEffect>,
    ) : FactDeliveryResult()

    data class NotPromoted(
        val outcome: VerificationOutcome,
    ) : FactDeliveryResult()

    data class InsufficientHandled(
        val effects: List<ConvergenceEffect>,
    ) : FactDeliveryResult()

    data object IgnoredExchangeBookkeeping : FactDeliveryResult()
}

sealed class ReprovideResult {
    data class Fact(
        val fact: AuthoritativeGenerationFact,
        val verificationMaterial: String = "",
    ) : ReprovideResult()

    data object Insufficient : ReprovideResult()
}

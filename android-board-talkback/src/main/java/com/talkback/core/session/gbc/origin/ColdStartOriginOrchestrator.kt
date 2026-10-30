package com.talkback.core.session.gbc.origin

import com.talkback.core.model.PredecessorWire
import com.talkback.core.session.gbc.FactDeliveryOrchestrator
import com.talkback.core.session.gbc.FactDeliveryResult
import com.talkback.core.session.gbc.GroupBootstrapConvergenceWiring
import com.talkback.core.session.gbc.VerificationBoundary
import com.talkback.core.session.gbc.crypto.GenerationFactCanonicalCodec
import com.talkback.core.session.gbc.crypto.SignedGenerationFactEnvelope
import com.talkback.core.session.gbc.issuance.FinalizeIssuanceResult
import com.talkback.core.session.gbc.issuance.FinalizedIssuanceRecord
import com.talkback.core.session.gbc.issuance.GenerationFactIssuanceKey
import com.talkback.core.session.gbc.issuance.GenerationFactIssuanceStore
import com.talkback.core.session.gbc.issuance.GenerationFactOriginEmissionSeam
import com.talkback.core.session.gbc.issuance.PrepareIssuanceResult
import com.talkback.core.session.gbc.issuance.PublishAdmissionResult
import java.util.concurrent.ConcurrentHashMap

/**
 * CSO production bridge: bootstrap candidate + no legal Current → PV-2 genesis → GBC retain.
 */
class ColdStartOriginOrchestrator(
    private val wiring: GroupBootstrapConvergenceWiring,
    private val factDeliveryOrchestrator: FactDeliveryOrchestrator,
    private val verificationBoundary: VerificationBoundary,
    private val originEmission: GenerationFactOriginEmissionSeam,
    private val issuanceStore: GenerationFactIssuanceStore,
    private val issuanceKey: GenerationFactIssuanceKey,
    private val genesisAllocator: ChannelGenesisGenerationIdentityAllocator,
    private val trustBindingRevision: Long,
) {
    private val channelLocks = ConcurrentHashMap<String, Any>()

    fun evaluateColdStartOriginEligibility(
        channelId: String,
        bootstrapCandidateModuleId: String,
        localModuleId: String,
    ): ColdStartOriginEligibility {
        if (bootstrapCandidateModuleId != localModuleId) {
            return ColdStartOriginEligibility.NotEligible.NotBootstrapCandidate
        }
        val snap = wiring.snapshot(channelId)
        if (snap.acceptedCurrent != null) {
            return ColdStartOriginEligibility.NotEligible.LegalCurrentExists
        }
        return ColdStartOriginEligibility.Eligible
    }

    fun attemptColdStartOriginIfEligible(
        channelId: String,
        bootstrapCandidateModuleId: String,
        localModuleId: String,
    ): ColdStartOriginAttemptResult {
        val lock = channelLocks.getOrPut(channelId) { Any() }
        synchronized(lock) {
            when (
                val eligibility =
                    evaluateColdStartOriginEligibility(
                        channelId,
                        bootstrapCandidateModuleId,
                        localModuleId,
                    )
            ) {
                is ColdStartOriginEligibility.NotEligible ->
                    return ColdStartOriginAttemptResult.Skipped(eligibility)
                ColdStartOriginEligibility.Eligible -> Unit
            }

            val genesisIdentity = genesisAllocator.allocateOrLoad(channelId)
            findFinalizedGenesis(genesisIdentity)?.let { finalized ->
                if (wiring.snapshot(channelId).acceptedCurrent != null) {
                    return ColdStartOriginAttemptResult.Skipped(
                        ColdStartOriginEligibility.NotEligible.LegalCurrentExists,
                    )
                }
                return restoreFromFinalized(channelId, genesisIdentity, finalized)
            }

            val authority =
                GenerationFactCanonicalCodec.AuthoritySemantics(
                    generationIdentity = genesisIdentity,
                    predecessor = PredecessorWire.None,
                    originAuthorityIdentity = localModuleId,
                    attestsCurrent = true,
                )
            val context =
                GenerationFactCanonicalCodec.VerificationContext(
                    originAuthorityIdentity = localModuleId,
                    signerKeyVersion = issuanceKey.signerKeyVersion,
                    trustBindingRevision = trustBindingRevision,
                )

            when (
                val prepared =
                    originEmission.prepareIssuance(issuanceKey, authority, context)
            ) {
                is PrepareIssuanceResult.Prepared -> {
                    val finalized =
                        when (
                            val result =
                                originEmission.signAndFinalize(issuanceKey, prepared.prepareId)
                        ) {
                            is FinalizeIssuanceResult.Finalized -> result
                            else -> return ColdStartOriginAttemptResult.Failed("finalize")
                        }

                    val admitted =
                        when (
                            val publish =
                                originEmission.admitFirstPublish(
                                    issuanceKey,
                                    finalized.record.factCommitment,
                                )
                        ) {
                            is PublishAdmissionResult.Admitted -> publish
                            else -> return ColdStartOriginAttemptResult.Failed("publish")
                        }

                    return acceptIssuance(
                        channelId = channelId,
                        genesisIdentity = genesisIdentity,
                        signedFactBytes = admitted.signedFactBytes,
                        resultKind = ColdStartOriginAttemptResult::Issued,
                    )
                }
                else -> return ColdStartOriginAttemptResult.Failed("prepare")
            }
        }
    }

    fun restoreColdStartOriginIfNeeded(
        channelId: String,
        bootstrapCandidateModuleId: String,
        localModuleId: String,
    ): ColdStartOriginAttemptResult? {
        if (bootstrapCandidateModuleId != localModuleId) return null
        if (wiring.snapshot(channelId).acceptedCurrent != null) return null
        val genesisIdentity = genesisAllocator.peek(channelId) ?: return null
        val finalized = findFinalizedGenesis(genesisIdentity) ?: return null
        val lock = channelLocks.getOrPut(channelId) { Any() }
        synchronized(lock) {
            if (wiring.snapshot(channelId).acceptedCurrent != null) return null
            return restoreFromFinalized(channelId, genesisIdentity, finalized)
        }
    }

    private fun restoreFromFinalized(
        channelId: String,
        genesisIdentity: String,
        finalized: FinalizedIssuanceRecord,
    ): ColdStartOriginAttemptResult {
        return acceptIssuance(
            channelId = channelId,
            genesisIdentity = genesisIdentity,
            signedFactBytes = finalized.signedFactBytes,
            resultKind = ColdStartOriginAttemptResult::Restored,
        )
    }

    private fun acceptIssuance(
        channelId: String,
        genesisIdentity: String,
        signedFactBytes: ByteArray,
        resultKind: (String, String, List<com.talkback.core.session.gbc.ConvergenceEffect>) -> ColdStartOriginAttemptResult,
    ): ColdStartOriginAttemptResult {
        val correlation = factDeliveryOrchestrator.pendingCorrelation(channelId)
        val acceptance =
            AuthorizedLocalOriginAcceptance.acceptFinalizedIssuance(
                channelId = channelId,
                signedFactBytes = signedFactBytes,
                verificationBoundary = verificationBoundary,
                factDeliveryOrchestrator = factDeliveryOrchestrator,
                correlation = correlation,
            )
        return when (acceptance.delivery) {
            is FactDeliveryResult.VerifiedAccepted ->
                resultKind(channelId, genesisIdentity, acceptance.effects)
            else -> ColdStartOriginAttemptResult.Failed("acceptance")
        }
    }

    private fun findFinalizedGenesis(generationIdentity: String): FinalizedIssuanceRecord? =
        issuanceStore.finalizedRecords(issuanceKey).firstOrNull { record ->
            val envelope =
                SignedGenerationFactEnvelope.parseSignedFactBytes(record.signedFactBytes)
                    ?: return@firstOrNull false
            val authority =
                GenerationFactCanonicalCodec.decodeAuthority(envelope.authorityCanonicalBytes)
                    ?: return@firstOrNull false
            authority.generationIdentity == generationIdentity
        }
}

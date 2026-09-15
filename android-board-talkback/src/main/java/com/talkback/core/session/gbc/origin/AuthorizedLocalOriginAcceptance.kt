package com.talkback.core.session.gbc.origin

import com.talkback.core.model.PredecessorWire
import com.talkback.core.session.gbc.FactDeliveryOrchestrator
import com.talkback.core.session.gbc.FactDeliveryResult
import com.talkback.core.session.gbc.GenerationFactCandidate
import com.talkback.core.session.gbc.GroupBootstrapConvergenceWiring
import com.talkback.core.session.gbc.VerificationBoundary
import com.talkback.core.session.gbc.VerificationOutcome
import com.talkback.core.session.gbc.crypto.GenerationFactCanonicalCodec
import com.talkback.core.session.gbc.crypto.GenerationFactVerificationBundle
import com.talkback.core.session.gbc.crypto.SignedGenerationFactEnvelope

/**
 * CSO-IA-T4: PV-2 finalized issuance enters GBC through the same verifier semantics
 * as remote candidates — not a naked FactStore write.
 */
object AuthorizedLocalOriginAcceptance {
    fun acceptFinalizedIssuance(
        channelId: String,
        signedFactBytes: ByteArray,
        verificationBoundary: VerificationBoundary,
        factDeliveryOrchestrator: FactDeliveryOrchestrator,
        correlation: String? = null,
    ): LocalOriginAcceptanceResult {
        val envelope =
            SignedGenerationFactEnvelope.parseSignedFactBytes(signedFactBytes)
                ?: return failed(FactDeliveryResult.NotPromoted(VerificationOutcome.Malformed))

        val authority =
            GenerationFactCanonicalCodec.decodeAuthority(envelope.authorityCanonicalBytes)
                ?: return failed(FactDeliveryResult.NotPromoted(VerificationOutcome.Malformed))

        val digestHex =
            GenerationFactCanonicalCodec.semanticDigestHex(envelope.authorityCanonicalBytes)
        val predecessor =
            when (val p = authority.predecessor) {
                PredecessorWire.Absent,
                PredecessorWire.None,
                -> null
                is PredecessorWire.Id -> p.generationIdentity
            }
        val bundle = GenerationFactVerificationBundle(signedFactBytes)
        val candidate =
            GenerationFactCandidate(
                claimedFactIdentity = digestHex,
                claimedGenerationIdentity = authority.generationIdentity,
                claimedPredecessorGenerationIdentity = predecessor,
                claimedOriginAuthorityIdentity = authority.originAuthorityIdentity,
                claimedAttestsCurrent = authority.attestsCurrent,
                claimedSemanticDigest = digestHex,
                opaqueMaterial = bundle.encodeToVerificationMaterial(),
            )

        val delivery =
            factDeliveryOrchestrator.onCandidateResponse(
                channelId = channelId,
                correlation = correlation,
                candidate = candidate,
                admissiblePath = true,
            )
        val effects =
            when (delivery) {
                is FactDeliveryResult.VerifiedAccepted -> delivery.effects
                is FactDeliveryResult.InsufficientHandled -> delivery.effects
                else -> emptyList()
            }
        return LocalOriginAcceptanceResult(delivery = delivery, effects = effects)
    }

    private fun failed(delivery: FactDeliveryResult): LocalOriginAcceptanceResult =
        LocalOriginAcceptanceResult(delivery = delivery, effects = emptyList())
}

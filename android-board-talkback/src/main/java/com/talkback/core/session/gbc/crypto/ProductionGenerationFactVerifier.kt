package com.talkback.core.session.gbc.crypto

import com.talkback.core.model.PredecessorWire
import com.talkback.core.session.gbc.AuthoritativeGenerationFact
import com.talkback.core.session.gbc.GenerationFactCandidate
import com.talkback.core.session.gbc.GenerationFactVerifier
import com.talkback.core.session.gbc.VerificationOutcome
import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.GenerationFactTrustLookup
import com.talkback.core.session.gbc.trust.GenerationFactTrustLookupResult
import java.security.PublicKey

/**
 * Production cryptographic verification engine (PV-1).
 * Terminal responsibility ends at admissible evidence (T6).
 */
class ProductionGenerationFactVerifier(
    private val trustLookup: GenerationFactTrustLookup,
) : GenerationFactVerifier {
    override fun verify(candidate: GenerationFactCandidate): VerificationOutcome {
        val bundle =
            GenerationFactVerificationBundle.decodeFromVerificationMaterial(candidate.opaqueMaterial)
                ?: return VerificationOutcome.Malformed

        val envelope =
            SignedGenerationFactEnvelope.parseSignedFactBytes(bundle.signedFactBytes)
                ?: return VerificationOutcome.Malformed

        val authority =
            GenerationFactCanonicalCodec.decodeAuthority(envelope.authorityCanonicalBytes)
                ?: return VerificationOutcome.Malformed

        val context =
            GenerationFactCanonicalCodec.decodeVerificationContext(envelope.verificationContextBytes)
                ?: return VerificationOutcome.Malformed

        val expectedDigestHex =
            GenerationFactCanonicalCodec.semanticDigestHex(envelope.authorityCanonicalBytes)
        if (expectedDigestHex != candidate.claimedSemanticDigest.lowercase()) {
            return VerificationOutcome.IntegrityAnomaly
        }

        if (authority.generationIdentity != candidate.claimedGenerationIdentity) {
            return VerificationOutcome.IntegrityAnomaly
        }
        if (authority.originAuthorityIdentity != candidate.claimedOriginAuthorityIdentity) {
            return VerificationOutcome.IntegrityAnomaly
        }
        if (authority.attestsCurrent != candidate.claimedAttestsCurrent) {
            return VerificationOutcome.IntegrityAnomaly
        }
        if (context.originAuthorityIdentity != candidate.claimedOriginAuthorityIdentity) {
            return VerificationOutcome.IntegrityAnomaly
        }
        if (!predecessorMatchesClaim(authority.predecessor, candidate.claimedPredecessorGenerationIdentity)) {
            return VerificationOutcome.IntegrityAnomaly
        }

        val trust =
            trustLookup.lookupBinding(
                moduleId = context.originAuthorityIdentity,
                signerKeyVersion = context.signerKeyVersion,
                trustBindingRevision = context.trustBindingRevision,
            )
        val binding =
            when (trust) {
                is GenerationFactTrustLookupResult.Found -> trust.binding
                GenerationFactTrustLookupResult.UnknownModule,
                GenerationFactTrustLookupResult.UnknownKeyVersion,
                -> return VerificationOutcome.Unverifiable
                GenerationFactTrustLookupResult.InvalidTrustBindingRevision ->
                    return VerificationOutcome.VerifyFail
            }

        if (binding.moduleId != context.originAuthorityIdentity ||
            binding.signerKeyVersion != context.signerKeyVersion
        ) {
            return VerificationOutcome.VerifyFail
        }

        when (binding.keyState) {
            GenerationFactKeyState.REVOKED,
            GenerationFactKeyState.RETIRED_OFF,
            GenerationFactKeyState.PREPARED,
            -> return VerificationOutcome.VerifyFail
            GenerationFactKeyState.ACTIVE -> Unit
            GenerationFactKeyState.RETIRED_VERIFY -> {
                val checkpoint =
                    trustLookup.retirementCheckpoint(
                        binding.moduleId,
                        binding.signerKeyVersion,
                    ) ?: return VerificationOutcome.Unverifiable
                val commitment =
                    GenerationFactCanonicalCodec.commitmentFromSemanticDigestHex(expectedDigestHex)
                        ?: return VerificationOutcome.Malformed
                val proof = bundle.historicalInclusionProof
                    ?: return VerificationOutcome.VerifyFail
                if (!GenerationFactInclusionProofVerifier.verify(
                        commitment,
                        checkpoint.issuanceRoot,
                        proof,
                    )
                ) {
                    return VerificationOutcome.VerifyFail
                }
            }
        }

        val publicKey: PublicKey =
            EcdsaP256Verifier.publicKeyFromSpki(binding.publicKeySpki)
                ?: return VerificationOutcome.VerifyFail

        val message =
            GenerationFactCanonicalCodec.signatureInput(
                envelope.authorityCanonicalBytes,
                envelope.verificationContextBytes,
            )
        if (!EcdsaP256Verifier.verify(message, envelope.signatureRs, publicKey)) {
            return VerificationOutcome.VerifyFail
        }

        return VerificationOutcome.Success(
            AuthoritativeGenerationFact(
                generationIdentity = authority.generationIdentity,
                predecessorGenerationIdentity = predecessorToOptional(authority.predecessor),
                originAuthorityIdentity = authority.originAuthorityIdentity,
                attestsCurrent = authority.attestsCurrent,
                semanticDigest = expectedDigestHex,
                resolvesConflictSet = authority.resolvesConflictSet,
            ),
        )
    }

    private fun predecessorMatchesClaim(
        predecessor: PredecessorWire,
        claimed: String?,
    ): Boolean =
        when (predecessor) {
            PredecessorWire.Absent,
            PredecessorWire.None,
            -> claimed == null
            is PredecessorWire.Id -> claimed == predecessor.generationIdentity
        }

    private fun predecessorToOptional(predecessor: PredecessorWire): String? =
        when (predecessor) {
            PredecessorWire.Absent,
            PredecessorWire.None,
            -> null
            is PredecessorWire.Id -> predecessor.generationIdentity
        }
}

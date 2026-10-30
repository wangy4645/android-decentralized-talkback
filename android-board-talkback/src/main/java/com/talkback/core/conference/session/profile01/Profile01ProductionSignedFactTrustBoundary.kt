package com.talkback.core.conference.session.profile01

import com.talkback.core.conference.session.profile01.wire.Profile01FactDigest
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactEnvelope
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactVerifier
import com.talkback.core.conference.session.profile01.wire.Profile01WireConstants

/**
 * Production Profile 01 signed-fact trust boundary (ECDSA + module trust lookup).
 *
 * Verifies signature and digest only — no lifecycle or registry policy.
 */
class Profile01ProductionSignedFactTrustBoundary(
    private val trustLookup: Profile01ModuleTrustLookup,
) : Profile01WireTrustBoundary {
    override fun verifySignedFact(signedFactBytes: ByteArray): Profile01WireVerificationResult {
        val envelope =
            Profile01SignedFactEnvelope.parse(signedFactBytes)
                ?: return Profile01WireVerificationResult.Rejected("MALFORMED_SIGNED_FACT")
        val fullFact =
            runCatching {
                com.talkback.core.conference.session.profile01.wire.Profile01CborCodec.decodeStrict(
                    envelope.fullCanonicalBytes,
                )
            }.getOrElse {
                return Profile01WireVerificationResult.Rejected("NON_CANONICAL_FACT")
            }
        val map = fullFact.intKeyMap() ?: return Profile01WireVerificationResult.Rejected("SCHEMA_INVALID")
        val factType = map[1]?.asUnsigned()?.toInt() ?: return Profile01WireVerificationResult.Rejected("SCHEMA_INVALID")
        val authority = map[2]?.intKeyMap() ?: return Profile01WireVerificationResult.Rejected("SCHEMA_INVALID")
        val metadata = map[3]?.intKeyMap() ?: return Profile01WireVerificationResult.Rejected("SCHEMA_INVALID")
        val signerKeyVersion = metadata[0]?.asUnsigned() ?: return Profile01WireVerificationResult.Rejected("SCHEMA_INVALID")
        val signerModuleId =
            when (factType) {
                Profile01WireConstants.FACT_TYPE_CREATION,
                Profile01WireConstants.FACT_TYPE_MEMBERSHIP,
                Profile01WireConstants.FACT_TYPE_MEDIA_KEY_PACKAGE,
                ->
                    authority[Profile01WireConstants.SIGNER_KEY_CREATION]?.asText()
                Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION ->
                    authority[Profile01WireConstants.SIGNER_KEY_SOURCE_DECLARATION]?.asText()
                else -> null
            } ?: return Profile01WireVerificationResult.Rejected("SCHEMA_INVALID")
        val trust =
            trustLookup.lookupSigningKey(signerModuleId, signerKeyVersion)
        val publicKeySpki =
            when (trust) {
                is Profile01ModuleTrustLookupResult.Found -> trust.publicKeySpki
                Profile01ModuleTrustLookupResult.UnknownModule,
                Profile01ModuleTrustLookupResult.UnknownKeyVersion,
                -> return Profile01WireVerificationResult.Rejected("UNKNOWN_SIGNER_KEY")
                Profile01ModuleTrustLookupResult.KeyNotVerifiable ->
                    return Profile01WireVerificationResult.Rejected("SIGNER_KEY_NOT_VERIFIABLE")
            }
        val verifyResult =
            Profile01SignedFactVerifier.verifySignature(
                envelope.fullCanonicalBytes,
                envelope.signatureRs,
                publicKeySpki,
            )
        if (verifyResult != "PASS") {
            return Profile01WireVerificationResult.Rejected(verifyResult)
        }
        val digest = Profile01FactDigest.computeFactDigest(envelope.fullCanonicalBytes)
        return Profile01WireVerificationResult.Verified(
            factDigest = digest.copyOf(),
            authenticatedSignerModuleId = signerModuleId,
        )
    }
}

/**
 * Golden-vector / harness trust boundary using Q4 public test keys (uncompressed SEC1).
 */
class Profile01GoldenVectorSignedFactTrustBoundary(
    private val keysByModuleAndVersion: Map<Pair<String, Long>, ByteArray>,
) : Profile01WireTrustBoundary {
    override fun verifySignedFact(signedFactBytes: ByteArray): Profile01WireVerificationResult {
        val envelope =
            Profile01SignedFactEnvelope.parse(signedFactBytes)
                ?: return Profile01WireVerificationResult.Rejected("MALFORMED_SIGNED_FACT")
        val fullFact =
            runCatching {
                com.talkback.core.conference.session.profile01.wire.Profile01CborCodec.decodeStrict(
                    envelope.fullCanonicalBytes,
                )
            }.getOrElse {
                return Profile01WireVerificationResult.Rejected("NON_CANONICAL_FACT")
            }
        val map = fullFact.intKeyMap() ?: return Profile01WireVerificationResult.Rejected("SCHEMA_INVALID")
        val factType = map[1]?.asUnsigned()?.toInt() ?: return Profile01WireVerificationResult.Rejected("SCHEMA_INVALID")
        val authority = map[2]?.intKeyMap() ?: return Profile01WireVerificationResult.Rejected("SCHEMA_INVALID")
        val metadata = map[3]?.intKeyMap() ?: return Profile01WireVerificationResult.Rejected("SCHEMA_INVALID")
        val signerKeyVersion = metadata[0]?.asUnsigned() ?: return Profile01WireVerificationResult.Rejected("SCHEMA_INVALID")
        val signerModuleId =
            when (factType) {
                Profile01WireConstants.FACT_TYPE_CREATION,
                Profile01WireConstants.FACT_TYPE_MEMBERSHIP,
                Profile01WireConstants.FACT_TYPE_MEDIA_KEY_PACKAGE,
                ->
                    authority[Profile01WireConstants.SIGNER_KEY_CREATION]?.asText()
                Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION ->
                    authority[Profile01WireConstants.SIGNER_KEY_SOURCE_DECLARATION]?.asText()
                else -> null
            } ?: return Profile01WireVerificationResult.Rejected("SCHEMA_INVALID")
        val publicKeyX963 =
            keysByModuleAndVersion[signerModuleId to signerKeyVersion]
                ?: return Profile01WireVerificationResult.Rejected("UNKNOWN_SIGNER_KEY")
        val verifyResult =
            Profile01SignedFactVerifier.verifySignatureWithX963(
                envelope.fullCanonicalBytes,
                envelope.signatureRs,
                publicKeyX963,
            )
        if (verifyResult != "PASS") {
            return Profile01WireVerificationResult.Rejected(verifyResult)
        }
        val digest = Profile01FactDigest.computeFactDigest(envelope.fullCanonicalBytes)
        return Profile01WireVerificationResult.Verified(
            factDigest = digest.copyOf(),
            authenticatedSignerModuleId = signerModuleId,
        )
    }
}

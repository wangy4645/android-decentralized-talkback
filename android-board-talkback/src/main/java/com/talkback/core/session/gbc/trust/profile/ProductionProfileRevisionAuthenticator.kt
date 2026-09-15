package com.talkback.core.session.gbc.trust.profile

import com.talkback.core.session.gbc.crypto.EcdsaP256Verifier

/**
 * Production Profile revision authenticator (PTI Layer B).
 *
 * Requires PAP-T2-established pinned anchor input and schema v2 protected semantics
 * with cryptographically bound [GenerationFactProfileTrustPayload.deploymentTrustDomainId].
 */
class ProductionProfileRevisionAuthenticator(
    private val anchorSource: OperationalTrustAnchorSource,
    private val expectedDeploymentTrustDomainId: String,
) : ProfileRevisionAuthenticator {
    init {
        require(expectedDeploymentTrustDomainId.isNotBlank()) {
            "expectedDeploymentTrustDomainId required"
        }
    }

    override fun authenticate(protectedBytes: ByteArray): ProfileAuthenticationResult {
        val candidateBytes = protectedBytes
        val envelope =
            AuthenticatedProfileRevisionEnvelope.parse(candidateBytes)
                ?: return ProfileAuthenticationResult.Rejected(
                    "production authentication requires signed envelope",
                )

        val protectedBytes = envelope.protectedBytes

        if (!ProfileRevisionCanonicalCodec.isProductionAuthenticationAdmissible(protectedBytes)) {
            return ProfileAuthenticationResult.Rejected(
                "protected schema without deploymentTrustDomainId is not production-admissible",
            )
        }

        val payload =
            ProfileRevisionCanonicalCodec.decode(protectedBytes)
                ?: return ProfileAuthenticationResult.Rejected("malformed protected payload")

        val signedScope = payload.deploymentTrustDomainId
            ?: return ProfileAuthenticationResult.Rejected("missing deploymentTrustDomainId")

        if (signedScope != expectedDeploymentTrustDomainId) {
            return ProfileAuthenticationResult.Rejected(
                "signed deploymentTrustDomainId does not match expected scope",
            )
        }

        val anchor =
            anchorSource.pinnedAnchor(expectedDeploymentTrustDomainId)
                ?: return ProfileAuthenticationResult.Rejected(
                    "no pinned operational anchor for expected deployment trust domain",
                )

        if (anchor.deploymentTrustDomainId != expectedDeploymentTrustDomainId) {
            return ProfileAuthenticationResult.Rejected(
                "pinned anchor not eligible for expected deployment trust domain",
            )
        }

        val publicKey =
            EcdsaP256Verifier.publicKeyFromSpki(anchor.operationalAuthorityPublicKeySpki)
                ?: return ProfileAuthenticationResult.Rejected("invalid operational authority public key")

        val message = AuthenticatedProfileRevisionEnvelope.signatureInput(protectedBytes)
        if (!EcdsaP256Verifier.verify(message, envelope.signatureRs, publicKey)) {
            return ProfileAuthenticationResult.Rejected("profile revision signature verification failed")
        }

        return ProfileAuthenticationResult.Authenticated
    }
}

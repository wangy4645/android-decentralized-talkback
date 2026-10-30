package com.talkback.core.session.gbc.trust.profile.establishment

import com.talkback.core.session.gbc.crypto.EcdsaP256Verifier
import com.talkback.core.session.gbc.trust.profile.AuthenticatedProfileRevisionEnvelope
import com.talkback.core.session.gbc.trust.profile.OperationalTrustAnchorSource
import com.talkback.core.session.gbc.trust.profile.ProfileAuthenticationResult

/**
 * Verifies PAP-authenticated establishment profile deliveries using the operational trust anchor.
 *
 * Reuses [AuthenticatedProfileRevisionEnvelope] — does not introduce a second signing protocol.
 */
class EstablishmentProfileOperationalAuthenticator(
    private val anchorSource: OperationalTrustAnchorSource,
    private val expectedDeploymentTrustDomainId: String,
) {
    init {
        require(expectedDeploymentTrustDomainId.isNotBlank()) {
            "expectedDeploymentTrustDomainId required"
        }
    }

    fun authenticate(candidateBytes: ByteArray): ProfileAuthenticationResult {
        val envelope =
            AuthenticatedProfileRevisionEnvelope.parse(candidateBytes)
                ?: return ProfileAuthenticationResult.Rejected(
                    "establishment authentication requires signed envelope",
                )
        val protectedBytes = envelope.protectedBytes
        val payload =
            EstablishmentProfileRevisionCanonicalCodec.decode(protectedBytes)
                ?: return ProfileAuthenticationResult.Rejected("malformed establishment protected payload")
        val signedScope =
            payload.deploymentTrustDomainId
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
            return ProfileAuthenticationResult.Rejected("establishment profile signature verification failed")
        }
        return ProfileAuthenticationResult.Authenticated
    }

    fun authenticatedRevision(candidateBytes: ByteArray): AuthenticatedEstablishmentProfileRevision? {
        if (authenticate(candidateBytes) != ProfileAuthenticationResult.Authenticated) return null
        val protected =
            AuthenticatedProfileRevisionEnvelope.parse(candidateBytes)?.protectedBytes
                ?: return null
        return AuthenticatedEstablishmentProfileRevision.fromPapAuthenticatedSemantics(protected)
    }
}

/**
 * PAP publication seam for establishment profile protected semantics.
 *
 * Production wiring supplies a real implementation; tests delegate to ADR-0057 Layer B harness signer.
 */
fun interface EstablishmentProfilePublicationSeam {
    fun publishProtectedSemantics(protectedSemanticBytes: ByteArray): ByteArray
}

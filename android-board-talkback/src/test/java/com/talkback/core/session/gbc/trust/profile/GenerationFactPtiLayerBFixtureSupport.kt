package com.talkback.core.session.gbc.trust.profile

import com.talkback.core.session.gbc.crypto.GenerationFactPv1FixtureSupport
import com.talkback.core.session.gbc.crypto.GenerationFactTestSigning
import com.talkback.core.session.gbc.trust.GenerationFactKeyState

object GenerationFactPtiLayerBFixtureSupport {
    const val TRUST_DOMAIN = "DEPLOYMENT-TRUST-DOMAIN-TEST-01"
    const val OTHER_TRUST_DOMAIN = "DEPLOYMENT-TRUST-DOMAIN-TEST-02"
    const val MODULE = GenerationFactPv1FixtureSupport.MODULE_M01
    const val KEY_VERSION = GenerationFactPv1FixtureSupport.KEY_VERSION
    const val ACTIVE_REVISION = GenerationFactPv1FixtureSupport.ACTIVE_REVISION

    fun establishedAnchor(): OperationalTrustAnchor =
        OperationalTrustAnchor(
            deploymentTrustDomainId = TRUST_DOMAIN,
            operationalAuthorityPublicKeySpki = OperationalProfileTestSigning.publicKeySpki.copyOf(),
        )

    fun anchorStore(): PinnedOperationalTrustAnchorStore =
        PinnedOperationalTrustAnchorStore(establishedAnchor())

    fun productionAuthenticator(
        anchorStore: PinnedOperationalTrustAnchorStore = anchorStore(),
        expectedDomain: String = TRUST_DOMAIN,
    ): ProductionProfileRevisionAuthenticator =
        ProductionProfileRevisionAuthenticator(anchorStore, expectedDomain)

    fun activeBindingPayloadV2(revision: Long = ACTIVE_REVISION): GenerationFactProfileTrustPayload =
        GenerationFactProfileTrustPayload(
            taskProfileRevision = revision,
            localModuleId = MODULE,
            moduleBindings =
                listOf(
                    ModuleSigningBinding(
                        moduleId = MODULE,
                        signerKeyVersion = KEY_VERSION,
                        keyState = GenerationFactKeyState.ACTIVE,
                        publicKeySpki = GenerationFactTestSigning.publicKeySpki,
                        activatedAtRevision = ACTIVE_REVISION,
                    ),
                ),
            deploymentTrustDomainId = TRUST_DOMAIN,
        )

    fun signedDelivery(payload: GenerationFactProfileTrustPayload): ByteArray =
        OperationalProfileTestSigning.signPayloadV2(payload)

    fun layerBHarness(
        anchorStore: PinnedOperationalTrustAnchorStore = anchorStore(),
    ): LayerBHarness {
        val trustStore = AcceptedLocalTrustStateStore()
        val authenticator = productionAuthenticator(anchorStore)
        val acceptor = ProfileRevisionAcceptor(authenticator, trustStore)
        val lookup = ProfileBackedGenerationFactTrustLookup(trustStore)
        return LayerBHarness(anchorStore, authenticator, trustStore, acceptor, lookup)
    }

    fun acceptSigned(
        harness: LayerBHarness,
        payload: GenerationFactProfileTrustPayload,
    ): ProfileRevisionAcceptResult =
        harness.acceptor.acceptProtectedRevision(signedDelivery(payload))

    data class LayerBHarness(
        val anchorStore: PinnedOperationalTrustAnchorStore,
        val authenticator: ProductionProfileRevisionAuthenticator,
        val store: AcceptedLocalTrustStateStore,
        val acceptor: ProfileRevisionAcceptor,
        val lookup: ProfileBackedGenerationFactTrustLookup,
    )
}

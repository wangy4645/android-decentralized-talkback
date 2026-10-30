package com.talkback.core.session.gbc.trust.profile.establishment

import com.talkback.core.conference.session.profile01.wire.Profile01EstablishmentAuthorityWiring
import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustState
import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustStateStore
import com.talkback.core.session.gbc.trust.profile.ModuleSigningBinding
import com.talkback.core.session.gbc.wiring.EstablishmentProductionWiringHarness
import com.talkback.core.session.gbc.trust.profile.GenerationFactPtiLayerBFixtureSupport
import org.junit.Assert.assertTrue
import org.junit.Test

class EstablishmentFieldProbeTest {
    @Test
    fun emitsEpProbeLines_withoutMutatingTrustState() {
        val establishmentStore = AcceptedLocalEstablishmentTrustStateStore()
        val signingStore = AcceptedLocalTrustStateStore()
        val keystoreStore = TestLocalEstablishmentKeystoreIdentityStore()
        val identity =
            keystoreStore.provisionFirstIdentity("M02", 4L) as LocalEstablishmentKeystoreProvisionResult.Created
        val deliveryAcceptor =
            EstablishmentProductionWiringHarness.deliveryAcceptorOnly(
                deploymentTrustDomainId = GenerationFactPtiLayerBFixtureSupport.TRUST_DOMAIN,
                anchorSource = GenerationFactPtiLayerBFixtureSupport.anchorStore(),
                store = establishmentStore,
            )
        val surface =
            Profile01EstablishmentAuthorityWiring.create(
                establishmentTrustStore = establishmentStore,
                localModuleId = "M02",
                keystoreIdentityStore = keystoreStore,
                establishmentProfileDeliveryAcceptor = deliveryAcceptor,
            )
        val payload =
            EstablishmentProfileRevisionFixtureSupport.duoPayload(peerVersion = 4L)
                .copy(
                    localModuleId = "M02",
                    deploymentTrustDomainId = GenerationFactPtiLayerBFixtureSupport.TRUST_DOMAIN,
                    establishmentBindings =
                        listOf(
                            EstablishmentProfileRevisionFixtureSupport.peerBinding(
                                moduleId = "M02",
                                establishmentKeyVersion = 4L,
                                establishmentPublicKeySpki = identity.identity.publicKeySpki,
                            ),
                            EstablishmentProfileRevisionFixtureSupport.peerBinding(
                                moduleId = "M01",
                                establishmentKeyVersion = 4L,
                            ),
                        ),
                )
        EstablishmentProfileRevisionAcceptor(establishmentStore)
            .acceptAuthenticatedRevision(
                AuthenticatedEstablishmentProfileRevision.forValidatedSemantics(payload),
            )
        signingStore.atomicReplace(
            AcceptedLocalTrustState(
                taskProfileRevision = 10L,
                revisionIdentity = ByteArray(32),
                localModuleId = "M02",
                bindingsByKey =
                    mapOf(
                        AcceptedLocalTrustState.BindingKey("M02", 1L) to
                            ModuleSigningBinding(
                                moduleId = "M02",
                                signerKeyVersion = 1L,
                                keyState = GenerationFactKeyState.ACTIVE,
                                publicKeySpki = byteArrayOf(0x01),
                                activatedAtRevision = 10L,
                            ),
                    ),
            ),
        )
        val session =
            EstablishmentFieldProbe.Session(
                deviceModuleId = "M02",
                establishmentAuthority = surface,
                signingTrustStore = signingStore,
            )
        val lines = mutableListOf<String>()
        val ep1 = EstablishmentFieldProbe.logEp1RemoteLookup(session, "M01", 4L, lines::add)
        val ep2 = EstablishmentFieldProbe.logEp2LocalPossession(session, lines::add)
        val ep4 = EstablishmentFieldProbe.logEp4IdentityDomainSeparation(session, lines::add)
        val ep5 = EstablishmentFieldProbe.logEp5NegativeFence(session, "M01", 999L, lines::add)
        assertTrue(ep1 is Ep1RemoteLookupOutcome.Found)
        assertTrue(ep2 is Ep2LocalPossessionOutcome.Verified)
        assertTrue(ep4 is Ep4IdentityDomainOutcome.Separated)
        assertTrue(ep5.storeBindingCountUnchanged)
        assertTrue(lines.all { it.startsWith(EstablishmentFieldProbe.LOG_PREFIX) })
    }
}

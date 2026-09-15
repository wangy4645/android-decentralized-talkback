package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustStateStore
import com.talkback.core.session.gbc.trust.profile.GenerationFactPtiLayerBFixtureSupport
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.TestLocalEstablishmentKeystoreIdentityStore
import com.talkback.core.session.gbc.wiring.EstablishmentProductionWiringHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-A exit tests: A1–A4 establishment authority (PR-1).
 */
class Profile01RecipientEstablishmentAuthorityTest {
    @Test
    fun a1_moduleIdToEstablishmentPublicKeyLookup_pass() {
        val establishmentStore = AcceptedLocalEstablishmentTrustStateStore()
        Profile01EstablishmentAuthorityTestFixtures.populateEstablishmentStore(establishmentStore)
        val lookup = Profile01ProfileBackedRecipientEstablishmentKeyLookup(establishmentStore)

        val result =
            lookup.lookupEstablishmentKey(
                Profile01EstablishmentAuthorityTestFixtures.PEER_MODULE_ID,
                Profile01EstablishmentAuthorityTestFixtures.ESTABLISHMENT_KEY_VERSION,
            )

        assertTrue(result is Profile01EstablishmentKeyLookupResult.Found)
        val found = result as Profile01EstablishmentKeyLookupResult.Found
        assertEquals(Profile01EstablishmentAuthorityTestFixtures.PEER_MODULE_ID, found.ref.recipientModuleId)
        assertEquals(
            Profile01EstablishmentAuthorityTestFixtures.ESTABLISHMENT_KEY_VERSION,
            found.ref.establishmentKeyVersion,
        )
        assertTrue(found.ref.establishmentPublicKeySpki.isNotEmpty())
    }

    @Test
    fun a2_establishmentKeyVersionIndependentFromSignerKeyVersion() {
        val signingStore = AcceptedLocalTrustStateStore()
        Profile01EstablishmentAuthorityTestFixtures.populateSigningStore(signingStore)

        val establishmentStore = AcceptedLocalEstablishmentTrustStateStore()
        Profile01EstablishmentAuthorityTestFixtures.populateEstablishmentStore(establishmentStore)

        val signerVersion =
            signingStore.currentSnapshot()
                ?.bindingsByKey
                ?.values
                ?.first()
                ?.signerKeyVersion
        val establishmentVersion =
            Profile01LocalEstablishmentIdentity.activeEstablishmentKeyVersion(
                establishmentStore,
                Profile01EstablishmentAuthorityTestFixtures.LOCAL_MODULE_ID,
            )

        assertEquals(Profile01EstablishmentAuthorityTestFixtures.SIGNER_KEY_VERSION, signerVersion)
        assertEquals(Profile01EstablishmentAuthorityTestFixtures.ESTABLISHMENT_KEY_VERSION, establishmentVersion)
        assertNotEquals(signerVersion, establishmentVersion)
    }

    @Test
    fun a3_unknownModuleOrVersion_failsWithoutSigningKeyFallback() {
        val signingStore = AcceptedLocalTrustStateStore()
        Profile01EstablishmentAuthorityTestFixtures.populateSigningStore(signingStore)

        val establishmentStore = AcceptedLocalEstablishmentTrustStateStore()
        val lookup = Profile01ProfileBackedRecipientEstablishmentKeyLookup(establishmentStore)

        val unknownModule =
            lookup.lookupEstablishmentKey(
                Profile01EstablishmentAuthorityTestFixtures.PEER_MODULE_ID,
                Profile01EstablishmentAuthorityTestFixtures.ESTABLISHMENT_KEY_VERSION,
            )
        assertEquals(Profile01EstablishmentKeyLookupResult.UnknownModule, unknownModule)

        Profile01EstablishmentAuthorityTestFixtures.populateEstablishmentStore(establishmentStore)
        val wrongVersion =
            lookup.lookupEstablishmentKey(
                Profile01EstablishmentAuthorityTestFixtures.PEER_MODULE_ID,
                Profile01EstablishmentAuthorityTestFixtures.ESTABLISHMENT_KEY_VERSION + 99,
            )
        assertEquals(Profile01EstablishmentKeyLookupResult.UnknownKeyVersion, wrongVersion)

        val signingSpki =
            signingStore.currentSnapshot()
                ?.bindingsByKey
                ?.values
                ?.first()
                ?.publicKeySpki
        val establishmentResult =
            lookup.lookupEstablishmentKey(
                Profile01EstablishmentAuthorityTestFixtures.PEER_MODULE_ID,
                Profile01EstablishmentAuthorityTestFixtures.ESTABLISHMENT_KEY_VERSION,
            ) as Profile01EstablishmentKeyLookupResult.Found
        assertNotEquals(signingSpki?.toList(), establishmentResult.ref.establishmentPublicKeySpki.toList())
    }

    @Test
    fun a4_productionWiringExposesAuthorityWithoutPackageEmit() {
        val keystoreStore = TestLocalEstablishmentKeystoreIdentityStore()
        val establishmentStore = AcceptedLocalEstablishmentTrustStateStore()
        val deliveryAcceptor =
            EstablishmentProductionWiringHarness.deliveryAcceptorOnly(
                deploymentTrustDomainId = GenerationFactPtiLayerBFixtureSupport.TRUST_DOMAIN,
                anchorSource = GenerationFactPtiLayerBFixtureSupport.anchorStore(),
                store = establishmentStore,
            )
        val surface =
            Profile01EstablishmentAuthorityWiring.create(
                establishmentTrustStore = establishmentStore,
                localModuleId = Profile01EstablishmentAuthorityTestFixtures.LOCAL_MODULE_ID,
                keystoreIdentityStore = keystoreStore,
                establishmentProfileDeliveryAcceptor = deliveryAcceptor,
            )
        Profile01EstablishmentAuthorityTestFixtures.populateEstablishmentStore(surface.establishmentTrustStore)

        val lookup =
            surface.recipientEstablishmentKeyLookup.lookupEstablishmentKey(
                Profile01EstablishmentAuthorityTestFixtures.PEER_MODULE_ID,
                Profile01EstablishmentAuthorityTestFixtures.ESTABLISHMENT_KEY_VERSION,
            )
        assertTrue(lookup is Profile01EstablishmentKeyLookupResult.Found)

        assertEquals(
            Profile01EstablishmentAuthorityTestFixtures.ESTABLISHMENT_KEY_VERSION,
            surface.localEstablishmentKeyVersion(),
        )
    }
}

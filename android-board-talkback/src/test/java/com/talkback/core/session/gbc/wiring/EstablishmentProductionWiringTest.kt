package com.talkback.core.session.gbc.wiring

import com.talkback.core.conference.session.profile01.wire.Profile01EstablishmentAuthorityTestFixtures
import com.talkback.core.conference.session.profile01.wire.Profile01EstablishmentKeyLookupResult
import com.talkback.core.conference.session.profile01.wire.Profile01LocalEstablishmentIdentityAvailability
import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustStateStore
import com.talkback.core.session.gbc.trust.profile.GenerationFactPtiLayerBFixtureSupport
import com.talkback.core.session.gbc.trust.profile.OperationalProfileTestSigning
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentProfileRevisionCanonicalCodec
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentProfileRevisionDeliveryAcceptResult
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentProfileRevisionFixtureSupport
import com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentProfileTrustPayload
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreProvisionResult
import com.talkback.core.session.gbc.trust.profile.establishment.Profile01LocalEstablishmentIdentitySnapshot
import com.talkback.core.session.gbc.trust.profile.establishment.TestLocalEstablishmentKeystoreIdentityStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * EP Production Wiring exit tests: W1–W7.
 */
class EstablishmentProductionWiringTest {
    private val keystoreStore = TestLocalEstablishmentKeystoreIdentityStore()
    private val anchorStore = GenerationFactPtiLayerBFixtureSupport.anchorStore()
    private val peerEstablishmentSpki: ByteArray =
        EstablishmentProfileRevisionFixtureSupport.generateRsa3072Spki()

    @Before
    fun setUp() {
        keystoreStore.clear()
    }

    @After
    fun tearDown() {
        keystoreStore.clear()
    }

    @Test
    fun w1_papAuthenticatedRevision_acceptorPopulatesProductionStore() {
        val runtime = harness()
        val signed = signedDuoPayload(localSpki = provisionLocalIdentity().publicKeySpki)

        val result = runtime.establishmentProfileDeliveryAcceptor.acceptSignedDelivery(signed)
        assertTrue(result is EstablishmentProfileRevisionDeliveryAcceptResult.Accepted)

        val snapshot = runtime.establishmentTrustStore.currentSnapshot()
        assertNotNull(snapshot)
        assertEquals(EstablishmentProfileRevisionFixtureSupport.ACTIVE_REVISION, snapshot?.taskProfileRevision)
        assertEquals(2, snapshot?.bindingsByKey?.size)
    }

    @Test
    fun w2_peerLookupThroughProductionSurface_returnsExpectedSpkiAndVersion() {
        val runtime = harness()
        acceptSigned(runtime, signedDuoPayload(localSpki = provisionLocalIdentity().publicKeySpki))
        val surface = runtime.authoritySurface

        val lookup =
            surface.recipientEstablishmentKeyLookup.lookupEstablishmentKey(
                EstablishmentProfileRevisionFixtureSupport.PEER_MODULE_ID,
                EstablishmentProfileRevisionFixtureSupport.ESTABLISHMENT_KEY_VERSION,
            )
        assertTrue(lookup is Profile01EstablishmentKeyLookupResult.Found)
        val found = lookup as Profile01EstablishmentKeyLookupResult.Found
        assertEquals(EstablishmentProfileRevisionFixtureSupport.PEER_MODULE_ID, found.ref.recipientModuleId)
        assertEquals(
            EstablishmentProfileRevisionFixtureSupport.ESTABLISHMENT_KEY_VERSION,
            found.ref.establishmentKeyVersion,
        )
        assertTrue(
            found.ref.establishmentPublicKeySpki.contentEquals(peerEstablishmentSpki),
        )
    }

    @Test
    fun w3_localProfileRowAndKeystore_verifiedOnProductionSurface() {
        val runtime = harness()
        val localIdentity = provisionLocalIdentity()
        acceptSigned(runtime, signedDuoPayload(localSpki = localIdentity.publicKeySpki))

        val availability = runtime.authoritySurface.verifiedLocalEstablishmentIdentity()
        assertTrue(availability is Profile01LocalEstablishmentIdentityAvailability.Verified)
        val verified = availability as Profile01LocalEstablishmentIdentityAvailability.Verified
        assertEquals(localIdentity.moduleId, verified.identity.moduleId)
        assertEquals(localIdentity.establishmentKeyVersion, verified.identity.establishmentKeyVersion)
        assertTrue(verified.identity.publicKeySpki.contentEquals(localIdentity.publicKeySpki))
    }

    @Test
    fun w4_signerKeyVersionIndependentFromEstablishmentKeyVersion() {
        val signingStore = AcceptedLocalTrustStateStore()
        Profile01EstablishmentAuthorityTestFixtures.populateSigningStore(signingStore)

        val runtime = harness()
        val localIdentity = provisionLocalIdentity()
        acceptSigned(runtime, signedDuoPayload(localSpki = localIdentity.publicKeySpki))

        val signerVersion =
            signingStore.currentSnapshot()
                ?.bindingsByKey
                ?.values
                ?.first()
                ?.signerKeyVersion
        val establishmentVersion = runtime.authoritySurface.localEstablishmentKeyVersion()

        assertEquals(Profile01EstablishmentAuthorityTestFixtures.SIGNER_KEY_VERSION, signerVersion)
        assertEquals(EstablishmentProfileRevisionFixtureSupport.ESTABLISHMENT_KEY_VERSION, establishmentVersion)
        assertNotEquals(signerVersion, establishmentVersion)
    }

    @Test
    fun w5_missingLocalIdentity_establishmentUnavailable_noFallback() {
        val runtime = harness()
        acceptSigned(runtime, signedDuoPayload(localSpki = provisionLocalIdentity().publicKeySpki))
        keystoreStore.clear()

        val availability = runtime.authoritySurface.verifiedLocalEstablishmentIdentity()
        assertTrue(availability is Profile01LocalEstablishmentIdentityAvailability.Unavailable)

        assertEquals(
            EstablishmentProfileRevisionFixtureSupport.ESTABLISHMENT_KEY_VERSION,
            runtime.authoritySurface.localEstablishmentKeyVersion(),
        )
        val peerLookup =
            runtime.authoritySurface.recipientEstablishmentKeyLookup.lookupEstablishmentKey(
                EstablishmentProfileRevisionFixtureSupport.PEER_MODULE_ID,
                EstablishmentProfileRevisionFixtureSupport.ESTABLISHMENT_KEY_VERSION,
            )
        assertTrue(peerLookup is Profile01EstablishmentKeyLookupResult.Found)
    }

    @Test
    fun w6_invalidUnauthenticatedProfile_zeroTrustMutation() {
        val runtime = harness()
        assertNull(runtime.establishmentTrustStore.currentSnapshot())

        val garbage = byteArrayOf(0x01, 0x02, 0x03)
        val result = runtime.establishmentProfileDeliveryAcceptor.acceptSignedDelivery(garbage)
        assertTrue(result is EstablishmentProfileRevisionDeliveryAcceptResult.AuthenticationRejected)
        assertNull(runtime.establishmentTrustStore.currentSnapshot())
    }

    @Test
    fun w7_recomposition_stableAuthorityState_noDuplicateProvisioning() {
        val stateFile = Files.createTempFile("ep-establishment-trust", ".bin")
        val sharedKeystore = TestLocalEstablishmentKeystoreIdentityStore()
        val localIdentity =
            sharedKeystore.provisionFirstIdentity(
                EstablishmentProfileRevisionFixtureSupport.LOCAL_MODULE_ID,
                EstablishmentProfileRevisionFixtureSupport.ESTABLISHMENT_KEY_VERSION,
            ) as LocalEstablishmentKeystoreProvisionResult.Created

        val runtime1 =
            EstablishmentProductionWiringHarness.create(
                deploymentTrustDomainId = GenerationFactPtiLayerBFixtureSupport.TRUST_DOMAIN,
                anchorSource = anchorStore,
                localModuleId = EstablishmentProfileRevisionFixtureSupport.LOCAL_MODULE_ID,
                keystoreStore = sharedKeystore,
                establishmentStateFile = stateFile,
            )
        acceptSigned(runtime1, signedDuoPayload(localSpki = localIdentity.identity.publicKeySpki))
        val snapshot1 = runtime1.establishmentTrustStore.currentSnapshot()
        assertNotNull(snapshot1)

        val runtime2 =
            EstablishmentProductionWiringHarness.create(
                deploymentTrustDomainId = GenerationFactPtiLayerBFixtureSupport.TRUST_DOMAIN,
                anchorSource = anchorStore,
                localModuleId = EstablishmentProfileRevisionFixtureSupport.LOCAL_MODULE_ID,
                keystoreStore = sharedKeystore,
                establishmentStateFile = stateFile,
            )
        val snapshot2 = runtime2.establishmentTrustStore.currentSnapshot()
        assertNotNull(snapshot2)
        assertEquals(snapshot1?.taskProfileRevision, snapshot2?.taskProfileRevision)
        assertEquals(snapshot1?.bindingsByKey?.size, snapshot2?.bindingsByKey?.size)
        assertTrue(
            runtime2.authoritySurface.verifiedLocalEstablishmentIdentity()
                is Profile01LocalEstablishmentIdentityAvailability.Verified,
        )

        Files.deleteIfExists(stateFile)
    }

    private fun harness(): EstablishmentProductionComposition.EstablishmentProductionRuntime =
        EstablishmentProductionWiringHarness.create(
            deploymentTrustDomainId = GenerationFactPtiLayerBFixtureSupport.TRUST_DOMAIN,
            anchorSource = anchorStore,
            localModuleId = EstablishmentProfileRevisionFixtureSupport.LOCAL_MODULE_ID,
            keystoreStore = keystoreStore,
        )

    private fun provisionLocalIdentity(): Profile01LocalEstablishmentIdentitySnapshot {
        val result =
            keystoreStore.provisionFirstIdentity(
                EstablishmentProfileRevisionFixtureSupport.LOCAL_MODULE_ID,
                EstablishmentProfileRevisionFixtureSupport.ESTABLISHMENT_KEY_VERSION,
            ) as LocalEstablishmentKeystoreProvisionResult.Created
        return result.identity
    }

    private fun signedDuoPayload(localSpki: ByteArray): ByteArray {
        val payload =
            EstablishmentProfileRevisionFixtureSupport.duoPayload(
                peerSpki = peerEstablishmentSpki,
            ).copy(
                deploymentTrustDomainId = GenerationFactPtiLayerBFixtureSupport.TRUST_DOMAIN,
                establishmentBindings =
                    listOf(
                        EstablishmentProfileRevisionFixtureSupport.localBinding(
                            establishmentKeyVersion = EstablishmentProfileRevisionFixtureSupport.ESTABLISHMENT_KEY_VERSION,
                            establishmentPublicKeySpki = localSpki,
                        ),
                        EstablishmentProfileRevisionFixtureSupport.peerBinding(
                            establishmentPublicKeySpki = peerEstablishmentSpki,
                        ),
                    ),
            )
        return signPayload(payload)
    }

    private fun signPayload(payload: EstablishmentProfileTrustPayload): ByteArray {
        val protected = EstablishmentProfileRevisionCanonicalCodec.encode(payload)
        return OperationalProfileTestSigning.signProtectedRevision(protected)
    }

    private fun acceptSigned(
        runtime: EstablishmentProductionComposition.EstablishmentProductionRuntime,
        signed: ByteArray,
    ) {
        val result = runtime.establishmentProfileDeliveryAcceptor.acceptSignedDelivery(signed)
        assertTrue(result is EstablishmentProfileRevisionDeliveryAcceptResult.Accepted)
    }
}

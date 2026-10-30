package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01GoldenVectorWireFixtures
import com.talkback.core.conference.session.profile01.Profile01MediaKeyPackageIngressResult
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustStateStore
import com.talkback.core.session.gbc.trust.profile.GenerationFactPtiLayerBFixtureSupport
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustState
import com.talkback.core.session.gbc.trust.profile.establishment.AcceptedLocalEstablishmentTrustStateStore
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreProvisionResult
import com.talkback.core.session.gbc.trust.profile.establishment.ModuleEstablishmentBinding
import com.talkback.core.session.gbc.trust.profile.establishment.TestLocalEstablishmentKeystoreIdentityStore
import com.talkback.core.session.gbc.wiring.EstablishmentProductionWiringHarness
import java.security.PrivateKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-P1D-2 exit tests D2-1..D2-6 — Factory production decrypt composition.
 */
class Profile01ProductionMediaKeyDecryptCompositionTest {
    private val corpus = Profile01Q5CryptoVectorFixtures.loadCorpus()
    private val wire = Profile01Q5CryptoVectorFixtures.wirePackage(corpus)

    @Test
    fun d2_1_verifiedIdentity_composesNonNullMediaKeyDecrypt() {
        val fixture = verifiedAuthorityFixture()
        val decrypt = Profile01ProductionMediaKeyDecryptComposition.compose(fixture.authority)
        assertNotNull(decrypt)

        val ingress = factoryMirrorIngress(fixture.authority)
        assertNotNull(ingress.mediaKeyDecryptSeam())
    }

    @Test
    fun d2_2_decryptUnwrapUsesVerifiedIdentityKeyAlias() {
        val fixture = verifiedAuthorityFixture()
        val spy = SpyKeyOperation(emptyMap())
        val decrypt =
            Profile01ProductionMediaKeyDecryptComposition.compose(fixture.authority, spy)
                ?: error("verified identity required")

        decrypt.decrypt(
            wire,
            localRecipientModuleId = fixture.localModuleId,
            localEstablishmentKeyVersion = fixture.establishmentKeyVersion,
        )
        assertEquals(listOf(fixture.authorizedKeyAlias), spy.requestedAliases)
    }

    @Test
    fun d2_3_localEstablishmentKeyVersionFromEstablishmentAuthority_notSigner() {
        val signingStore = AcceptedLocalTrustStateStore()
        Profile01EstablishmentAuthorityTestFixtures.populateSigningStore(signingStore)

        val fixture = verifiedAuthorityFixture()
        val signerVersion =
            signingStore.currentSnapshot()
                ?.bindingsByKey
                ?.values
                ?.first()
                ?.signerKeyVersion

        assertEquals(Profile01EstablishmentAuthorityTestFixtures.SIGNER_KEY_VERSION, signerVersion)
        assertEquals(corpus.inputs.recipientKeyVersion, fixture.authority.localEstablishmentKeyVersion())
        assertNotEquals(signerVersion, fixture.authority.localEstablishmentKeyVersion())
    }

    @Test
    fun d2_4_unavailableIdentity_compositionNullAndIngressDecryptSeamUnavailable() {
        val keystoreStore = TestLocalEstablishmentKeystoreIdentityStore()
        val establishmentStore = AcceptedLocalEstablishmentTrustStateStore()
        Profile01EstablishmentAuthorityTestFixtures.populateEstablishmentStore(establishmentStore)
        val deliveryAcceptor =
            EstablishmentProductionWiringHarness.deliveryAcceptorOnly(
                deploymentTrustDomainId = GenerationFactPtiLayerBFixtureSupport.TRUST_DOMAIN,
                anchorSource = GenerationFactPtiLayerBFixtureSupport.anchorStore(),
                store = establishmentStore,
            )
        val authority =
            Profile01EstablishmentAuthorityWiring.create(
                establishmentTrustStore = establishmentStore,
                localModuleId = corpus.inputs.recipientModuleId,
                keystoreIdentityStore = keystoreStore,
                establishmentProfileDeliveryAcceptor = deliveryAcceptor,
            )

        assertTrue(
            authority.verifiedLocalEstablishmentIdentity()
                is Profile01LocalEstablishmentIdentityAvailability.Unavailable,
        )
        assertNull(Profile01ProductionMediaKeyDecryptComposition.compose(authority))

        val ingress = factoryMirrorIngress(authority)
        assertNull(ingress.mediaKeyDecryptSeam())

        val result =
            ingress.ingestMediaKeyPackageSignedFact(
                signedFactBytes = byteArrayOf(0x01),
                localRecipientModuleId = corpus.inputs.recipientModuleId,
                localEstablishmentKeyVersion = corpus.inputs.recipientKeyVersion,
            )
        assertTrue(result is Profile01MediaKeyPackageIngressResult.Rejected)
        assertEquals(
            "DECRYPT_SEAM_UNAVAILABLE",
            (result as Profile01MediaKeyPackageIngressResult.Rejected).reason,
        )
    }

    @Test
    fun d2_5_signingIdentitySameNumericVersion_doesNotChangeCompositionBehavior() {
        val signingStore = AcceptedLocalTrustStateStore()
        Profile01EstablishmentAuthorityTestFixtures.populateSigningStore(
            signingStore,
            snapshot =
                Profile01EstablishmentAuthorityTestFixtures.signingOnlySnapshot(
                    signerKeyVersion = corpus.inputs.recipientKeyVersion,
                ),
        )

        val fixture = verifiedAuthorityFixture()
        val decrypt = Profile01ProductionMediaKeyDecryptComposition.compose(fixture.authority)
        assertNotNull(decrypt)
        assertEquals(corpus.inputs.recipientKeyVersion, fixture.authority.localEstablishmentKeyVersion())
        assertTrue(
            fixture.authority.verifiedLocalEstablishmentIdentity()
                is Profile01LocalEstablishmentIdentityAvailability.Verified,
        )
    }

    @Test
    fun d2_6_compositionZeroEnrollmentKeygenRotationOrTrustMutation() {
        val keystoreStore = TestLocalEstablishmentKeystoreIdentityStore()
        val fixture = verifiedAuthorityFixture(keystoreStore = keystoreStore)
        val snapshotBefore = fixture.authority.establishmentTrustStore.currentSnapshot()

        val decrypt = Profile01ProductionMediaKeyDecryptComposition.compose(fixture.authority)
        assertNotNull(decrypt)

        val snapshotAfter = fixture.authority.establishmentTrustStore.currentSnapshot()
        assertEquals(snapshotBefore?.taskProfileRevision, snapshotAfter?.taskProfileRevision)
        assertEquals(snapshotBefore?.bindingsByKey?.size, snapshotAfter?.bindingsByKey?.size)
        assertTrue(
            fixture.authority.verifiedLocalEstablishmentIdentity()
                is Profile01LocalEstablishmentIdentityAvailability.Verified,
        )
    }

    private fun factoryMirrorIngress(
        authority: Profile01EstablishmentAuthoritySurface,
    ): Profile01ConferenceMediaFactIngress {
        val registry = ConferenceSessionMediaControlFactRegistry()
        val bridge = ConferenceSessionMediaGbcPublisherBridge(registry)
        val validator =
            Profile01ConferenceMediaFactValidator(
                Profile01GoldenVectorWireFixtures.goldenVectorTrustBoundary(),
            )
        val supplementRegistry = Profile01SessionMediaSupplementRegistry()
        val mediaKeyDecrypt = Profile01ProductionMediaKeyDecryptComposition.compose(authority)
        return Profile01ConferenceMediaFactIngress(
            validator = validator,
            publisherBridge = bridge,
            mediaKeyDecrypt = mediaKeyDecrypt,
            supplementRegistry = supplementRegistry,
        )
    }

    private fun verifiedAuthorityFixture(
        localModuleId: String = corpus.inputs.recipientModuleId,
        establishmentKeyVersion: Long = corpus.inputs.recipientKeyVersion,
        keystoreStore: TestLocalEstablishmentKeystoreIdentityStore =
            TestLocalEstablishmentKeystoreIdentityStore(),
    ): VerifiedAuthorityFixture {
        val provisioned =
            keystoreStore.provisionFirstIdentity(localModuleId, establishmentKeyVersion)
                as LocalEstablishmentKeystoreProvisionResult.Created
        val establishmentStore = AcceptedLocalEstablishmentTrustStateStore()
        establishmentStore.atomicReplace(
            establishmentSnapshot(
                localModuleId = localModuleId,
                localSpki = provisioned.identity.publicKeySpki,
                establishmentKeyVersion = establishmentKeyVersion,
            ),
        )
        val deliveryAcceptor =
            EstablishmentProductionWiringHarness.deliveryAcceptorOnly(
                deploymentTrustDomainId = GenerationFactPtiLayerBFixtureSupport.TRUST_DOMAIN,
                anchorSource = GenerationFactPtiLayerBFixtureSupport.anchorStore(),
                store = establishmentStore,
            )
        val authority =
            Profile01EstablishmentAuthorityWiring.create(
                establishmentTrustStore = establishmentStore,
                localModuleId = localModuleId,
                keystoreIdentityStore = keystoreStore,
                establishmentProfileDeliveryAcceptor = deliveryAcceptor,
            )
        assertTrue(
            authority.verifiedLocalEstablishmentIdentity()
                is Profile01LocalEstablishmentIdentityAvailability.Verified,
        )
        return VerifiedAuthorityFixture(
            authority = authority,
            authorizedKeyAlias = provisioned.identity.keyAlias,
            localModuleId = localModuleId,
            establishmentKeyVersion = establishmentKeyVersion,
        )
    }

    private fun establishmentSnapshot(
        localModuleId: String,
        localSpki: ByteArray,
        establishmentKeyVersion: Long,
    ): AcceptedLocalEstablishmentTrustState {
        val peerBinding =
            ModuleEstablishmentBinding(
                moduleId = Profile01EstablishmentAuthorityTestFixtures.LOCAL_MODULE_ID,
                establishmentKeyVersion = establishmentKeyVersion,
                keyState = GenerationFactKeyState.ACTIVE,
                establishmentPublicKeySpki = Profile01EstablishmentAuthorityTestFixtures.establishmentPublicKeySpki.copyOf(),
                activatedAtRevision = 10L,
            )
        val localBinding =
            ModuleEstablishmentBinding(
                moduleId = localModuleId,
                establishmentKeyVersion = establishmentKeyVersion,
                keyState = GenerationFactKeyState.ACTIVE,
                establishmentPublicKeySpki = localSpki.copyOf(),
                activatedAtRevision = 10L,
            )
        return AcceptedLocalEstablishmentTrustState(
            taskProfileRevision = 10L,
            revisionIdentity = ByteArray(32),
            localModuleId = localModuleId,
            bindingsByKey =
                listOf(peerBinding, localBinding).associateBy {
                    AcceptedLocalEstablishmentTrustState.BindingKey(
                        it.moduleId,
                        it.establishmentKeyVersion,
                    )
                },
        )
    }

    private data class VerifiedAuthorityFixture(
        val authority: Profile01EstablishmentAuthoritySurface,
        val authorizedKeyAlias: String,
        val localModuleId: String,
        val establishmentKeyVersion: Long,
    )

    private class SpyKeyOperation(
        private val privateKeyByAlias: Map<String, PrivateKey>,
    ) : EstablishmentPekUnwrapKeyOperation {
        val requestedAliases = mutableListOf<String>()

        override fun unwrap(
            wrappedPek: ByteArray,
            authorizedKeyAlias: String,
        ): Profile01PekUnwrapResult {
            requestedAliases += authorizedKeyAlias
            val privateKey =
                privateKeyByAlias[authorizedKeyAlias]
                    ?: return Profile01PekUnwrapResult.Rejected("KEY_UNAVAILABLE")
            return EstablishmentRsaOaepPekCrypto.unwrap(wrappedPek, privateKey)
        }
    }
}

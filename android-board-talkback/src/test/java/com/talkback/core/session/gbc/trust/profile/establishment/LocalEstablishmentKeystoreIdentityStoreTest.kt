package com.talkback.core.session.gbc.trust.profile.establishment

import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.GenerationFactPtiFixtureSupport
import com.talkback.core.session.gbc.trust.profile.ModuleSigningBinding
import com.talkback.core.session.gbc.trust.profile.ProfileRevisionAcceptResult
import java.security.KeyPairGenerator
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PR-EP-2 exit tests: EP2-1..EP2-7 local establishment Keystore identity + profile verifier.
 *
 * Contract tests use [TestLocalEstablishmentKeystoreIdentityStore].
 * [AndroidKeystoreLocalEstablishmentIdentityStore] is smoke-tested when the provider is available.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LocalEstablishmentKeystoreIdentityStoreTest {
    private lateinit var keystoreStore: TestLocalEstablishmentKeystoreIdentityStore

    @Before
    fun setUp() {
        keystoreStore = TestLocalEstablishmentKeystoreIdentityStore()
    }

    @After
    fun tearDown() {
        keystoreStore.clear()
        clearAndroidEstablishmentAliasesIfAvailable()
    }

    @Test
    fun ep2_1_firstProvision_rsa3072NonExportableIdentity() {
        val result =
            keystoreStore.provisionFirstIdentity(
                moduleId = MODULE_ID,
                establishmentKeyVersion = VERSION,
            )

        assertTrue(result is LocalEstablishmentKeystoreProvisionResult.Created)
        val identity = (result as LocalEstablishmentKeystoreProvisionResult.Created).identity
        assertEquals(MODULE_ID, identity.moduleId)
        assertEquals(VERSION, identity.establishmentKeyVersion)
        assertTrue(EstablishmentSpkiValidator.isSupportedRsa3072(identity.publicKeySpki))
    }

    @Test
    fun ep2_1_androidKeystore_smokeWhenProviderAvailable() {
        val androidStore = androidKeystoreLocalEstablishmentIdentityStoreOrNull()
        assumeNotNull(androidStore)
        clearAndroidEstablishmentAliasesIfAvailable()
        val result =
            androidStore!!.provisionFirstIdentity(
                moduleId = MODULE_ID,
                establishmentKeyVersion = VERSION,
            )
        assertTrue(
            result is LocalEstablishmentKeystoreProvisionResult.Created ||
                result is LocalEstablishmentKeystoreProvisionResult.AlreadyProvisioned,
        )
        val spki =
            when (result) {
                is LocalEstablishmentKeystoreProvisionResult.Created -> result.identity.publicKeySpki
                is LocalEstablishmentKeystoreProvisionResult.AlreadyProvisioned -> result.identity.publicKeySpki
                else -> null
            }
        assertNotNull(spki)
        assertTrue(EstablishmentSpkiValidator.isSupportedRsa3072(spki!!))
    }

    @Test
    fun ep2_2_subsequentLoad_sameAliasVersionAndSpki_noSilentRegeneration() {
        val first =
            keystoreStore.provisionFirstIdentity(MODULE_ID, VERSION) as
                LocalEstablishmentKeystoreProvisionResult.Created
        val secondProvision = keystoreStore.provisionFirstIdentity(MODULE_ID, VERSION)
        assertTrue(secondProvision is LocalEstablishmentKeystoreProvisionResult.AlreadyProvisioned)
        assertArrayEquals(
            first.identity.publicKeySpki,
            (secondProvision as LocalEstablishmentKeystoreProvisionResult.AlreadyProvisioned)
                .identity.publicKeySpki,
        )

        val loaded =
            keystoreStore.loadIdentity(MODULE_ID, VERSION) as LocalEstablishmentKeystoreLoadResult.Ready
        assertArrayEquals(first.identity.publicKeySpki, loaded.identity.publicKeySpki)
        assertEquals(first.identity.keyAlias, loaded.identity.keyAlias)
    }

    @Test
    fun ep2_3_profileLocalBindingMatches_keystore_verified() {
        val provisioned =
            keystoreStore.provisionFirstIdentity(MODULE_ID, VERSION) as
                LocalEstablishmentKeystoreProvisionResult.Created
        val revision =
            authenticatedRevision(
                localSpki = provisioned.identity.publicKeySpki,
                localVersion = VERSION,
            )
        val result = LocalEstablishmentBindingVerifier.verifyRevisionAgainstKeystore(revision, keystoreStore)
        assertTrue(result is LocalEstablishmentBindingVerifyResult.Verified)
    }

    @Test
    fun ep2_4_profileSpkiMismatch_localEstablishmentKeyMismatch() {
        val provisioned =
            keystoreStore.provisionFirstIdentity(MODULE_ID, VERSION) as
                LocalEstablishmentKeystoreProvisionResult.Created
        val wrongSpki = generateRsa3072Spki()
        val profileBinding =
            AuthenticatedLocalEstablishmentBinding(
                moduleId = MODULE_ID,
                establishmentAlgorithm = EstablishmentAlgorithm.RSA_3072_OAEP_SHA256_MGF1_SHA1,
                establishmentKeyVersion = VERSION,
                establishmentPublicKeySpki = wrongSpki,
            )
        val result =
            LocalEstablishmentBindingVerifier.verify(
                profileBinding,
                provisioned.identity,
            )
        assertTrue(result is LocalEstablishmentBindingVerifyResult.LocalEstablishmentKeyMismatch)
    }

    @Test
    fun ep2_5_profileVersionMismatch_explicitNoCoercion() {
        keystoreStore.provisionFirstIdentity(MODULE_ID, VERSION)
        val loadedV4 =
            keystoreStore.loadIdentity(MODULE_ID, VERSION) as LocalEstablishmentKeystoreLoadResult.Ready
        val profileBinding =
            AuthenticatedLocalEstablishmentBinding(
                moduleId = MODULE_ID,
                establishmentAlgorithm = EstablishmentAlgorithm.RSA_3072_OAEP_SHA256_MGF1_SHA1,
                establishmentKeyVersion = VERSION + 1,
                establishmentPublicKeySpki = loadedV4.identity.publicKeySpki,
            )
        val result =
            LocalEstablishmentBindingVerifier.verify(
                profileBinding,
                loadedV4.identity,
            )
        assertTrue(result is LocalEstablishmentBindingVerifyResult.VersionMismatch)
        assertEquals(VERSION + 1, (result as LocalEstablishmentBindingVerifyResult.VersionMismatch).expectedVersion)
        assertEquals(VERSION, result.actualVersion)

        val revision =
            authenticatedRevision(
                localSpki = loadedV4.identity.publicKeySpki,
                localVersion = VERSION + 1,
            )
        val revisionResult =
            LocalEstablishmentBindingVerifier.verifyRevisionAgainstKeystore(revision, keystoreStore)
        assertTrue(revisionResult is LocalEstablishmentBindingVerifyResult.IdentityUnavailable)
    }

    @Test
    fun ep2_6_signerVersionDifferent_establishmentVerificationStillPasses() {
        val provisioned =
            keystoreStore.provisionFirstIdentity(MODULE_ID, VERSION) as
                LocalEstablishmentKeystoreProvisionResult.Created
        val signingHarness = GenerationFactPtiFixtureSupport.harness()
        val signingPayload =
            GenerationFactPtiFixtureSupport.activeBindingPayload(revision = 99L).copy(
                moduleBindings =
                    listOf(
                        ModuleSigningBinding(
                            moduleId = MODULE_ID,
                            signerKeyVersion = 77L,
                            keyState = GenerationFactKeyState.ACTIVE,
                            publicKeySpki = byteArrayOf(0x01, 0x02, 0x03),
                            activatedAtRevision = 99L,
                        ),
                    ),
            )
        val signingResult = GenerationFactPtiFixtureSupport.accept(signingHarness, signingPayload)
        assertTrue(signingResult is ProfileRevisionAcceptResult.Accepted)
        assertNotEquals(VERSION, 77L)

        val revision =
            authenticatedRevision(
                localSpki = provisioned.identity.publicKeySpki,
                localVersion = VERSION,
            )
        val establishmentResult =
            LocalEstablishmentBindingVerifier.verifyRevisionAgainstKeystore(revision, keystoreStore)
        assertTrue(establishmentResult is LocalEstablishmentBindingVerifyResult.Verified)
    }

    @Test
    fun ep2_7_missingKeystoreIdentity_explicitUnavailable_noSignerFallback() {
        val revision =
            authenticatedRevision(
                localSpki = generateRsa3072Spki(),
                localVersion = VERSION,
            )
        val result = LocalEstablishmentBindingVerifier.verifyRevisionAgainstKeystore(revision, keystoreStore)
        assertTrue(result is LocalEstablishmentBindingVerifyResult.IdentityUnavailable)
        assertEquals(
            "MISSING_KEYSTORE_IDENTITY",
            (result as LocalEstablishmentBindingVerifyResult.IdentityUnavailable).reason,
        )
    }

    @Test
    fun ep2_5_keystoreNeverAutoRepairsVersionSkew() {
        keystoreStore.provisionFirstIdentity(MODULE_ID, VERSION)
        assertTrue(
            keystoreStore.loadIdentity(MODULE_ID, VERSION + 1) is
                LocalEstablishmentKeystoreLoadResult.Unavailable,
        )
    }

    private fun authenticatedRevision(
        localSpki: ByteArray,
        localVersion: Long,
    ): AuthenticatedEstablishmentProfileRevision {
        val payload =
            EstablishmentProfileTrustPayload(
                taskProfileRevision = 10L,
                localModuleId = MODULE_ID,
                establishmentBindings =
                    listOf(
                        establishmentBinding(
                            moduleId = MODULE_ID,
                            establishmentKeyVersion = localVersion,
                            establishmentPublicKeySpki = localSpki,
                        ),
                    ),
            )
        return AuthenticatedEstablishmentProfileRevision.forValidatedSemantics(payload)
    }

    private fun establishmentBinding(
        moduleId: String,
        establishmentKeyVersion: Long,
        establishmentPublicKeySpki: ByteArray,
    ): EstablishmentProfileBinding =
        EstablishmentProfileBinding(
            moduleId = moduleId,
            establishmentAlgorithm = EstablishmentAlgorithm.RSA_3072_OAEP_SHA256_MGF1_SHA1,
            establishmentKeyVersion = establishmentKeyVersion,
            keyState = GenerationFactKeyState.ACTIVE,
            establishmentPublicKeySpki = establishmentPublicKeySpki.copyOf(),
            activatedAtRevision = 10L,
        )

    private fun generateRsa3072Spki(): ByteArray {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        return keyPair.public.encoded
    }

    private fun clearAndroidEstablishmentAliasesIfAvailable() {
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.aliases()
                .toList()
                .filter { alias -> alias.startsWith("talkback-establishment-") }
                .forEach { alias -> keyStore.deleteEntry(alias) }
        }
    }

    companion object {
        private const val MODULE_ID = "M01"
        private const val VERSION = 4L
    }
}

package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreAlias
import com.talkback.core.session.gbc.trust.profile.establishment.Profile01LocalEstablishmentIdentitySnapshot
import com.talkback.core.session.gbc.trust.profile.establishment.forModule
import java.security.PrivateKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-P1D-1 exit tests D1-1..D1-6 — production establishment PEK unwrap seam.
 *
 * Uses injected [EstablishmentPekUnwrapKeyOperation]; Android Keystore field evidence is separate.
 */
class Profile01ProductionRecipientKeyEstablishmentSeamTest {
    private val corpus = Profile01Q5CryptoVectorFixtures.loadCorpus()
    private val q5PrivateKey = Profile01Q5TestRecipientKeyEstablishmentSeam.q5TestOnlyEstablishmentPrivateKey()
    private val q5PublicSpki = Profile01Q5TestRecipientKeyEstablishmentSeam.testEstablishmentPublicKeySpki()
    private val expectedPek = Profile01Q5CryptoVectorFixtures.hex(corpus.inputs.pekHex)
    private val wrappedPek = Profile01Q5CryptoVectorFixtures.hex(corpus.expected.wrappedPekHex)
    private val authorizedAlias = "profile-authorized-establishment-M02-v4"
    private val wireConstructedV7Alias =
        LocalEstablishmentKeystoreAlias.forModule(RECIPIENT_MODULE_ID, 7L)
    private val signingDecoyAlias = "talkback-signer-M02-v4"

    private val verifiedIdentity =
        Profile01LocalEstablishmentIdentityAvailability.Verified(
            Profile01LocalEstablishmentIdentitySnapshot(
                moduleId = RECIPIENT_MODULE_ID,
                establishmentKeyVersion = ESTABLISHMENT_KEY_VERSION,
                publicKeySpki = q5PublicSpki.copyOf(),
                keyAlias = authorizedAlias,
            ),
        )

    @Test
    fun d1_1_verifiedIdentityMatchingModuleAndVersion_unwrapSucceeds() {
        val spy = SpyKeyOperation(aliasToPrivateKey())
        val seam = requireSeam(spy)
        val result =
            seam.unwrapPek(
                wrappedPek,
                recipientModuleId = RECIPIENT_MODULE_ID,
                recipientKeyVersion = ESTABLISHMENT_KEY_VERSION,
            )
        val ready = result as Profile01PekUnwrapResult.Ready
        assertArrayEquals(expectedPek, ready.pek)
        assertEquals(listOf(authorizedAlias), spy.requestedAliases)
    }

    @Test
    fun d1_2_recipientModuleIdMismatch_rejectsBeforeKeyOperation() {
        val spy = SpyKeyOperation(aliasToPrivateKey())
        val seam = requireSeam(spy)
        val result =
            seam.unwrapPek(
                wrappedPek,
                recipientModuleId = "M03",
                recipientKeyVersion = ESTABLISHMENT_KEY_VERSION,
            )
        assertRejected(result, "RECIPIENT_MODULE_MISMATCH")
        assertTrue(spy.requestedAliases.isEmpty())
    }

    @Test
    fun d1_3_establishmentKeyVersionMismatch_rejectsBeforeKeyOperation() {
        val spy =
            SpyKeyOperation(
                mapOf(
                    authorizedAlias to q5PrivateKey,
                    wireConstructedV7Alias to q5PrivateKey,
                ),
            )
        val seam = requireSeam(spy)
        val result =
            seam.unwrapPek(
                wrappedPek,
                recipientModuleId = RECIPIENT_MODULE_ID,
                recipientKeyVersion = 7L,
            )
        assertRejected(result, "RECIPIENT_KEY_VERSION_MISMATCH")
        assertTrue(spy.requestedAliases.isEmpty())
    }

    @Test
    fun d1_4_authorizedAliasUnavailable_rejectsExplicitly() {
        val seam = requireSeam(SpyKeyOperation(emptyMap()))
        val result =
            seam.unwrapPek(
                wrappedPek,
                recipientModuleId = RECIPIENT_MODULE_ID,
                recipientKeyVersion = ESTABLISHMENT_KEY_VERSION,
            )
        assertRejected(result, "KEY_UNAVAILABLE")
    }

    @Test
    fun d1_5_corruptWrappedPek_rejectsExplicitly() {
        val seam = requireSeam(SpyKeyOperation(aliasToPrivateKey()))
        val result =
            seam.unwrapPek(
                ByteArray(Profile01WireConstants.WRAPPED_PEK_BYTES),
                recipientModuleId = RECIPIENT_MODULE_ID,
                recipientKeyVersion = ESTABLISHMENT_KEY_VERSION,
            )
        assertRejected(result, "OAEP_DECRYPT_FAIL")
    }

    @Test
    fun d1_6_signingDecoyWithSameVersion_neverConsulted() {
        val spy =
            SpyKeyOperation(
                mapOf(
                    authorizedAlias to q5PrivateKey,
                    signingDecoyAlias to q5PrivateKey,
                    wireConstructedV7Alias to q5PrivateKey,
                ),
            )
        val seam = requireSeam(spy)
        val result =
            seam.unwrapPek(
                wrappedPek,
                recipientModuleId = RECIPIENT_MODULE_ID,
                recipientKeyVersion = ESTABLISHMENT_KEY_VERSION,
            )
        assertTrue(result is Profile01PekUnwrapResult.Ready)
        assertEquals(listOf(authorizedAlias), spy.requestedAliases)
    }

    @Test
    fun create_returnsNullWhenIdentityUnavailable() {
        val seam =
            Profile01ProductionRecipientKeyEstablishmentSeam.create(
                Profile01LocalEstablishmentIdentityAvailability.Unavailable("MISSING"),
                keyOperation = SpyKeyOperation(aliasToPrivateKey()),
            )
        assertEquals(null, seam)
    }

    private fun requireSeam(
        keyOperation: EstablishmentPekUnwrapKeyOperation,
    ): Profile01ProductionRecipientKeyEstablishmentSeam =
        Profile01ProductionRecipientKeyEstablishmentSeam.create(verifiedIdentity, keyOperation)
            ?: error("verified identity required")

    private fun aliasToPrivateKey(): Map<String, PrivateKey> =
        mapOf(authorizedAlias to q5PrivateKey)

    private fun assertRejected(
        result: Profile01PekUnwrapResult,
        reason: String,
    ) {
        assertTrue(result is Profile01PekUnwrapResult.Rejected)
        assertEquals(reason, (result as Profile01PekUnwrapResult.Rejected).reason)
    }

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

    companion object {
        private const val RECIPIENT_MODULE_ID = "M02"
        private const val ESTABLISHMENT_KEY_VERSION = 4L
    }
}

package com.talkback.core.conference.session.profile01

import com.talkback.core.conference.session.profile01.wire.Profile01CborCodec
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageDecryptResult
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageDecryptSeam
import com.talkback.core.conference.session.profile01.wire.Profile01PackageCrypto
import com.talkback.core.conference.session.profile01.wire.Profile01Q5CryptoVectorFixtures
import com.talkback.core.conference.session.profile01.wire.Profile01Q5TestRecipientKeyEstablishmentSeam
import com.talkback.core.conference.session.profile01.wire.Profile01WireConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ADR-0058 Profile 01 Q5 MEDIA_KEY_PACKAGE decrypt seam — PUBLIC TEST KEYS ONLY.
 */
class Profile01MediaKeyPackageDecryptSeamTest {
    private val corpus = Profile01Q5CryptoVectorFixtures.loadCorpus()
    private val wire = Profile01Q5CryptoVectorFixtures.wirePackage(corpus)
    private val seam =
        Profile01MediaKeyPackageDecryptSeam(Profile01Q5TestRecipientKeyEstablishmentSeam.defaultSeam())

    @Test
    fun packageAad_matchesQ5GoldenVector() {
        val actual = Profile01PackageCrypto.packageAadBytes(wire)
        assertEquals(corpus.expected.packageAadBytesHex, actual.toHex())
    }

    @Test
    fun positiveVector_derivesExpectedSrtpMaterial() {
        val result =
            seam.decrypt(
                wire,
                localRecipientModuleId = corpus.inputs.recipientModuleId,
                localEstablishmentKeyVersion = corpus.inputs.recipientKeyVersion,
            )
        val ready = result as Profile01MediaKeyPackageDecryptResult.Ready
        assertEquals(corpus.expected.srtpMasterKeyHex, ready.material.masterKey.toHex())
        assertEquals(corpus.expected.srtpMasterSaltHex, ready.material.masterSalt.toHex())
        assertEquals("00112233445566778899aabbccddeeff", ready.material.conferenceId)
        assertEquals(7L, ready.material.conferenceEpoch)
        assertEquals(2L, ready.material.membershipVersion)
        assertEquals(3L, ready.material.mediaKeyEpoch)
        assertEquals(
            corpus.expected.membershipKeyContextDigestHex,
            ready.material.membershipKeyContextDigest.toHex(),
        )
        assertEquals(8, ready.material.keyContextHint64.size)
    }

    @Test
    fun negativeVectors_failClosed() {
        val unwrapSeam = Profile01Q5TestRecipientKeyEstablishmentSeam.defaultSeam()
        val unwrapped =
            (unwrapSeam.unwrapPek(
                hex(corpus.expected.wrappedPekHex),
                corpus.inputs.recipientModuleId,
                corpus.inputs.recipientKeyVersion,
            ) as com.talkback.core.conference.session.profile01.wire.Profile01PekUnwrapResult.Ready).pek
        val aad = hex(corpus.expected.packageAadBytesHex)
        val encrypted = hex(corpus.expected.ciphertextHex + corpus.expected.gcmTagHex)
        val nonce = hex(corpus.inputs.gcmNonceHex)

        assertDecryptFails("wrong-recipient-module-aad") {
            val wrongWire = wire.copy(recipientModuleId = "M03")
            seam.decrypt(wrongWire, "M02", corpus.inputs.recipientKeyVersion)
        }
        assertDecryptFails("wrong-recipient-key-version-aad") {
            seam.decrypt(wire, corpus.inputs.recipientModuleId, corpus.inputs.recipientKeyVersion + 1)
        }
        assertGcmFails {
            val wrongAad = buildWrongAadRecipientModule("M03")
            decryptGcm(unwrapped, nonce, encrypted, wrongAad)
        }
        assertGcmFails {
            val wrongAad = buildWrongAadKeyVersion(corpus.inputs.recipientKeyVersion + 1)
            decryptGcm(unwrapped, nonce, encrypted, wrongAad)
        }
        assertGcmFails {
            val wrongDigest = wire.membershipFactDigest.copyOf()
            wrongDigest[0] = (wrongDigest[0].toInt() xor 1).toByte()
            val wrongAad = buildWrongAadMembershipDigest(wrongDigest)
            decryptGcm(unwrapped, nonce, encrypted, wrongAad)
        }
        assertGcmFails {
            val tampered = encrypted.copyOf()
            tampered[tampered.size - 1] = (tampered.last().toInt() xor 1).toByte()
            decryptGcm(unwrapped, nonce, tampered, aad)
        }
        assertDecryptFails("wrong-context-commitment") {
            val wrongWire = wire.copy(mediaKeyCommitment = hex("00".repeat(32)))
            seam.decrypt(wrongWire, corpus.inputs.recipientModuleId, corpus.inputs.recipientKeyVersion)
        }
        for (vector in corpus.negativeVectors) {
            assertTrue("negative vector listed: ${vector.name}", vector.expected.isNotBlank())
        }
    }

    private fun assertDecryptFails(label: String, action: () -> Profile01MediaKeyPackageDecryptResult) {
        val result = action()
        assertTrue("$label expected reject, got $result", result is Profile01MediaKeyPackageDecryptResult.Rejected)
    }

    private fun assertGcmFails(action: () -> Unit) {
        try {
            action()
            throw AssertionError("expected GCM failure")
        } catch (_: Exception) {
            // expected
        }
    }

    private fun decryptGcm(pek: ByteArray, nonce: ByteArray, encrypted: ByteArray, aad: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(pek, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        cipher.doFinal(encrypted)
    }

    private fun buildWrongAadRecipientModule(moduleId: String): ByteArray =
        buildWrongAad(mapOf(7 to Profile01CborCodec.CborValue.Text(moduleId)))

    private fun buildWrongAadKeyVersion(version: Long): ByteArray =
        buildWrongAad(mapOf(11 to Profile01CborCodec.CborValue.Unsigned(version)))

    private fun buildWrongAadMembershipDigest(digest: ByteArray): ByteArray =
        buildWrongAad(mapOf(5 to Profile01CborCodec.CborValue.ByteString(digest)))

    private fun buildWrongAad(overrides: Map<Int, Profile01CborCodec.CborValue>): ByteArray {
        val base =
            listOf(
                0 to Profile01CborCodec.CborValue.ByteString(wire.conferenceId),
                1 to Profile01CborCodec.CborValue.Unsigned(wire.conferenceEpoch),
                2 to Profile01CborCodec.CborValue.Text(wire.ownerModuleId),
                3 to Profile01CborCodec.CborValue.Unsigned(wire.membershipVersion),
                4 to Profile01CborCodec.CborValue.Unsigned(wire.mediaKeyEpoch),
                5 to Profile01CborCodec.CborValue.ByteString(wire.membershipFactDigest),
                6 to Profile01CborCodec.CborValue.ByteString(wire.mediaKeyCommitment),
                7 to Profile01CborCodec.CborValue.Text(wire.recipientModuleId),
                8 to Profile01CborCodec.CborValue.ByteString(wire.packageIdentity),
                9 to Profile01CborCodec.CborValue.ByteString(wire.wrappedPek),
                10 to Profile01CborCodec.CborValue.ByteString(wire.gcmNonce),
                11 to Profile01CborCodec.CborValue.Unsigned(wire.recipientKeyVersion),
            ).map { (key, value) ->
                Profile01CborCodec.CborValue.Unsigned(key.toLong()) to (overrides[key] ?: value)
            }
        val core = Profile01CborCodec.CborValue.CborMap(base)
        val envelope =
            Profile01CborCodec.CborValue.CborArray(
                listOf(
                    Profile01CborCodec.CborValue.Unsigned(Profile01WireConstants.SCHEMA_VERSION.toLong()),
                    Profile01CborCodec.CborValue.Unsigned(Profile01WireConstants.FACT_TYPE_MEDIA_KEY_PACKAGE.toLong()),
                    core,
                ),
            )
        return Profile01WireConstants.PACKAGE_AAD_DOMAIN + Profile01CborCodec.encode(envelope)
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }

    private fun hex(value: String): ByteArray = Profile01Q5CryptoVectorFixtures.hex(value)
}

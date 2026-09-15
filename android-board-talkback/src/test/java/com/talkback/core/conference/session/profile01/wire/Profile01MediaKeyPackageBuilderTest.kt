package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.conference.session.profile01.Profile01GoldenVectorSignedFactTrustBoundary
import com.talkback.core.conference.session.profile01.Profile01WireVerificationResult
import com.talkback.core.session.gbc.trust.GenerationFactKeyState
import com.talkback.core.session.gbc.trust.profile.establishment.ModuleEstablishmentBinding
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-B exit tests: B1–B6 MEDIA_KEY_PACKAGE builder (PR-2).
 */
class Profile01MediaKeyPackageBuilderTest {
    private val corpus = Profile01Q5CryptoVectorFixtures.loadCorpus()
    private val q5ReferenceWire = Profile01Q5CryptoVectorFixtures.wirePackage(corpus)
    private val signerBundle = testSigner(moduleId = "M01", keyVersion = 1L)
    private val signer = signerBundle.signer
    private val trustBoundary = signerBundle.trustBoundary
    private val decryptSeam =
        Profile01MediaKeyPackageDecryptSeam(Profile01Q5TestRecipientKeyEstablishmentSeam.defaultSeam())

    @Test
    fun b1_build_decoderAndValidatorAcceptEnvelope() {
        val built = buildQ5Package()
        val decoded = Profile01WireCborDecoder.decodeMediaKeyPackage(built.signedFactBytes)
        assertTrue(decoded is Profile01WireCborDecoder.DecodeResult.Ready)
        val verified = trustBoundary.verifySignedFact(built.signedFactBytes)
        assertTrue(verified is Profile01WireVerificationResult.Verified)
    }

    @Test
    fun b2_recipientDecrypt_derivesExpectedSrtpMaterial() {
        val built = buildQ5Package()
        val wire =
            (Profile01WireCborDecoder.decodeMediaKeyPackage(built.signedFactBytes)
                as Profile01WireCborDecoder.DecodeResult.Ready).value
        val result =
            decryptSeam.decrypt(
                wire,
                localRecipientModuleId = corpus.inputs.recipientModuleId,
                localEstablishmentKeyVersion = corpus.inputs.recipientKeyVersion,
            )
        val ready = result as Profile01MediaKeyPackageDecryptResult.Ready
        assertEquals(16, ready.material.masterKey.size)
        assertEquals(12, ready.material.masterSalt.size)
        assertEquals(8, ready.material.keyContextHint64.size)
        assertEquals(corpus.expected.srtpMasterKeyHex, ready.material.masterKey.toHex())
        assertEquals(corpus.expected.srtpMasterSaltHex, ready.material.masterSalt.toHex())
        assertEquals(
            corpus.expected.membershipKeyContextDigestHex,
            ready.material.membershipKeyContextDigest.toHex(),
        )
    }

    @Test
    fun cf7_overrideRecipientKeyVersionAndResign_onlyVersionChanges_rejectsAtMismatch() {
        val built = buildQ5Package()
        val original =
            (
                Profile01WireCborDecoder.decodeMediaKeyPackage(built.signedFactBytes)
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value
        val wireVersion =
            Profile01MediaKeyPackageNegativeFixtureVersions.chooseWireMismatchVersion(
                original.recipientKeyVersion,
            )
        val overridden =
            Profile01MediaKeyPackageBuilder(signer = signer, randomSource = q5FixedRandom())
                .overrideRecipientKeyVersionAndResign(built.signedFactBytes, wireVersion)
        assertTrue(overridden is Profile01MediaKeyPackageBuildResult.Ready)
        val wire =
            (
                Profile01WireCborDecoder.decodeMediaKeyPackage(
                    (overridden as Profile01MediaKeyPackageBuildResult.Ready).signedFactBytes,
                ) as Profile01WireCborDecoder.DecodeResult.Ready
            ).value
        assertEquals(wireVersion, wire.recipientKeyVersion)
        assertArrayEquals(original.wrappedPek, wire.wrappedPek)
        assertArrayEquals(original.ciphertext, wire.ciphertext)
        assertArrayEquals(original.gcmTag, wire.gcmTag)
        assertArrayEquals(original.packageIdentity, wire.packageIdentity)
        assertNotEquals(original.recipientKeyVersion, wire.recipientKeyVersion)
        val verified = trustBoundary.verifySignedFact(overridden.signedFactBytes)
        assertTrue(verified is Profile01WireVerificationResult.Verified)
        val decrypted =
            decryptSeam.decrypt(
                wire,
                localRecipientModuleId = corpus.inputs.recipientModuleId,
                localEstablishmentKeyVersion = corpus.inputs.recipientKeyVersion,
            )
        assertTrue(decrypted is Profile01MediaKeyPackageDecryptResult.Rejected)
        assertEquals(
            "RECIPIENT_KEY_VERSION_MISMATCH",
            (decrypted as Profile01MediaKeyPackageDecryptResult.Rejected).reason,
        )
    }

    @Test
    fun b3_wrongRecipientPrivateKey_fails() {
        val built = buildQ5Package()
        val wire =
            (Profile01WireCborDecoder.decodeMediaKeyPackage(built.signedFactBytes)
                as Profile01WireCborDecoder.DecodeResult.Ready).value
        val wrongKeySeam =
            Profile01MediaKeyPackageDecryptSeam(
                Profile01Q5TestRecipientKeyEstablishmentSeam(
                    mapOf("M03" to corpus.inputs.recipientKeyVersion to generateRsaPrivateKey()),
                ),
            )
        val result =
            wrongKeySeam.decrypt(
                wire,
                localRecipientModuleId = corpus.inputs.recipientModuleId,
                localEstablishmentKeyVersion = corpus.inputs.recipientKeyVersion,
            )
        assertTrue(result is Profile01MediaKeyPackageDecryptResult.Rejected)
    }

    @Test
    fun b4_wrongEstablishmentKeyVersionOrRecipientIdentity_fails() {
        val built = buildQ5Package()
        val wire =
            (Profile01WireCborDecoder.decodeMediaKeyPackage(built.signedFactBytes)
                as Profile01WireCborDecoder.DecodeResult.Ready).value
        val wrongVersion =
            decryptSeam.decrypt(
                wire,
                localRecipientModuleId = corpus.inputs.recipientModuleId,
                localEstablishmentKeyVersion = corpus.inputs.recipientKeyVersion + 1,
            )
        assertTrue(wrongVersion is Profile01MediaKeyPackageDecryptResult.Rejected)

        val wrongRecipient =
            decryptSeam.decrypt(
                wire.copy(recipientModuleId = "M03"),
                localRecipientModuleId = corpus.inputs.recipientModuleId,
                localEstablishmentKeyVersion = corpus.inputs.recipientKeyVersion,
            )
        assertTrue(wrongRecipient is Profile01MediaKeyPackageDecryptResult.Rejected)
    }

    @Test
    fun b5_tamperFields_failAtCorrectBoundary() {
        val built = buildQ5Package()
        val wire =
            (Profile01WireCborDecoder.decodeMediaKeyPackage(built.signedFactBytes)
                as Profile01WireCborDecoder.DecodeResult.Ready).value

        val tamperedSigned = built.signedFactBytes.copyOf()
        tamperedSigned[tamperedSigned.size - 1] =
            (tamperedSigned.last().toInt() xor 0x01).toByte()
        val verifyTampered = trustBoundary.verifySignedFact(tamperedSigned)
        assertTrue(verifyTampered is Profile01WireVerificationResult.Rejected)

        val tamperedCipher = wire.copy(ciphertext = wire.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        val decryptCipher =
            decryptSeam.decrypt(
                tamperedCipher,
                corpus.inputs.recipientModuleId,
                corpus.inputs.recipientKeyVersion,
            )
        assertTrue(decryptCipher is Profile01MediaKeyPackageDecryptResult.Rejected)

        val tamperedDigest = wire.membershipFactDigest.copyOf()
        tamperedDigest[0] = (tamperedDigest[0].toInt() xor 1).toByte()
        val tamperedWrapped = wire.wrappedPek.copyOf()
        tamperedWrapped[0] = (tamperedWrapped[0].toInt() xor 1).toByte()
        val decryptWrapped =
            decryptSeam.decrypt(
                wire.copy(wrappedPek = tamperedWrapped),
                corpus.inputs.recipientModuleId,
                corpus.inputs.recipientKeyVersion,
            )
        assertTrue(decryptWrapped is Profile01MediaKeyPackageDecryptResult.Rejected)
        val decryptDigest =
            decryptSeam.decrypt(
                wire.copy(membershipFactDigest = tamperedDigest, mediaKeyCommitment = hex("00".repeat(32))),
                corpus.inputs.recipientModuleId,
                corpus.inputs.recipientKeyVersion,
            )
        assertTrue(decryptDigest is Profile01MediaKeyPackageDecryptResult.Rejected)
    }

    @Test
    fun b6_twoBuilds_differentCiphertext_sameAuthorizedMaterial() {
        val request = q5BuildRequest()
        val builder =
            Profile01MediaKeyPackageBuilder(
                signer = signer,
                randomSource = Profile01PackageRandomSource.SecureRandom,
            )
        val first =
            (builder.build(request) as Profile01MediaKeyPackageBuildResult.Ready)
        val second =
            (builder.build(request) as Profile01MediaKeyPackageBuildResult.Ready)
        assertFalse(first.signedFactBytes.contentEquals(second.signedFactBytes))
        assertNotEquals(first.packageIdentity.toHex(), second.packageIdentity.toHex())

        val wire1 =
            (Profile01WireCborDecoder.decodeMediaKeyPackage(first.signedFactBytes)
                as Profile01WireCborDecoder.DecodeResult.Ready).value
        val wire2 =
            (Profile01WireCborDecoder.decodeMediaKeyPackage(second.signedFactBytes)
                as Profile01WireCborDecoder.DecodeResult.Ready).value
        assertNotEquals(wire1.ciphertext.toHex(), wire2.ciphertext.toHex())

        val material1 =
            (decryptSeam.decrypt(
                wire1,
                corpus.inputs.recipientModuleId,
                corpus.inputs.recipientKeyVersion,
            ) as Profile01MediaKeyPackageDecryptResult.Ready).material
        val material2 =
            (decryptSeam.decrypt(
                wire2,
                corpus.inputs.recipientModuleId,
                corpus.inputs.recipientKeyVersion,
            ) as Profile01MediaKeyPackageDecryptResult.Ready).material
        assertArrayEquals(material1.masterKey, material2.masterKey)
        assertArrayEquals(material1.masterSalt, material2.masterSalt)
        assertArrayEquals(material1.membershipKeyContextDigest, material2.membershipKeyContextDigest)
    }

    @Test
    fun builder_rejectsCommitmentMismatch() {
        val request =
            q5BuildRequest().let { req ->
                req.copy(
                    material =
                        req.material.copy(
                            mediaKeyCommitment = ByteArray(32),
                        ),
                )
            }
        val result =
            Profile01MediaKeyPackageBuilder(
                signer = signer,
                randomSource = q5FixedRandom(),
            ).build(request)
        assertTrue(result is Profile01MediaKeyPackageBuildResult.Rejected)
    }

    private fun buildQ5Package(): Profile01MediaKeyPackageBuildResult.Ready {
        val result =
            Profile01MediaKeyPackageBuilder(
                signer = signer,
                randomSource = q5FixedRandom(),
            ).build(q5BuildRequest())
        return result as Profile01MediaKeyPackageBuildResult.Ready
    }

    private fun q5BuildRequest(): Profile01MediaKeyPackageBuildRequest {
        val secret = hex(corpus.inputs.conferenceMediaSecretHex)
        val contextDigest = hex(corpus.expected.membershipKeyContextDigestHex)
        val commitment = Profile01PackageCrypto.computeMediaKeyCommitment(secret, contextDigest)
        val recipientBinding =
            Profile01PackageRecipientBinding.fromModuleEstablishmentBinding(
                ModuleEstablishmentBinding(
                    moduleId = corpus.inputs.recipientModuleId,
                    establishmentKeyVersion = corpus.inputs.recipientKeyVersion,
                    keyState = GenerationFactKeyState.ACTIVE,
                    establishmentPublicKeySpki =
                        Profile01Q5TestRecipientKeyEstablishmentSeam.testEstablishmentPublicKeySpki(),
                    activatedAtRevision = 10L,
                ),
            )
        return Profile01MediaKeyPackageBuildRequest(
            material =
                Profile01ConferenceKeyMaterial(
                    conferenceId = q5ReferenceWire.conferenceId.copyOf(),
                    conferenceEpoch = q5ReferenceWire.conferenceEpoch,
                    ownerModuleId = q5ReferenceWire.ownerModuleId,
                    membershipVersion = q5ReferenceWire.membershipVersion,
                    mediaKeyEpoch = q5ReferenceWire.mediaKeyEpoch,
                    conferenceMediaSecret = secret,
                    membershipKeyContextDigest = contextDigest,
                    mediaKeyCommitment = commitment,
                ),
            creationFactDigest = hex(corpus.inputs.membershipFactDigestHex),
            recipient = recipientBinding,
        )
    }

    private fun q5FixedRandom(): Profile01PackageRandomSource =
        Profile01PackageRandomSource.fixed(
            pek = hex(corpus.inputs.pekHex),
            packageIdentity = hex(corpus.inputs.packageIdentityHex),
            gcmNonce = hex(corpus.inputs.gcmNonceHex),
        )

    private data class TestSignerBundle(
        val signer: Profile01PersistedSignedFactSigner,
        val trustBoundary: Profile01GoldenVectorSignedFactTrustBoundary,
    )

    private fun testSigner(
        moduleId: String,
        keyVersion: Long,
    ): TestSignerBundle {
        val keyPair =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
        val signer =
            Profile01PersistedSignedFactSigner.fromPkcs8(
                pkcs8PrivateKey = keyPair.private.encoded,
                signerModuleId = moduleId,
                signerKeyVersion = keyVersion,
            )!!
        val x963 = ecPublicKeyX963(keyPair.public)
        return TestSignerBundle(
            signer = signer,
            trustBoundary =
                Profile01GoldenVectorSignedFactTrustBoundary(
                    mapOf(moduleId to keyVersion to x963),
                ),
        )
    }

    private fun ecPublicKeyX963(publicKey: java.security.PublicKey): ByteArray {
        val ec = publicKey as java.security.interfaces.ECPublicKey
        val x = ec.w.affineX.toByteArray()
        val y = ec.w.affineY.toByteArray()
        val x32 = x.copyOfRange(maxOf(0, x.size - 32), x.size)
        val y32 = y.copyOfRange(maxOf(0, y.size - 32), y.size)
        return byteArrayOf(0x04) + ByteArray(32 - x32.size) + x32 + ByteArray(32 - y32.size) + y32
    }

    private fun generateRsaPrivateKey(): java.security.PrivateKey {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        return keyPair.private
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }

    private fun hex(value: String): ByteArray = Profile01Q5CryptoVectorFixtures.hex(value)
}

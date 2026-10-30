package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.conference.session.profile01.Profile01GoldenVectorWireFixtures
import com.talkback.core.conference.session.profile01.Profile01WireMembershipMember
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class Profile01WireCborEncoderTest {
    @Test
    fun encodeCreation_roundTripsThroughDecoder() {
        val wire = Profile01GoldenVectorWireFixtures.decodeSessionWireFact()
        val snapshot =
            Profile01CreationAuthoritySnapshot(
                conferenceId = wire.conferenceId.hexToId128Bytes(),
                conferenceEpoch = wire.conferenceEpoch,
                ownerModuleId = "M01",
                mediaGroupDescriptor = decodeCreationDescriptor(wire),
                membershipView = wire.membershipView,
                initialMembershipVersion = wire.membershipVersion,
                initialMediaKeyEpoch = wire.mediaKeyEpoch,
                mediaKeyCommitment = wire.mediaKeyCommitment.copyOf(),
                signerKeyVersion = 1L,
            )
        val signer = testSigner(moduleId = "M01", keyVersion = 1L)
        val full = Profile01WireCborEncoder.encodeCreationFullFact(snapshot)
        val signature = signer.signFullFact(full)
        val signed = Profile01SignedFactEnvelopeBuilder.build(full, signature)
        val decoded =
            Profile01WireCborDecoder.decodeCreationSession(
                signed,
                Profile01GoldenVectorWireFixtures.sessionMediaSupplement(),
            )
        assertTrue(decoded is Profile01WireCborDecoder.DecodeResult.Ready)
        val roundTrip = (decoded as Profile01WireCborDecoder.DecodeResult.Ready).value
        assertEquals(wire.conferenceId, roundTrip.conferenceId)
        assertEquals(wire.conferenceEpoch, roundTrip.conferenceEpoch)
        assertEquals(wire.membershipVersion, roundTrip.membershipVersion)
        assertEquals(wire.mediaKeyEpoch, roundTrip.mediaKeyEpoch)
        assertEquals(wire.membershipView.size, roundTrip.membershipView.size)
        assertArrayEquals(wire.mediaKeyCommitment, roundTrip.mediaKeyCommitment)
        assertEquals(wire.endpoint.multicastAddress, roundTrip.endpoint.multicastAddress)
        assertEquals(wire.endpoint.mediaPort, roundTrip.endpoint.mediaPort)
    }

    @Test
    fun encodeSourceDeclaration_roundTripsThroughDecoder() {
        val wire = Profile01GoldenVectorWireFixtures.decodeMemberSourceWireFact()
        val envelope =
            Profile01WireCborDecoder.parseEnvelope(
                Profile01GoldenVectorWireFixtures.sourceDeclarationSignedFactBytes,
            )!!
        val authority = Profile01CborCodec.decodeStrict(envelope.fullCanonicalBytes).intKeyMap()!![2]!!.intKeyMap()!!
        val sourceInstanceId = authority[6]!!.asByteString()!!
        val descriptorDigest = authority[9]!!.asByteString()!!
        val snapshot =
            Profile01SourceDeclarationAuthoritySnapshot(
                conferenceId = wire.conferenceId.hexToId128Bytes(),
                conferenceEpoch = wire.conferenceEpoch,
                membershipVersion = wire.membershipVersion,
                mediaKeyEpoch = wire.mediaKeyEpoch,
                declaringModuleId = wire.moduleId,
                sourceGeneration = wire.sourceGeneration,
                sourceInstanceId = sourceInstanceId.copyOf(),
                ssrc = wire.ssrc,
                mediaGroupDescriptorDigest = descriptorDigest.copyOf(),
                signerKeyVersion = 1L,
            )
        val signer = testSigner(moduleId = wire.moduleId, keyVersion = 1L)
        val full = Profile01WireCborEncoder.encodeSourceDeclarationFullFact(snapshot)
        val signed =
            Profile01SignedFactEnvelopeBuilder.build(
                full,
                signer.signFullFact(full),
            )
        val decoded = Profile01WireCborDecoder.decodeSourceDeclarationMember(signed)
        assertTrue(decoded is Profile01WireCborDecoder.DecodeResult.Ready)
        val roundTrip = (decoded as Profile01WireCborDecoder.DecodeResult.Ready).value
        assertEquals(wire.conferenceId, roundTrip.conferenceId)
        assertEquals(wire.moduleId, roundTrip.moduleId)
        assertEquals(wire.sourceGeneration, roundTrip.sourceGeneration)
        assertEquals(wire.ssrc, roundTrip.ssrc)
        assertArrayEquals(wire.sourceAdmissionKey48, roundTrip.sourceAdmissionKey48)
    }

    @Test
    fun encodeCreation_productionDescriptor_verifiesWithTrustBoundary() {
        val conferenceId = Profile01ConferenceIdAuthority.deriveId128Bytes("session-test-1")
        val members =
            listOf(
                Profile01WireMembershipMember(
                    moduleId = "M01",
                    membershipIncarnationId =
                        Profile01MembershipIncarnationAuthority.deriveIncarnationId128(
                            conferenceId,
                            "M01",
                        ),
                ),
                Profile01WireMembershipMember(
                    moduleId = "M02",
                    membershipIncarnationId =
                        Profile01MembershipIncarnationAuthority.deriveIncarnationId128(
                            conferenceId,
                            "M02",
                        ),
                ),
            )
        val descriptor = Profile01MediaGroupDescriptor.productionDescriptor()
        val descriptorDigest = Profile01FactDigest.descriptorDigest(descriptor)
        val secret = ByteArray(32) { it.toByte() }
        val contextDigest =
            Profile01MembershipKeyContextDigest.computeForCreation(
                conferenceId = conferenceId,
                conferenceEpoch = 3L,
                ownerModuleId = "M01",
                mediaGroupDescriptorDigest = descriptorDigest,
                membershipVersion = 0L,
                completeMembershipView = members,
                mediaKeyEpoch = 1L,
            )
        val commitment =
            Profile01PackageCrypto.computeMediaKeyCommitment(
                conferenceMediaSecret = secret,
                membershipKeyContextDigest = contextDigest,
            )
        val keyPair =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
        val signer =
            Profile01PersistedSignedFactSigner.fromPkcs8(
                pkcs8PrivateKey = keyPair.private.encoded,
                signerModuleId = "M01",
                signerKeyVersion = 1L,
            )!!
        val snapshot =
            Profile01CreationAuthoritySnapshot(
                conferenceId = conferenceId,
                conferenceEpoch = 3L,
                ownerModuleId = "M01",
                mediaGroupDescriptor = descriptor,
                membershipView = members,
                initialMembershipVersion = 0L,
                initialMediaKeyEpoch = 1L,
                mediaKeyCommitment = commitment,
                signerKeyVersion = 1L,
            )
        val signed =
            Profile01SignedFactEnvelopeBuilder.build(
                Profile01WireCborEncoder.encodeCreationFullFact(snapshot),
                signer.signFullFact(Profile01WireCborEncoder.encodeCreationFullFact(snapshot)),
            )
        val envelope = Profile01SignedFactEnvelope.parse(signed)!!
        assertEquals(
            "PASS",
            Profile01SignedFactVerifier.verifySignature(
                envelope.fullCanonicalBytes,
                envelope.signatureRs,
                keyPair.public.encoded,
            ),
        )
    }

    private fun decodeCreationDescriptor(
        wire: com.talkback.core.conference.session.profile01.Profile01WireSessionFact,
    ): Profile01CborCodec.CborValue {
        val envelope =
            Profile01WireCborDecoder.parseEnvelope(Profile01GoldenVectorWireFixtures.creationSignedFactBytes)!!
        val full = Profile01CborCodec.decodeStrict(envelope.fullCanonicalBytes)
        val authority = full.intKeyMap()!![2]!!.intKeyMap()!!
        return authority[3]!!
    }

    private fun testSigner(
        moduleId: String,
        keyVersion: Long,
    ): Profile01PersistedSignedFactSigner {
        val keyPair =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
        return Profile01PersistedSignedFactSigner.fromPkcs8(
            pkcs8PrivateKey = keyPair.private.encoded,
            signerModuleId = moduleId,
            signerKeyVersion = keyVersion,
        )!!
    }
}

package com.talkback.core.conference.session.profile01

import com.talkback.core.conference.session.profile01.wire.Profile01FactDigest
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactVerifier
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Profile01WireCborDecoderTest {
    @Test
    fun membershipGoldenVector_decodesExpectedFields() {
        val decoded =
            Profile01WireCborDecoder.decodeMembership(
                Profile01GoldenVectorWireFixtures.membershipSignedFactBytes,
            )
        assertTrue(decoded is Profile01WireCborDecoder.DecodeResult.Ready)
        val wire = (decoded as Profile01WireCborDecoder.DecodeResult.Ready).value
        assertEquals(Profile01GoldenVectorWireFixtures.CONFERENCE_ID, wire.conferenceId)
        assertEquals(1L, wire.membershipVersion)
        assertEquals(2L, wire.mediaKeyEpoch)
        assertArrayEquals(
            Profile01GoldenVectorWireFixtures.membershipFactDigest,
            wire.factDigest,
        )
    }

    @Test
    fun creationGoldenVector_decodesExpectedFields() {
        val decoded =
            Profile01WireCborDecoder.decodeCreationSession(
                Profile01GoldenVectorWireFixtures.creationSignedFactBytes,
                Profile01GoldenVectorWireFixtures.sessionMediaSupplement(),
            )
        assertTrue(decoded is Profile01WireCborDecoder.DecodeResult.Ready)
        val wire = (decoded as Profile01WireCborDecoder.DecodeResult.Ready).value
        assertEquals(Profile01GoldenVectorWireFixtures.CONFERENCE_ID, wire.conferenceId)
        assertEquals(Profile01GoldenVectorWireFixtures.CHANNEL_ID, wire.channelId)
        assertEquals(7L, wire.conferenceEpoch)
        assertEquals(0L, wire.membershipVersion)
        assertEquals(1L, wire.mediaKeyEpoch)
        assertEquals(3, wire.membershipView.size)
        assertEquals("239.1.2.3", wire.endpoint.multicastAddress)
        assertEquals(41000, wire.endpoint.mediaPort)
        assertArrayEquals(Profile01GoldenVectorWireFixtures.creationFactDigest, wire.factDigest)
    }

    @Test
    fun sourceDeclarationGoldenVector_decodesAndDerivesAdmissionKey() {
        val decoded =
            Profile01WireCborDecoder.decodeSourceDeclarationMember(
                Profile01GoldenVectorWireFixtures.sourceDeclarationSignedFactBytes,
            )
        assertTrue(decoded is Profile01WireCborDecoder.DecodeResult.Ready)
        val wire = (decoded as Profile01WireCborDecoder.DecodeResult.Ready).value
        assertEquals(Profile01GoldenVectorWireFixtures.CONFERENCE_ID, wire.conferenceId)
        assertEquals(7L, wire.conferenceEpoch)
        assertEquals(1L, wire.membershipVersion)
        assertEquals(2L, wire.mediaKeyEpoch)
        assertEquals("M02", wire.moduleId)
        assertEquals(1L, wire.sourceGeneration)
        assertEquals(0x10203040, wire.ssrc)
        assertEquals(6, wire.sourceAdmissionKey48.size)
        assertArrayEquals(
            Profile01GoldenVectorWireFixtures.sourceDeclarationFactDigest,
            wire.factDigest,
        )
    }

    @Test
    fun goldenVectorTrustBoundary_positiveVectorsPass() {
        val trust = Profile01GoldenVectorWireFixtures.goldenVectorTrustBoundary()
        val creation =
            trust.verifySignedFact(Profile01GoldenVectorWireFixtures.creationSignedFactBytes)
        assertTrue(creation is Profile01WireVerificationResult.Verified)
        val source =
            trust.verifySignedFact(Profile01GoldenVectorWireFixtures.sourceDeclarationSignedFactBytes)
        assertTrue(source is Profile01WireVerificationResult.Verified)
        val membership =
            trust.verifySignedFact(Profile01GoldenVectorWireFixtures.membershipSignedFactBytes)
        assertTrue(membership is Profile01WireVerificationResult.Verified)
    }

    @Test
    fun goldenVectorTrustBoundary_tamperedSignatureRejected() {
        val trust = Profile01GoldenVectorWireFixtures.goldenVectorTrustBoundary()
        val result =
            trust.verifySignedFact(Profile01GoldenVectorWireFixtures.staleSourceDeclarationSignedFactBytes)
        assertTrue(result is Profile01WireVerificationResult.Rejected)
        assertEquals(
            "INVALID_INNER_SIGNATURE",
            (result as Profile01WireVerificationResult.Rejected).reason,
        )
    }

    @Test
    fun factDigest_matchesGoldenVector() {
        val envelope =
            Profile01WireCborDecoder.parseEnvelope(Profile01GoldenVectorWireFixtures.creationSignedFactBytes)!!
        val digest = Profile01FactDigest.computeFactDigest(envelope.fullCanonicalBytes)
        assertArrayEquals(Profile01GoldenVectorWireFixtures.creationFactDigest, digest)
    }

    @Test
    fun signedFactVerifier_acceptsGoldenCreation() {
        val envelope =
            Profile01WireCborDecoder.parseEnvelope(Profile01GoldenVectorWireFixtures.creationSignedFactBytes)!!
        val result =
            Profile01SignedFactVerifier.verifySignatureWithX963(
                envelope.fullCanonicalBytes,
                envelope.signatureRs,
                hex("04984225585d2285c138033d6140e3cef8b91859704e53c313f8b636ba4f9676499734144f46fd19a767a545287c4396b97b69dd38faaea8981adc1a4fed9b401e"),
            )
        assertEquals("PASS", result)
    }

    private fun hex(value: String): ByteArray {
        val clean = value.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val idx = i * 2
            out[i] = clean.substring(idx, idx + 2).toInt(16).toByte()
        }
        return out
    }
}

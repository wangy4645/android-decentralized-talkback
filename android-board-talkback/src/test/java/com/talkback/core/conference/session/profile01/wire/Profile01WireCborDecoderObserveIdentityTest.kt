package com.talkback.core.conference.session.profile01.wire

import com.talkback.core.conference.session.profile01.Profile01DirectedWireFixtures
import com.talkback.core.util.CoordinatorTaskAttribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Profile01WireCborDecoderObserveIdentityTest {
    @Test
    fun creationIdentityUsesFactDigestAsHead() {
        val observed =
            Profile01WireCborDecoder.readObserveIdentity(
                Profile01DirectedWireFixtures.creationSignedFactBytes,
            )
        assertNotNull(observed)
        assertEquals(Profile01WireConstants.FACT_TYPE_CREATION, observed!!.factType)
        assertEquals(Profile01DirectedWireFixtures.CONFERENCE_ID, observed.conferenceIdHex)
        assertEquals(
            Profile01DirectedWireFixtures.creationFactDigest.toHexLower(),
            observed.factDigestHex,
        )
        assertEquals(observed.factDigestHex, observed.headDigestHex)
        assertNotNull(observed.conferenceEpoch)
        assertNotNull(observed.membershipVersion)
        assertNotNull(observed.mediaKeyEpoch)
    }

    @Test
    fun membershipIdentityIsDifferentFactAndHeadFromCreation() {
        val creation =
            Profile01WireCborDecoder.readObserveIdentity(
                Profile01DirectedWireFixtures.creationSignedFactBytes,
            )!!
        val membership =
            Profile01WireCborDecoder.readObserveIdentity(
                Profile01DirectedWireFixtures.membershipSignedFactBytes,
            )!!
        assertEquals(Profile01WireConstants.FACT_TYPE_MEMBERSHIP, membership.factType)
        assertEquals(membership.factDigestHex, membership.headDigestHex)
        assertNotEquals(creation.factDigestHex, membership.factDigestHex)
        assertNotEquals(creation.membershipVersion, membership.membershipVersion)
    }

    @Test
    fun sourceIdentityKeepsGenerationWithoutClaimingFactIsHead() {
        val observed =
            Profile01WireCborDecoder.readObserveIdentity(
                Profile01DirectedWireFixtures.sourceDeclarationSignedFactBytes,
            )
        assertNotNull(observed)
        assertEquals(Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION, observed!!.factType)
        assertEquals(
            Profile01DirectedWireFixtures.sourceDeclarationFactDigest.toHexLower(),
            observed.factDigestHex,
        )
        assertNull(observed.headDigestHex)
        assertNotNull(observed.membershipVersion)
        assertNotNull(observed.mediaKeyEpoch)
    }

    @Test
    fun formattedIdentityContainsHeadAndDigestSeparately() {
        val observed =
            Profile01WireCborDecoder.readObserveIdentity(
                Profile01DirectedWireFixtures.membershipSignedFactBytes,
            )!!
        val line =
            CoordinatorTaskAttribution.shadowSignedFactIdentity(
                sessionId = "sess-1",
                peerModuleId = "M01",
                factType = observed.factType,
                factDigestHex = observed.factDigestHex,
                conferenceEpoch = observed.conferenceEpoch,
                membershipVersion = observed.membershipVersion,
                mediaKeyEpoch = observed.mediaKeyEpoch,
                headDigestHex = observed.headDigestHex,
            )
        assertTrue(line.contains("path=SIGNED_FACT"))
        assertTrue(line.contains("session=sess-1"))
        assertTrue(line.contains("peer=M01"))
        assertTrue(line.contains("factType=2"))
        assertTrue(line.contains("factDigest=${observed.factDigestHex}"))
        assertTrue(line.contains("headDigest=${observed.headDigestHex}"))
        assertTrue(line.contains("membershipVersion=${observed.membershipVersion}"))
        assertTrue(line.contains("mediaKeyEpoch=${observed.mediaKeyEpoch}"))
    }
}

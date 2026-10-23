package com.talkback.core.conference.session.profile01

import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.profile01.wire.Profile01WireMediaKeyPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Profile01RejoinAuthoritativeCatchUpTest {
    private val catchUpTrust =
        Profile01WireTrustBoundary { signedFactBytes ->
            if (signedFactBytes.contentEquals(ByteArray(1))) {
                Profile01WireVerificationResult.Verified(ByteArray(32))
            } else {
                Profile01GoldenVectorWireFixtures.goldenVectorTrustBoundary().verifySignedFact(signedFactBytes)
            }
        }

    @Test
    fun fenceThenStaleHeadRejectsNewMediaKeyContext() {
        val validator = Profile01ConferenceMediaFactValidator(catchUpTrust)
        val ingress =
            Profile01ConferenceMediaFactIngress(
                validator,
                ConferenceSessionMediaGbcPublisherBridge(ConferenceSessionMediaControlFactRegistry()),
            )
        ingestCreationAndMembership(ingress)
        val head = validator.membershipRegistry().current(Profile01GoldenVectorWireFixtures.CONFERENCE_ID)!!
        val packageAtHead = syntheticMediaKeyPackage(head)
        assertTrue(
            validator.verifyMediaKeyPackage(packageAtHead) is Profile01WireVerificationResult.Verified,
        )

        validator.membershipRegistry().fenceConferenceForAuthoritativeCatchUp(
            Profile01GoldenVectorWireFixtures.CONFERENCE_ID,
        )
        assertNull(validator.membershipRegistry().current(Profile01GoldenVectorWireFixtures.CONFERENCE_ID))
        val mismatch =
            validator.verifyMediaKeyPackage(packageAtHead) as Profile01WireVerificationResult.Rejected
        assertEquals("MEMBERSHIP_NOT_CONVERGED", mismatch.reason)
    }

    @Test
    fun catchUpChainThenPackageVerifiesAndStaleMembershipRejected() {
        val validator = Profile01ConferenceMediaFactValidator(catchUpTrust)
        val ingress =
            Profile01ConferenceMediaFactIngress(
                validator,
                ConferenceSessionMediaGbcPublisherBridge(ConferenceSessionMediaControlFactRegistry()),
            )
        validator.membershipRegistry().fenceConferenceForAuthoritativeCatchUp(
            Profile01GoldenVectorWireFixtures.CONFERENCE_ID,
        )
        ingestCreationAndMembership(ingress)
        val head = validator.membershipRegistry().current(Profile01GoldenVectorWireFixtures.CONFERENCE_ID)!!
        val chain =
            Profile01AuthoritativeMembershipChain.validateAgainstCreationAndHead(
                creationSignedFactBytes = Profile01GoldenVectorWireFixtures.creationSignedFactBytes,
                chainSignedFacts = listOf(Profile01GoldenVectorWireFixtures.membershipSignedFactBytes),
                expectedHead = head,
            )
        assertTrue(chain is Profile01AuthoritativeMembershipChain.Validation.Valid)
        assertTrue(
            validator.verifyMediaKeyPackage(syntheticMediaKeyPackage(head))
                is Profile01WireVerificationResult.Verified,
        )

        val replaySameHead =
            ingress.ingestMembershipSignedFact(Profile01GoldenVectorWireFixtures.membershipSignedFactBytes)
        assertTrue(replaySameHead is Profile01MembershipIngressResult.Converged)
        assertEquals(
            head.membershipVersion,
            (replaySameHead as Profile01MembershipIngressResult.Converged).generation.membershipVersion,
        )
        validator.membershipRegistry().fenceConferenceForAuthoritativeCatchUp(
            Profile01GoldenVectorWireFixtures.CONFERENCE_ID,
        )
        ingestCreationOnly(ingress)
        assertNotNull(validator.membershipRegistry().current(Profile01GoldenVectorWireFixtures.CONFERENCE_ID))
        val mismatchOlderPackage =
            validator.verifyMediaKeyPackage(syntheticMediaKeyPackage(head)) as Profile01WireVerificationResult.Rejected
        assertEquals("MEMBERSHIP_NOT_CONVERGED", mismatchOlderPackage.reason)

        val fresh = Profile01ConferenceMediaFactValidator(catchUpTrust)
        ingestCreationOnly(
            Profile01ConferenceMediaFactIngress(
                fresh,
                ConferenceSessionMediaGbcPublisherBridge(ConferenceSessionMediaControlFactRegistry()),
            ),
        )
        val creationHead = fresh.membershipRegistry().current(Profile01GoldenVectorWireFixtures.CONFERENCE_ID)!!
        assertEquals(0L, creationHead.membershipVersion)
        val mismatchVsCreation =
            fresh.verifyMediaKeyPackage(syntheticMediaKeyPackage(head)) as Profile01WireVerificationResult.Rejected
        assertEquals("MEMBERSHIP_CONTEXT_MISMATCH", mismatchVsCreation.reason)
    }

    @Test
    fun brokenChainFailsValidation() {
        val validator = Profile01ConferenceMediaFactValidator(catchUpTrust)
        val ingress =
            Profile01ConferenceMediaFactIngress(
                validator,
                ConferenceSessionMediaGbcPublisherBridge(ConferenceSessionMediaControlFactRegistry()),
            )
        ingestCreationAndMembership(ingress)
        val head = validator.membershipRegistry().current(Profile01GoldenVectorWireFixtures.CONFERENCE_ID)!!
        val invalid =
            Profile01AuthoritativeMembershipChain.validateAgainstCreationAndHead(
                creationSignedFactBytes = Profile01GoldenVectorWireFixtures.creationSignedFactBytes,
                chainSignedFacts = emptyList(),
                expectedHead = head,
            )
        assertTrue(invalid is Profile01AuthoritativeMembershipChain.Validation.Invalid)
        assertEquals(
            "EMPTY_MEMBERSHIP_CHAIN",
            (invalid as Profile01AuthoritativeMembershipChain.Validation.Invalid).reason,
        )
    }

    @Test
    fun fenceDoesNotClearOtherConference() {
        val registry = Profile01MembershipConvergenceRegistry()
        val otherId = "aa".repeat(16)
        registry.seedFromCreation(Profile01GoldenVectorWireFixtures.decodeSessionWireFact())
        registry.fenceConferenceForAuthoritativeCatchUp(otherId)
        assertTrue(registry.current(Profile01GoldenVectorWireFixtures.CONFERENCE_ID) != null)
    }

    private fun ingestCreationOnly(ingress: Profile01ConferenceMediaFactIngress) {
        ingress.ingestCreationSignedFact(
            Profile01GoldenVectorWireFixtures.creationSignedFactBytes,
            Profile01GoldenVectorWireFixtures.sessionMediaSupplement(),
            "wlan0",
        )
    }

    private fun ingestCreationAndMembership(ingress: Profile01ConferenceMediaFactIngress) {
        ingestCreationOnly(ingress)
        val membership =
            ingress.ingestMembershipSignedFact(Profile01GoldenVectorWireFixtures.membershipSignedFactBytes)
        assertTrue(membership is Profile01MembershipIngressResult.Converged)
    }

    private fun syntheticMediaKeyPackage(head: Profile01MembershipGeneration): Profile01WireMediaKeyPackage {
        val conferenceId = hexToBytes(head.conferenceId)
        return Profile01WireMediaKeyPackage(
            signedFactBytes = ByteArray(1),
            factDigest = ByteArray(32),
            fullCanonicalBytes = ByteArray(1),
            conferenceId = conferenceId,
            conferenceEpoch = head.conferenceEpoch,
            ownerModuleId = "M01",
            membershipVersion = head.membershipVersion,
            mediaKeyEpoch = head.mediaKeyEpoch,
            membershipFactDigest = head.generationFactDigest.copyOf(),
            mediaKeyCommitment = head.mediaKeyCommitment.copyOf(),
            recipientModuleId = "M03",
            packageIdentity = ByteArray(16),
            wrappedPek = ByteArray(384),
            gcmNonce = ByteArray(12),
            ciphertext = ByteArray(16),
            gcmTag = ByteArray(16),
            recipientKeyVersion = 1L,
        )
    }

    private fun hexToBytes(value: String): ByteArray {
        val out = ByteArray(value.length / 2)
        for (i in out.indices) {
            out[i] = value.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}

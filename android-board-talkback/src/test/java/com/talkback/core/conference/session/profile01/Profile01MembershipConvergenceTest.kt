package com.talkback.core.conference.session.profile01

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.ControlFactPublishOutcome
import com.talkback.core.conference.session.ControlPlaneConferenceSessionMediaFactPort
import com.talkback.core.conference.session.MemberBindingFact
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 2 — Profile 01 membership fact convergence and full ingress chain.
 */
class Profile01MembershipConvergenceTest {
    private lateinit var registry: ConferenceSessionMediaControlFactRegistry
    private lateinit var bridge: ConferenceSessionMediaGbcPublisherBridge
    private lateinit var ingress: Profile01ConferenceMediaFactIngress
    private lateinit var wiring: ConferenceSessionMediaWiring

    @Before
    fun setUp() {
        registry = ConferenceSessionMediaControlFactRegistry()
        bridge = ConferenceSessionMediaGbcPublisherBridge(registry)
        val validator =
            Profile01ConferenceMediaFactValidator(Profile01GoldenVectorWireFixtures.goldenVectorTrustBoundary())
        ingress = Profile01ConferenceMediaFactIngress(validator, bridge)
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        ConferenceSessionMediaCoordinatorDelegate.factPort =
            ControlPlaneConferenceSessionMediaFactPort(registry)
    }

    @Test
    fun membershipGoldenVector_decodesExpectedGenerationFields() {
        val wire = Profile01GoldenVectorWireFixtures.decodeMembershipWireFact()
        assertEquals(Profile01GoldenVectorWireFixtures.CONFERENCE_ID, wire.conferenceId)
        assertEquals(7L, wire.conferenceEpoch)
        assertEquals(1L, wire.membershipVersion)
        assertEquals(2L, wire.mediaKeyEpoch)
        assertEquals(3, wire.members.size)
        assertTrue(wire.members.any { it.moduleId == "M02" })
        assertArrayEquals(
            Profile01GoldenVectorWireFixtures.creationFactDigest,
            wire.previousMembershipDigest,
        )
    }

    @Test
    fun creationThenMembership_convergesAuthoritativeGeneration() {
        ingestCreation()
        val membership =
            ingress.ingestMembershipSignedFact(Profile01GoldenVectorWireFixtures.membershipSignedFactBytes)
        assertTrue(membership is Profile01MembershipIngressResult.Converged)
        val generation = (membership as Profile01MembershipIngressResult.Converged).generation
        assertEquals(1L, generation.membershipVersion)
        assertEquals(2L, generation.mediaKeyEpoch)
        assertEquals(
            Profile01GoldenVectorWireFixtures.membershipFactDigest.toHex(),
            generation.generationFactDigest.toHex(),
        )
        assertTrue(generation.hasMember("M02"))
    }

    @Test
    fun membershipBeforeCreation_staysPendingUntilCreationSeeds() {
        val early =
            ingress.ingestMembershipSignedFact(Profile01GoldenVectorWireFixtures.membershipSignedFactBytes)
        assertTrue(early is Profile01MembershipIngressResult.Pending)

        ingestCreation()
        val replay =
            ingress.ingestMembershipSignedFact(Profile01GoldenVectorWireFixtures.membershipSignedFactBytes)
        assertTrue(replay is Profile01MembershipIngressResult.Converged)
        assertEquals(1L, (replay as Profile01MembershipIngressResult.Converged).generation.membershipVersion)
    }

    @Test
    fun convergedMembership_allowsSourceDeclarationWithoutHarnessOverlay() {
        val sessionWire = ingestCreationAndMembership()
        val memberWire = Profile01GoldenVectorWireFixtures.decodeMemberSourceWireFact()

        val memberIngress = ingress.ingestMember(memberWire)
        assertEquals(ControlFactPublishOutcome.ACCEPTED, memberIngress.publishOutcome)

        ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStarted(
            sessionWire.conferenceId,
            sessionWire.channelId,
            memberWire.membershipVersion,
        )
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(
            sessionWire.conferenceId,
            memberWire.moduleId,
        )

        val wiring = this.wiring
        val orch = wiring.orchestrator(sessionWire.conferenceId)!!
        assertTrue(
            orch.selection.registry.isInstalledExecutable(
                memberWire.moduleId,
                memberWire.sourceGeneration,
            ),
        )
    }

    @Test
    fun sourceBeforeMembership_isSupersededUntilGenerationConverges() {
        ingestCreation()
        val memberWire = Profile01GoldenVectorWireFixtures.decodeMemberSourceWireFact()
        val early = ingress.ingestMember(memberWire)
        assertNull(early.publishOutcome)
        assertTrue(early.validation is Profile01ValidationResult.NotPublished)

        ingress.ingestMembershipSignedFact(Profile01GoldenVectorWireFixtures.membershipSignedFactBytes)
        val accepted = ingress.ingestMember(memberWire)
        assertEquals(ControlFactPublishOutcome.ACCEPTED, accepted.publishOutcome)
    }

    private fun ingestCreation(): Profile01WireSessionFact {
        val result =
            ingress.ingestCreationSignedFact(
                Profile01GoldenVectorWireFixtures.creationSignedFactBytes,
                Profile01GoldenVectorWireFixtures.sessionMediaSupplement(),
                Slice4MulticastNetworkConstants.DEFAULT_IFACE,
            )
        assertEquals(ControlFactPublishOutcome.ACCEPTED, result.ingress!!.publishOutcome)
        return Profile01GoldenVectorWireFixtures.decodeSessionWireFact()
    }

    private fun ingestCreationAndMembership(): Profile01WireSessionFact {
        val sessionWire = Profile01GoldenVectorWireFixtures.decodeSessionWireFact()
        assertEquals(
            ControlFactPublishOutcome.ACCEPTED,
            ingress.ingestSession(sessionWire, Slice4MulticastNetworkConstants.DEFAULT_IFACE).publishOutcome,
        )
        val membership =
            ingress.ingestMembershipSignedFact(Profile01GoldenVectorWireFixtures.membershipSignedFactBytes)
        assertTrue(membership is Profile01MembershipIngressResult.Converged)
        return sessionWire
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        org.junit.Assert.assertArrayEquals(expected, actual)
    }
}

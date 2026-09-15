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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 2 Slice 5 — Profile 01 wire → validation → bridge → coordinator → wiring → runtime.
 */
class Profile01ConferenceMediaFactIngressChainTest {
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
        ConferenceSessionMediaCoordinatorDelegate.factPort = ControlPlaneConferenceSessionMediaFactPort(registry)
    }

    @Test
    fun goldenWireFact_fullChain_admitsSourceInPhase1Runtime() {
        val sessionWire = ingestCreationAndMembership()
        val memberWire = Profile01GoldenVectorWireFixtures.decodeMemberSourceWireFact()

        ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStarted(
            sessionWire.conferenceId,
            sessionWire.channelId,
            memberWire.membershipVersion,
        )
        assertTrue(wiring.hasSession(sessionWire.conferenceId))

        val memberIngress = ingress.ingestMember(memberWire)
        assertEquals(ControlFactPublishOutcome.ACCEPTED, memberIngress.publishOutcome)

        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(
            sessionWire.conferenceId,
            memberWire.moduleId,
        )

        val orch = wiring.orchestrator(sessionWire.conferenceId)!!
        assertTrue(
            orch.selection.registry.isInstalledExecutable(
                memberWire.moduleId,
                memberWire.sourceGeneration,
            ),
        )
        assertEquals(1, orch.authority.store.currentAdmitted().size)

        val binding =
            MemberBindingFact(
                moduleId = memberWire.moduleId,
                incarnationId = memberWire.sourceGeneration,
                ssrc = memberWire.ssrc,
                sourceAdmissionKey48 = memberWire.sourceAdmissionKey48.copyOf(),
                mediaKeyEpoch = memberWire.mediaKeyEpoch,
            )
        SessionMediaWiringHarness.assertAccepted(
            wiring.admitDatagram(sessionWire.conferenceId, SessionMediaWiringHarness.protectedPacket(binding)),
        )
    }

    @Test
    fun rawSignedFactBytes_fullChain_admitsSourceInPhase1Runtime() {
        val sessionResult =
            ingress.ingestCreationSignedFact(
                Profile01GoldenVectorWireFixtures.creationSignedFactBytes,
                Profile01GoldenVectorWireFixtures.sessionMediaSupplement(),
                Slice4MulticastNetworkConstants.DEFAULT_IFACE,
            )
        assertTrue(sessionResult.decode is Profile01SignedFactDecodeResult.Ready)
        assertEquals(ControlFactPublishOutcome.ACCEPTED, sessionResult.ingress!!.publishOutcome)

        val membershipResult =
            ingress.ingestMembershipSignedFact(Profile01GoldenVectorWireFixtures.membershipSignedFactBytes)
        assertTrue(membershipResult is Profile01MembershipIngressResult.Converged)

        val sessionWire = Profile01GoldenVectorWireFixtures.decodeSessionWireFact()
        ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStarted(
            sessionWire.conferenceId,
            sessionWire.channelId,
            1L,
        )

        val memberResult =
            ingress.ingestSourceDeclarationSignedFact(
                Profile01GoldenVectorWireFixtures.sourceDeclarationSignedFactBytes,
            )
        assertTrue(memberResult.decode is Profile01SignedFactDecodeResult.Ready)
        assertEquals(ControlFactPublishOutcome.ACCEPTED, memberResult.ingress!!.publishOutcome)

        val memberWire = Profile01GoldenVectorWireFixtures.decodeMemberSourceWireFact()
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(
            sessionWire.conferenceId,
            memberWire.moduleId,
        )

        val binding =
            MemberBindingFact(
                moduleId = memberWire.moduleId,
                incarnationId = memberWire.sourceGeneration,
                ssrc = memberWire.ssrc,
                sourceAdmissionKey48 = memberWire.sourceAdmissionKey48.copyOf(),
                mediaKeyEpoch = memberWire.mediaKeyEpoch,
            )
        SessionMediaWiringHarness.assertAccepted(
            wiring.admitDatagram(sessionWire.conferenceId, SessionMediaWiringHarness.protectedPacket(binding)),
        )
    }

    @Test
    fun staleSupersededMember_doesNotReinstallOldSource() {
        val sessionWire = ingestCreationAndMembership()
        ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStarted(
            sessionWire.conferenceId,
            sessionWire.channelId,
            1L,
        )

        val current =
            Profile01GoldenVectorWireFixtures.decodeMemberSourceWireFact().copy(
                sourceGeneration = 2L,
            )
        ingress.ingestMember(current)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(
            sessionWire.conferenceId,
            current.moduleId,
        )

        val stale = Profile01GoldenVectorWireFixtures.decodeMemberSourceWireFact()
        val staleIngress = ingress.ingestMember(stale)
        assertNull(staleIngress.publishOutcome)
        assertTrue(staleIngress.validation is Profile01ValidationResult.NotPublished)

        assertEquals(
            2L,
            registry.member(sessionWire.conferenceId, current.moduleId)!!.membershipIncarnationId,
        )
        val orch = wiring.orchestrator(sessionWire.conferenceId)!!
        assertTrue(orch.selection.registry.isInstalledExecutable(current.moduleId, 2L))
        assertTrue(!orch.selection.registry.isInstalledExecutable(current.moduleId, 1L))
    }

    @Test
    fun invalidSignature_doesNotPublish() {
        ingestCreationAndMembership()

        val result =
            ingress.ingestSourceDeclarationSignedFact(
                Profile01GoldenVectorWireFixtures.staleSourceDeclarationSignedFactBytes,
            )
        assertNull(result.ingress?.publishOutcome)
        assertTrue(result.ingress?.validation is Profile01ValidationResult.Invalid)
        assertNull(registry.member(Profile01GoldenVectorWireFixtures.CONFERENCE_ID, "M02"))
    }

    @Test
    fun repeatPublish_isIdempotent() {
        val sessionWire = Profile01GoldenVectorWireFixtures.decodeSessionWireFact()
        assertEquals(
            ControlFactPublishOutcome.ACCEPTED,
            ingress.ingestSession(sessionWire, Slice4MulticastNetworkConstants.DEFAULT_IFACE).publishOutcome,
        )
        assertEquals(
            ControlFactPublishOutcome.IDEMPOTENT,
            ingress.ingestSession(sessionWire, Slice4MulticastNetworkConstants.DEFAULT_IFACE).publishOutcome,
        )
    }

    private fun ingestCreationAndMembership(): Profile01WireSessionFact {
        val sessionIngress =
            ingress.ingestSession(
                Profile01GoldenVectorWireFixtures.decodeSessionWireFact(),
                Slice4MulticastNetworkConstants.DEFAULT_IFACE,
            )
        assertEquals(ControlFactPublishOutcome.ACCEPTED, sessionIngress.publishOutcome)
        val membership =
            ingress.ingestMembershipSignedFact(Profile01GoldenVectorWireFixtures.membershipSignedFactBytes)
        assertTrue(membership is Profile01MembershipIngressResult.Converged)
        return Profile01GoldenVectorWireFixtures.decodeSessionWireFact()
    }
}

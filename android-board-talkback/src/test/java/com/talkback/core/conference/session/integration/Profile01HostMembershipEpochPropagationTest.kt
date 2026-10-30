package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.ControlFactPublishOutcome
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01GoldenVectorWireFixtures
import com.talkback.core.conference.session.profile01.Profile01MembershipApplyResult
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.wire.Profile01DerivedMediaKeyMaterial
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P0′-HOST-EPOCH-PROPAGATION — host membership bump must replay epoch=2 into live wiring.
 */
class Profile01HostMembershipEpochPropagationTest {
    private val sessionId = "host-epoch-session"
    private val channelId = Profile01GoldenVectorWireFixtures.CHANNEL_ID
    private val conferenceId = Profile01GoldenVectorWireFixtures.CONFERENCE_ID

    private lateinit var registry: ConferenceSessionMediaControlFactRegistry
    private lateinit var ingress: Profile01ConferenceMediaFactIngress
    private lateinit var wiring: ConferenceSessionMediaWiring
    private lateinit var sessionIndex: MeetingProfile01ConferenceSessionIndex
    private lateinit var supplementRegistry: Profile01SessionMediaSupplementRegistry
    private lateinit var hostProjection: Profile01HostLocalSessionFactProjection
    private lateinit var factPort: MeetingProfile01AwareConferenceSessionMediaFactPort

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        registry = ConferenceSessionMediaControlFactRegistry()
        supplementRegistry = Profile01SessionMediaSupplementRegistry()
        val bridge = ConferenceSessionMediaGbcPublisherBridge(registry)
        ingress =
            Profile01ConferenceMediaFactIngress(
                validator =
                    Profile01ConferenceMediaFactValidator(
                        Profile01GoldenVectorWireFixtures.goldenVectorTrustBoundary(),
                    ),
                publisherBridge = bridge,
                supplementRegistry = supplementRegistry,
            )
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        sessionIndex = MeetingProfile01ConferenceSessionIndex()
        sessionIndex.registerSession(sessionId, channelId, rosterEpoch = 0L)
        sessionIndex.bindConferenceId(sessionId, conferenceId)
        factPort = MeetingProfile01AwareConferenceSessionMediaFactPort(registry, sessionIndex)
        ConferenceSessionMediaCoordinatorDelegate.factPort = factPort
        hostProjection =
            Profile01HostLocalSessionFactProjection(
                readSignedCreationFact = { Profile01GoldenVectorWireFixtures.creationSignedFactBytes },
                readSignedSourceFact = { null },
                ingress = ingress,
                supplementRegistry = supplementRegistry,
                sessionIndex = sessionIndex,
                registry = registry,
                networkInterfaceName = { Slice4MulticastNetworkConstants.DEFAULT_IFACE },
            )
        seedSupplement(mediaKeyEpoch = 1L, membershipVersion = 0L)
    }

    @After
    fun tearDown() {
        MeetingProductMediaShadow.enabled = false
        ConferenceSessionMediaCoordinatorDelegate.factPort = null
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun hostMembershipReplay_rotatesLiveSessionFromEpoch1To2() {
        val creation =
            ingress.ingestCreationSignedFact(
                Profile01GoldenVectorWireFixtures.creationSignedFactBytes,
                Profile01GoldenVectorWireFixtures.sessionMediaSupplement(),
                Slice4MulticastNetworkConstants.DEFAULT_IFACE,
            )
        assertEquals(ControlFactPublishOutcome.ACCEPTED, creation.ingress!!.publishOutcome)

        val sessionWire = Profile01GoldenVectorWireFixtures.decodeSessionWireFact()
        ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStarted(
            sessionId,
            channelId,
            sessionWire.membershipVersion,
        )
        assertTrue(ConferenceSessionMediaBridge.hasSession(sessionId))
        assertEquals(1L, wiring.currentMediaKeyEpoch(sessionId))

        val bindingEpoch1 =
            SessionMediaWiringHarness.memberBinding(
                "M02",
                mediaKeyEpoch = 1L,
                incarnationId = 10L,
                ssrc = 0x22001001,
            )
        assertTrue(wiring.installMember(sessionId, bindingEpoch1))

        simulateHostMembershipGenerationOnly()

        seedSupplement(mediaKeyEpoch = 2L, membershipVersion = 1L)
        assertEquals(1L, registry.session(conferenceId)!!.mediaKeyEpoch)

        assertEquals(
            HostMembershipReplayOutcome.APPLIED,
            hostProjection.prepareHostMembershipRegistry(
                sessionId = sessionId,
                signedMembershipBytes = Profile01GoldenVectorWireFixtures.membershipSignedFactBytes,
            ),
        )
        val memberIngress = ingress.ingestMember(Profile01GoldenVectorWireFixtures.decodeMemberSourceWireFact())
        assertEquals(ControlFactPublishOutcome.ACCEPTED, memberIngress.publishOutcome)
        sessionIndex.markMediaConnected(sessionId, "M02")
        assertEquals(
            HostMembershipReplayOutcome.APPLIED,
            hostProjection.replayHostMembershipWiring(sessionId, "M02"),
        )
        assertEquals(2L, registry.session(conferenceId)!!.mediaKeyEpoch)
        assertEquals(2L, wiring.currentMediaKeyEpoch(sessionId))
        assertTrue(wiring.runtimeSnapshot(sessionId)!!.catalogEntries > 0)

        val memberBinding = factPort.memberBinding(sessionId, "M02")!!
        SessionMediaWiringHarness.assertAccepted(
            wiring.admitDatagram(sessionId, SessionMediaWiringHarness.protectedPacket(memberBinding)),
        )
    }

    @Test
    fun hostMembershipReplay_idempotentSecondCall_andRejectsDowngrade() {
        ingestCreationAndStartSessionEpoch1()
        simulateHostMembershipGenerationOnly()
        seedSupplement(mediaKeyEpoch = 2L, membershipVersion = 1L)

        ingress.ingestMember(Profile01GoldenVectorWireFixtures.decodeMemberSourceWireFact())
        sessionIndex.markMediaConnected(sessionId, "M02")
        hostProjection.prepareHostMembershipRegistry(sessionId, Profile01GoldenVectorWireFixtures.membershipSignedFactBytes)
        hostProjection.replayHostMembershipWiring(sessionId, "M02")
        hostProjection.prepareHostMembershipRegistry(sessionId, Profile01GoldenVectorWireFixtures.membershipSignedFactBytes)
        hostProjection.replayHostMembershipWiring(sessionId, "M02")
        assertEquals(2L, wiring.currentMediaKeyEpoch(sessionId))

        val epoch1Fact =
            SessionMediaWiringHarness.sessionFact(
                sessionId,
                generation = 1L,
                mediaKeyEpoch = 1L,
            )
        assertFalse(wiring.startSession(epoch1Fact))
        assertEquals(2L, wiring.currentMediaKeyEpoch(sessionId))
    }

    private fun ingestCreationAndStartSessionEpoch1() {
        val creation =
            ingress.ingestCreationSignedFact(
                Profile01GoldenVectorWireFixtures.creationSignedFactBytes,
                Profile01GoldenVectorWireFixtures.sessionMediaSupplement(),
                Slice4MulticastNetworkConstants.DEFAULT_IFACE,
            )
        assertEquals(ControlFactPublishOutcome.ACCEPTED, creation.ingress!!.publishOutcome)
        val sessionWire = Profile01GoldenVectorWireFixtures.decodeSessionWireFact()
        ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStarted(
            sessionId,
            channelId,
            sessionWire.membershipVersion,
        )
        assertEquals(1L, wiring.currentMediaKeyEpoch(sessionId))
    }

    private fun simulateHostMembershipGenerationOnly() {
        val wire = Profile01GoldenVectorWireFixtures.decodeMembershipWireFact()
        val applied = ingress.membershipRegistry().applyMembership(wire)
        assertTrue(
            applied is Profile01MembershipApplyResult.Accepted ||
                applied is Profile01MembershipApplyResult.Idempotent,
        )
    }

    private fun seedSupplement(
        mediaKeyEpoch: Long,
        membershipVersion: Long,
    ) {
        val base = Phase1MediaHarness.masterKey.copyOf()
        if (mediaKeyEpoch > 1L) {
            base[0] = (base[0] + mediaKeyEpoch.toInt()).toByte()
        }
        supplementRegistry.put(
            Profile01DerivedMediaKeyMaterial(
                conferenceId = conferenceId,
                conferenceEpoch = 7L,
                membershipVersion = membershipVersion,
                mediaKeyEpoch = mediaKeyEpoch,
                masterKey = base,
                masterSalt = Phase1MediaHarness.masterSalt.copyOf(),
                keyContextHint64 = Phase1MediaHarness.keyContextHint64.copyOf(),
                membershipKeyContextDigest = ByteArray(32),
            ),
        )
    }
}

package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.ControlFactPublishOutcome
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.integration.cutover.MulticastAudibleCutoverReadiness
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P0′-POST-ROTATE-BINDING-REINSTALL — production ordering: registry/sources before wiring replay.
 */
class Profile01PostRotateBindingReinstallTest {
    private val sessionId = "post-rotate-session"
    private val channelId = Profile01GoldenVectorWireFixtures.CHANNEL_ID
    private val conferenceId = Profile01GoldenVectorWireFixtures.CONFERENCE_ID
    private val localModuleId = "M02"

    private lateinit var registry: ConferenceSessionMediaControlFactRegistry
    private lateinit var ingress: Profile01ConferenceMediaFactIngress
    private lateinit var wiring: ConferenceSessionMediaWiring
    private lateinit var sessionIndex: MeetingProfile01ConferenceSessionIndex
    private lateinit var supplementRegistry: Profile01SessionMediaSupplementRegistry
    private lateinit var hostProjection: Profile01HostLocalSessionFactProjection
    private lateinit var factPort: MeetingProfile01AwareConferenceSessionMediaFactPort

    /** Stands in for [MeetingProfile01SourceOriginBridge.readSignedSourceFact] — committed bytes only. */
    private var hostSignedSource: ByteArray? = null

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
        sessionIndex.markMediaConnected(sessionId, localModuleId)
        factPort = MeetingProfile01AwareConferenceSessionMediaFactPort(registry, sessionIndex)
        ConferenceSessionMediaCoordinatorDelegate.factPort = factPort
        hostProjection =
            Profile01HostLocalSessionFactProjection(
                readSignedCreationFact = { Profile01GoldenVectorWireFixtures.creationSignedFactBytes },
                readSignedSourceFact = { hostSignedSource?.copyOf() },
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
        hostSignedSource = null
        MeetingProductMediaShadow.enabled = false
        ConferenceSessionMediaCoordinatorDelegate.factPort = null
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun productionOrdering_rotateThenReinstallsCurrentEpochBindings() {
        ingestCreationAndStartEpoch1()
        val peerEpoch1 =
            SessionMediaWiringHarness.memberBinding(
                localModuleId,
                mediaKeyEpoch = 1L,
                incarnationId = 10L,
                ssrc = 0x22002001,
            )
        assertTrue(wiring.installMember(sessionId, peerEpoch1))
        assertEquals(1, wiring.runtimeSnapshot(sessionId)!!.catalogEntries)

        simulateHostMembershipApplyOnly()
        seedSupplement(mediaKeyEpoch = 2L, membershipVersion = 1L)

        assertEquals(
            HostMembershipReplayOutcome.APPLIED,
            hostProjection.prepareHostMembershipRegistry(
                sessionId,
                Profile01GoldenVectorWireFixtures.membershipSignedFactBytes,
            ),
        )
        assertEquals(2L, registry.session(conferenceId)!!.mediaKeyEpoch)

        // Authoritative local SOURCE exists but is absent from the host registry before replay.
        assertNull(registry.member(conferenceId, localModuleId))
        hostSignedSource = Profile01GoldenVectorWireFixtures.sourceDeclarationSignedFactBytes

        assertEquals(
            HostMembershipReplayOutcome.APPLIED,
            hostProjection.replayHostMembershipWiring(sessionId, localModuleId),
        )

        assertEquals(2L, wiring.currentMediaKeyEpoch(sessionId))
        assertTrue(registry.memberModuleIdsAtHead(conferenceId).contains(localModuleId))
        val snap = wiring.runtimeSnapshot(sessionId)!!
        assertTrue(snap.catalogEntries > 0)
        assertTrue(snap.admittedCount > 0)
        assertEquals(2L, factPort.memberBinding(sessionId, localModuleId)!!.mediaKeyEpoch)
        assertEquals(
            Profile01GoldenVectorWireFixtures.decodeMemberSourceWireFact().sourceGeneration,
            ConferenceSessionMediaBridge.currentIncarnation(sessionId, localModuleId),
        )

        val memberBinding = factPort.memberBinding(sessionId, localModuleId)!!
        SessionMediaWiringHarness.assertAccepted(
            wiring.admitDatagram(sessionId, SessionMediaWiringHarness.protectedPacket(memberBinding)),
        )

        val readiness = MulticastAudibleCutoverReadiness.evaluate(sessionId, localModuleId)
        assertFalse(readiness.missing.contains("LOCAL_TX_BINDING_NOT_READY"))
    }

    @Test
    fun noAuthoritativeLocalSource_leavesCatalogEmpty() {
        ingestCreationAndStartEpoch1()
        simulateHostMembershipApplyOnly()
        seedSupplement(mediaKeyEpoch = 2L, membershipVersion = 1L)

        assertEquals(
            HostMembershipReplayOutcome.APPLIED,
            hostProjection.prepareHostMembershipRegistry(
                sessionId,
                Profile01GoldenVectorWireFixtures.membershipSignedFactBytes,
            ),
        )
        assertEquals(
            HostMembershipReplayOutcome.APPLIED,
            hostProjection.replayHostMembershipWiring(sessionId, localModuleId),
        )

        assertEquals(2L, wiring.currentMediaKeyEpoch(sessionId))
        assertEquals(0, wiring.runtimeSnapshot(sessionId)!!.catalogEntries)
    }

    /**
     * Negative regression — a SOURCE that is not authoritative must never reach the local catalog,
     * even though fixing the release wiring depends on local projection.
     */
    @Test
    fun unverifiedSignedSource_mustNotBecomeLocalRegistryBinding() {
        ingestCreationAndStartEpoch1()
        simulateHostMembershipApplyOnly()
        seedSupplement(mediaKeyEpoch = 2L, membershipVersion = 1L)

        hostProjection.prepareHostMembershipRegistry(
            sessionId,
            Profile01GoldenVectorWireFixtures.membershipSignedFactBytes,
        )
        hostSignedSource = Profile01GoldenVectorWireFixtures.staleSourceDeclarationSignedFactBytes

        hostProjection.replayHostMembershipWiring(sessionId, localModuleId)

        assertEquals(2L, wiring.currentMediaKeyEpoch(sessionId))
        assertNull(registry.member(conferenceId, localModuleId))
        assertEquals(0, wiring.runtimeSnapshot(sessionId)!!.catalogEntries)
        assertTrue(
            MulticastAudibleCutoverReadiness
                .evaluate(sessionId, localModuleId)
                .missing
                .contains("LOCAL_TX_BINDING_NOT_READY"),
        )
    }

    @Test
    fun idempotentMembershipReplay_doesNotAdvanceEpoch() {
        ingestCreationAndStartEpoch1()
        simulateHostMembershipApplyOnly()
        seedSupplement(mediaKeyEpoch = 2L, membershipVersion = 1L)

        hostProjection.prepareHostMembershipRegistry(sessionId, Profile01GoldenVectorWireFixtures.membershipSignedFactBytes)
        hostSignedSource = Profile01GoldenVectorWireFixtures.sourceDeclarationSignedFactBytes
        hostProjection.replayHostMembershipWiring(sessionId, localModuleId)
        val epochAfterFirst = wiring.currentMediaKeyEpoch(sessionId)!!

        hostProjection.prepareHostMembershipRegistry(sessionId, Profile01GoldenVectorWireFixtures.membershipSignedFactBytes)
        hostProjection.replayHostMembershipWiring(sessionId, localModuleId)
        assertEquals(epochAfterFirst, wiring.currentMediaKeyEpoch(sessionId))
        assertTrue(wiring.runtimeSnapshot(sessionId)!!.catalogEntries > 0)
    }

    private fun ingestCreationAndStartEpoch1() {
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

    private fun simulateHostMembershipApplyOnly() {
        val applied =
            ingress.membershipRegistry().applyMembership(
                Profile01GoldenVectorWireFixtures.decodeMembershipWireFact(),
            )
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

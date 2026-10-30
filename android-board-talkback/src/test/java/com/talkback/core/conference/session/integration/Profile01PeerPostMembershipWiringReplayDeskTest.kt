package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceMediaMemberControlFact
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

/** Peer leave/membership wire — epoch rotation + binding reinstall after membership head. */
class Profile01PeerPostMembershipWiringReplayDeskTest {
    private val sessionId = "peer-post-membership-replay"
    private val channelId = Profile01GoldenVectorWireFixtures.CHANNEL_ID
    private val conferenceId = Profile01GoldenVectorWireFixtures.CONFERENCE_ID
    private val localModuleId = "M02"
    private val remoteModuleId = "M01"

    private lateinit var registry: ConferenceSessionMediaControlFactRegistry
    private lateinit var ingress: Profile01ConferenceMediaFactIngress
    private lateinit var wiring: ConferenceSessionMediaWiring
    private lateinit var sessionIndex: MeetingProfile01ConferenceSessionIndex
    private lateinit var supplementRegistry: Profile01SessionMediaSupplementRegistry
    private lateinit var factPort: MeetingProfile01AwareConferenceSessionMediaFactPort
    private lateinit var peerReplay: Profile01PeerMembershipWiringReplay
    private var localSignedSource: ByteArray? = null
    private var consumptionReadyCount = 0

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
        val materializer =
            Profile01ShadowMemberBindingMaterializer(
                factPort = factPort,
                ingress = ingress,
                localModuleId = { localModuleId },
                readSignedLocalSource = { localSignedSource?.copyOf() },
                retryLocalSourceBuild = { consumptionReadyCount++ },
            )
        peerReplay =
            Profile01PeerMembershipWiringReplay(
                ingress = ingress,
                sessionIndex = sessionIndex,
                registry = registry,
                networkInterfaceName = { Slice4MulticastNetworkConstants.DEFAULT_IFACE },
                localModuleId = { localModuleId },
                creationSignedBytesForSession = { Profile01GoldenVectorWireFixtures.creationSignedFactBytes },
                onLocalMembershipHeadConsumptionReady = { consumptionReadyCount++ },
                memberBindingMaterializer = materializer,
            )
        ingestCreationAndStartEpoch1()
    }

    @After
    fun tearDown() {
        localSignedSource = null
        MeetingProductMediaShadow.enabled = false
        ConferenceSessionMediaCoordinatorDelegate.factPort = null
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun peerReplay_rotatesEpochAndReinstallsBindingAtHead() {
        val epoch1 =
            SessionMediaWiringHarness.memberBinding(
                localModuleId,
                mediaKeyEpoch = 1L,
                incarnationId = 10L,
                ssrc = 0x22002002,
            )
        assertTrue(wiring.installMember(sessionId, epoch1))

        applyMembershipHead()
        seedSupplement(mediaKeyEpoch = 2L, membershipVersion = 1L)
        localSignedSource = Profile01GoldenVectorWireFixtures.sourceDeclarationSignedFactBytes

        assertEquals(
            PeerMembershipWiringReplayOutcome.APPLIED,
            peerReplay.replayAfterMembershipWireApplied(sessionId),
        )

        assertEquals(2L, wiring.currentMediaKeyEpoch(sessionId))
        assertTrue(registry.memberModuleIdsAtHead(conferenceId).contains(localModuleId))
        val snap = wiring.runtimeSnapshot(sessionId)!!
        assertTrue(snap.catalogEntries > 0)
        assertTrue(snap.admittedCount > 0)
        assertTrue(consumptionReadyCount > 0)
        assertFalse(
            MulticastAudibleCutoverReadiness
                .evaluate(sessionId, localModuleId)
                .missing
                .contains("LOCAL_TX_BINDING_NOT_READY"),
        )
    }

    @Test
    fun peerReplay_deferredWithoutSupplement_thenCompletesAfterMediaKeyEpochReady() {
        applyMembershipHead()
        assertEquals(
            PeerMembershipWiringReplayOutcome.DEFERRED_NO_SUPPLEMENT,
            peerReplay.replayAfterMembershipWireApplied(sessionId),
        )
        assertEquals(1L, wiring.currentMediaKeyEpoch(sessionId))

        seedSupplement(mediaKeyEpoch = 2L, membershipVersion = 1L)
        localSignedSource = Profile01GoldenVectorWireFixtures.sourceDeclarationSignedFactBytes
        assertEquals(
            PeerMembershipWiringReplayOutcome.APPLIED,
            peerReplay.replayAfterMediaKeyAppliedIfMembershipReady(sessionId),
        )
        assertEquals(2L, wiring.currentMediaKeyEpoch(sessionId))
    }

    @Test
    fun peerReplay_withoutOriginCreationBytes_syncsFromPublishedSession() {
        val epoch1 =
            SessionMediaWiringHarness.memberBinding(
                localModuleId,
                mediaKeyEpoch = 1L,
                incarnationId = 10L,
                ssrc = 0x22002002,
            )
        assertTrue(wiring.installMember(sessionId, epoch1))
        val peerReplayNoCreationBytes =
            Profile01PeerMembershipWiringReplay(
                ingress = ingress,
                sessionIndex = sessionIndex,
                registry = registry,
                networkInterfaceName = { Slice4MulticastNetworkConstants.DEFAULT_IFACE },
                localModuleId = { localModuleId },
                creationSignedBytesForSession = { null },
                onLocalMembershipHeadConsumptionReady = { consumptionReadyCount++ },
                memberBindingMaterializer =
                    Profile01ShadowMemberBindingMaterializer(
                        factPort = factPort,
                        ingress = ingress,
                        localModuleId = { localModuleId },
                        readSignedLocalSource = { localSignedSource?.copyOf() },
                        retryLocalSourceBuild = { consumptionReadyCount++ },
                    ),
            )
        applyMembershipHead()
        assertEquals(
            PeerMembershipWiringReplayOutcome.DEFERRED_NO_SUPPLEMENT,
            peerReplayNoCreationBytes.replayAfterMembershipWireApplied(sessionId),
        )
        seedSupplement(mediaKeyEpoch = 2L, membershipVersion = 1L)
        localSignedSource = Profile01GoldenVectorWireFixtures.sourceDeclarationSignedFactBytes
        assertEquals(
            PeerMembershipWiringReplayOutcome.APPLIED,
            peerReplayNoCreationBytes.replayAfterMediaKeyAppliedIfMembershipReady(sessionId),
        )
        assertEquals(2L, wiring.currentMediaKeyEpoch(sessionId))
    }

    @Test
    fun peerReplay_afterEpochRotation_reinstallsRemoteBindingWhenHeadRegistryPresent() {
        applyMembershipHead()
        seedSupplement(mediaKeyEpoch = 2L, membershipVersion = 1L)
        localSignedSource = Profile01GoldenVectorWireFixtures.sourceDeclarationSignedFactBytes

        assertEquals(
            PeerMembershipWiringReplayOutcome.APPLIED,
            peerReplay.replayAfterMembershipWireApplied(sessionId),
        )
        assertEquals(2L, wiring.currentMediaKeyEpoch(sessionId))
        val session = registry.session(conferenceId)!!
        assertEquals(2L, session.mediaKeyEpoch)

        assertEquals(
            ControlFactPublishOutcome.ACCEPTED,
            registry.publishMember(
                remoteMemberFact(
                    moduleId = remoteModuleId,
                    sourceGeneration = 20L,
                    mediaKeyEpoch = 2L,
                    membershipVersion = session.membershipVersion,
                ),
            ),
        )

        peerReplay.replayMemberBindingsAfterMediaKeyEpochRotated(sessionId)

        val snap = wiring.runtimeSnapshot(sessionId)!!
        assertTrue(snap.catalogEntries >= 2)
        assertFalse(
            MulticastAudibleCutoverReadiness
                .evaluate(sessionId, localModuleId)
                .missing
                .contains("REMOTE_MULTICAST_RX_SOURCE_NOT_READY"),
        )
    }

    @Test
    fun peerReplay_delayedRemoteHeadBinding_installsOnPostRotateReplayHook() {
        applyMembershipHead()
        seedSupplement(mediaKeyEpoch = 2L, membershipVersion = 1L)
        localSignedSource = Profile01GoldenVectorWireFixtures.sourceDeclarationSignedFactBytes
        assertEquals(
            PeerMembershipWiringReplayOutcome.APPLIED,
            peerReplay.replayAfterMembershipWireApplied(sessionId),
        )
        val session = registry.session(conferenceId)!!
        assertEquals(1, wiring.runtimeSnapshot(sessionId)!!.catalogEntries)

        assertEquals(
            ControlFactPublishOutcome.ACCEPTED,
            registry.publishMember(
                remoteMemberFact(
                    moduleId = remoteModuleId,
                    sourceGeneration = 21L,
                    mediaKeyEpoch = 2L,
                    membershipVersion = session.membershipVersion,
                ),
            ),
        )
        peerReplay.replayMemberBindingsAfterMediaKeyEpochRotated(sessionId)
        assertTrue(wiring.runtimeSnapshot(sessionId)!!.catalogEntries >= 2)
    }

    @Test
    fun peerReplay_withoutAuthoritativeLocalSource_keepsCatalogEmpty() {
        applyMembershipHead()
        seedSupplement(mediaKeyEpoch = 2L, membershipVersion = 1L)
        localSignedSource = null

        assertEquals(
            PeerMembershipWiringReplayOutcome.APPLIED,
            peerReplay.replayAfterMembershipWireApplied(sessionId),
        )
        assertEquals(2L, wiring.currentMediaKeyEpoch(sessionId))
        assertNull(registry.member(conferenceId, localModuleId))
        assertEquals(0, wiring.runtimeSnapshot(sessionId)!!.catalogEntries)
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

    private fun applyMembershipHead() {
        val applied =
            ingress.membershipRegistry().applyMembership(
                Profile01GoldenVectorWireFixtures.decodeMembershipWireFact(),
            )
        assertTrue(
            applied is Profile01MembershipApplyResult.Accepted ||
                applied is Profile01MembershipApplyResult.Idempotent,
        )
    }

    private fun remoteMemberFact(
        moduleId: String,
        sourceGeneration: Long,
        mediaKeyEpoch: Long,
        membershipVersion: Long,
    ) = ConferenceMediaMemberControlFact(
        conferenceId = conferenceId,
        moduleId = moduleId,
        membershipIncarnationId = sourceGeneration,
        ssrc = (0x31000000 + sourceGeneration).toInt(),
        sourceAdmissionKey48 = ByteArray(6) { (sourceGeneration + moduleId.last().code).toByte() },
        mediaKeyEpoch = mediaKeyEpoch,
        membershipVersion = membershipVersion,
        factGeneration = sourceGeneration,
    )

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

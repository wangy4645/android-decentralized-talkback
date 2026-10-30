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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P0′-SOURCE-SUCCESSION-COMPLETION — host-local replay must drive the replacement completion
 * seam before member-ready, otherwise a successor incarnation stays `DEFERRED_REPLACE_PENDING`
 * forever (nothing else re-drives member-ready on the host path).
 *
 * Starts from a populated gen1 catalog on purpose — an empty catalog cannot reproduce the field.
 */
class Profile01SourceSuccessionCompletionTest {
    private val sessionId = "succession-completion-session"
    private val channelId = Profile01GoldenVectorWireFixtures.CHANNEL_ID
    private val conferenceId = Profile01GoldenVectorWireFixtures.CONFERENCE_ID
    private val localModuleId = "M02"
    private val peerModuleId = "M03"

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
        ingress =
            Profile01ConferenceMediaFactIngress(
                validator =
                    Profile01ConferenceMediaFactValidator(
                        Profile01GoldenVectorWireFixtures.goldenVectorTrustBoundary(),
                    ),
                publisherBridge = ConferenceSessionMediaGbcPublisherBridge(registry),
                supplementRegistry = supplementRegistry,
            )
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        sessionIndex = MeetingProfile01ConferenceSessionIndex()
        sessionIndex.registerSession(sessionId, channelId, rosterEpoch = 0L)
        sessionIndex.bindConferenceId(sessionId, conferenceId)
        sessionIndex.markMediaConnected(sessionId, localModuleId)
        sessionIndex.markMediaConnected(sessionId, peerModuleId)
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

    /**
     * Cross-epoch succession — `replaceMember` legitimately fails because epoch rotation already
     * retired the gen1 incarnation, so `MEMBER_REPLACED` lands on `WIRING_REJECTED`. That is an
     * accepted semantic/observability debt: the obligation is still completed and the successor
     * installs. Asserted explicitly so nobody "fixes" the lifecycle after reading the log.
     */
    @Test
    fun hostReplay_completesSuccession_thenInstallsCurrentIncarnation() {
        installGen1AtEpoch1()
        advanceRegistryToEpoch2()
        publishSuccessorsAtHead()

        assertTrue(factPort.memberReplacePending(sessionId, localModuleId))
        assertTrue(factPort.memberReplacePending(sessionId, peerModuleId))

        assertEquals(
            HostMembershipReplayOutcome.APPLIED,
            hostProjection.replayHostMembershipWiring(sessionId, localModuleId),
        )

        assertEquals(2L, wiring.currentMediaKeyEpoch(sessionId))
        assertLastOutcome(ShadowHook.MEMBER_REPLACED, localModuleId, ShadowOutcome.WIRING_REJECTED)
        assertLastOutcome(ShadowHook.MEMBER_MEDIA_READY, localModuleId, ShadowOutcome.APPLIED)
        assertFalse(factPort.memberReplacePending(sessionId, localModuleId))

        assertEquals(2L, ConferenceSessionMediaBridge.currentIncarnation(sessionId, localModuleId))
        assertEquals(2L, registry.member(conferenceId, localModuleId)!!.membershipIncarnationId)
        assertEquals(2L, registry.member(conferenceId, localModuleId)!!.mediaKeyEpoch)
        val localBinding = factPort.memberBinding(sessionId, localModuleId)!!
        assertEquals(2L, localBinding.mediaKeyEpoch)
        assertEquals(2L, localBinding.incarnationId)

        SessionMediaWiringHarness.assertAccepted(
            wiring.admitDatagram(sessionId, SessionMediaWiringHarness.protectedPacket(localBinding)),
        )
        assertFalse(
            MulticastAudibleCutoverReadiness
                .evaluate(sessionId, localModuleId)
                .missing
                .contains("LOCAL_TX_BINDING_NOT_READY"),
        )
    }

    /**
     * The `SOURCE_GENERATION_MISMATCH` TX fence compares the registry member incarnation against
     * the local source commitment — a different layer from the wiring catalog. After completion
     * both views must agree on the successor, otherwise TX stays fenced.
     */
    @Test
    fun hostReplay_registryAndCatalogAgreeOnSuccessorGeneration() {
        installGen1AtEpoch1()
        advanceRegistryToEpoch2()
        publishSuccessorsAtHead()

        hostProjection.replayHostMembershipWiring(sessionId, localModuleId)

        val registryIncarnation = registry.member(conferenceId, localModuleId)!!.membershipIncarnationId
        val catalogIncarnation = ConferenceSessionMediaBridge.currentIncarnation(sessionId, localModuleId)
        assertEquals(registryIncarnation, catalogIncarnation)
        assertEquals(
            registry.session(conferenceId)!!.mediaKeyEpoch,
            factPort.memberBinding(sessionId, localModuleId)!!.mediaKeyEpoch,
        )
    }

    /** Full authoritative module set — not just the host's own source. */
    @Test
    fun hostReplay_completesPeerSuccessionToo() {
        installGen1AtEpoch1()
        advanceRegistryToEpoch2()
        publishSuccessorsAtHead()

        hostProjection.replayHostMembershipWiring(sessionId, localModuleId)

        assertFalse(factPort.memberReplacePending(sessionId, peerModuleId))
        assertLastOutcome(ShadowHook.MEMBER_MEDIA_READY, peerModuleId, ShadowOutcome.APPLIED)
        assertEquals(2L, ConferenceSessionMediaBridge.currentIncarnation(sessionId, peerModuleId))
        assertEquals(2, wiring.runtimeSnapshot(sessionId)!!.catalogEntries)
    }

    /** No obligation — completion seam must no-op, never disturb an installed current binding. */
    @Test
    fun hostReplay_withoutObligation_isNoOp() {
        installGen1AtEpoch1()

        assertFalse(factPort.memberReplacePending(sessionId, localModuleId))
        hostProjection.replayHostMembershipWiring(sessionId, localModuleId)

        assertLastOutcome(ShadowHook.MEMBER_REPLACED, localModuleId, ShadowOutcome.DEFERRED_NO_REPLACE_PAIR)
        assertEquals(1L, ConferenceSessionMediaBridge.currentIncarnation(sessionId, localModuleId))
        assertEquals(1L, wiring.currentMediaKeyEpoch(sessionId))
    }

    /** Stale fence — a delayed gen1 must not replace or downgrade an installed gen2. */
    @Test
    fun delayedGen1_cannotDowngradeInstalledGen2() {
        installGen1AtEpoch1()
        advanceRegistryToEpoch2()
        publishSuccessorsAtHead()
        hostProjection.replayHostMembershipWiring(sessionId, localModuleId)
        assertEquals(2L, ConferenceSessionMediaBridge.currentIncarnation(sessionId, localModuleId))

        assertEquals(
            ControlFactPublishOutcome.REJECTED_STALE,
            publishMemberAtHead(localModuleId, incarnationId = 1L, keySuffix = 0x11, ssrc = 0x33000101),
        )

        hostProjection.replayHostMembershipWiring(sessionId, localModuleId)

        assertEquals(2L, ConferenceSessionMediaBridge.currentIncarnation(sessionId, localModuleId))
        assertEquals(2L, registry.member(conferenceId, localModuleId)!!.membershipIncarnationId)
    }

    private fun installGen1AtEpoch1() {
        val creation =
            ingress.ingestCreationSignedFact(
                Profile01GoldenVectorWireFixtures.creationSignedFactBytes,
                Profile01GoldenVectorWireFixtures.sessionMediaSupplement(),
                Slice4MulticastNetworkConstants.DEFAULT_IFACE,
            )
        assertEquals(ControlFactPublishOutcome.ACCEPTED, creation.ingress!!.publishOutcome)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStarted(
            sessionId,
            channelId,
            Profile01GoldenVectorWireFixtures.decodeSessionWireFact().membershipVersion,
        )
        assertEquals(1L, wiring.currentMediaKeyEpoch(sessionId))

        assertEquals(
            ControlFactPublishOutcome.ACCEPTED,
            publishMemberAtHead(localModuleId, incarnationId = 1L, keySuffix = 0x11, ssrc = 0x33000101),
        )
        assertEquals(
            ControlFactPublishOutcome.ACCEPTED,
            publishMemberAtHead(peerModuleId, incarnationId = 1L, keySuffix = 0x21, ssrc = 0x33000201),
        )
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(sessionId, localModuleId)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(sessionId, peerModuleId)

        assertEquals(2, wiring.runtimeSnapshot(sessionId)!!.catalogEntries)
        assertEquals(1L, ConferenceSessionMediaBridge.currentIncarnation(sessionId, localModuleId))
        SessionMediaWiringHarness.assertAccepted(
            wiring.admitDatagram(
                sessionId,
                SessionMediaWiringHarness.protectedPacket(factPort.memberBinding(sessionId, localModuleId)!!),
            ),
        )
    }

    private fun advanceRegistryToEpoch2() {
        val applied =
            ingress.membershipRegistry().applyMembership(
                Profile01GoldenVectorWireFixtures.decodeMembershipWireFact(),
            )
        assertTrue(
            applied is Profile01MembershipApplyResult.Accepted ||
                applied is Profile01MembershipApplyResult.Idempotent,
        )
        seedSupplement(mediaKeyEpoch = 2L, membershipVersion = 1L)
        assertEquals(
            HostMembershipReplayOutcome.APPLIED,
            hostProjection.prepareHostMembershipRegistry(
                sessionId,
                Profile01GoldenVectorWireFixtures.membershipSignedFactBytes,
            ),
        )
        assertEquals(2L, registry.session(conferenceId)!!.mediaKeyEpoch)
    }

    private fun publishSuccessorsAtHead() {
        assertEquals(
            ControlFactPublishOutcome.ACCEPTED,
            publishMemberAtHead(localModuleId, incarnationId = 2L, keySuffix = 0x12, ssrc = 0x33000102),
        )
        assertEquals(
            ControlFactPublishOutcome.ACCEPTED,
            publishMemberAtHead(peerModuleId, incarnationId = 2L, keySuffix = 0x22, ssrc = 0x33000202),
        )
    }

    private fun publishMemberAtHead(
        moduleId: String,
        incarnationId: Long,
        keySuffix: Int,
        ssrc: Int,
    ): ControlFactPublishOutcome {
        val head = registry.session(conferenceId)
        assertNotNull(head)
        val admissionKey =
            SessionMediaWiringHarness
                .memberBinding(moduleId, admissionKeySuffix = keySuffix)
                .sourceAdmissionKey48
        return registry.publishMember(
            ConferenceMediaMemberControlFact(
                conferenceId = conferenceId,
                moduleId = moduleId,
                membershipIncarnationId = incarnationId,
                ssrc = ssrc,
                sourceAdmissionKey48 = admissionKey,
                mediaKeyEpoch = head!!.mediaKeyEpoch,
                membershipVersion = head.membershipVersion,
                factGeneration = incarnationId,
            ),
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

    private fun assertLastOutcome(
        hook: ShadowHook,
        moduleId: String,
        outcome: ShadowOutcome,
    ) {
        val recent = MeetingProductMediaShadow.observability.snapshot()["recentEvents"] as Map<*, *>
        assertEquals(outcome.name, recent["$hook:$sessionId:$moduleId"])
    }
}

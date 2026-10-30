package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.MediaGroupEndpointBinding
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.ControlFactPublishOutcome
import com.talkback.core.conference.session.gbc.AuthoritativeConferenceMediaSessionDeclaration
import com.talkback.core.conference.session.gbc.ConferenceMediaDeclarationDisposition
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01MembershipApplyResult
import com.talkback.core.conference.session.profile01.Profile01SessionEndpointAuthority
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.Profile01WireMembershipFact
import com.talkback.core.conference.session.profile01.Profile01WireMembershipMember
import com.talkback.core.conference.session.profile01.Profile01WireSessionFact
import com.talkback.core.conference.session.profile01.SessionHeadSyncOutcome
import com.talkback.core.conference.session.profile01.wire.Profile01DerivedMediaKeyMaterial
import com.talkback.core.conference.session.profile01.wire.Profile01P1DProductionIngressHarness
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** EG-1 contract fixtures E–H — peer existing-session endpoint authority (EC-1 … EC-6). */
class MeetingProfile01PeerExistingSessionEndpointAuthorityTest {
    private val channelId = "ec-endpoint-channel"
    private val supplementReadyCount = AtomicInteger(0)

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        supplementReadyCount.set(0)
        ConferenceSessionMediaBridge.wiring = ConferenceSessionMediaWiring.forHarness()
    }

    @After
    fun tearDown() {
        MeetingProductMediaShadow.enabled = false
        ConferenceSessionMediaCoordinatorDelegate.factPort = null
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun fixtureE_activeEpoch2NoPendingCreation_rotatesSessionToEpoch3() {
        val env = createIg2FullChainEnvironment(supplementReadyCount)
        val baselineGeneration = bootstrapIg2ProductionEpoch2Active(env, supplementReadyCount)
        runIg2E3APreconditionToSupplementActive(env)

        assertEquals(0, env.incompleteApplyContinuation.pendingCount(env.conferenceIdHex))
        assertEquals(1, supplementReadyCount.get())

        val sessionControl = env.controlRegistry.session(env.conferenceIdHex)
        assertNotNull(sessionControl)
        assertEquals(3L, sessionControl!!.mediaKeyEpoch)

        val commitment = env.localSourceAuthority.currentCommitment(IG2_SESSION_ID, env.localModuleId)
        assertNotNull(commitment)
        assertEquals(3L, commitment!!.mediaKeyEpoch)
        assertEquals(baselineGeneration + 1L, commitment.sourceGeneration)
    }

    @Test
    fun fixtureF_conferenceEpochChange_rejectsExistingPublishedEndpointReuse() {
        val env = newDirectSyncEnvironment(seedConferenceEpoch = 7L, seedMediaKeyEpoch = 2L)
        publishSessionAtHead(env, conferenceEpoch = 7L, mediaKeyEpoch = 2L, membershipVersion = 1L)
        val current = env.ingress.currentMembershipGeneration(env.conferenceIdHex)!!
        val nextDigest = ByteArray(32) { 0x08 }
        val bumped =
            env.validator.membershipRegistry().applyMembership(
                Profile01WireMembershipFact(
                    signedFactBytes = byteArrayOf(),
                    factDigest = nextDigest,
                    conferenceId = env.conferenceIdHex,
                    conferenceEpoch = 8L,
                    ownerModuleId = current.members.first().moduleId,
                    membershipVersion = 2L,
                    previousMembershipDigest = current.generationFactDigest.copyOf(),
                    members = current.members,
                    mediaKeyEpoch = 3L,
                    mediaKeyCommitment = current.mediaKeyCommitment.copyOf(),
                ),
            )
        assertTrue(bumped is Profile01MembershipApplyResult.Accepted)
        env.supplementRegistry.put(
            derivedSupplement(env.conferenceIdHex, conferenceEpoch = 8L, membershipVersion = 2L, mediaKeyEpoch = 3L),
        )

        val sync =
            env.ingress.syncSessionDeclarationToMembershipHead(
                conferenceId = env.conferenceIdHex,
                channelId = channelId,
                networkInterfaceName = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
                endpointAuthority = Profile01SessionEndpointAuthority.ExistingPublishedSession,
            )
        assertEquals(SessionHeadSyncOutcome.REJECTED_INCOMPLETE, sync)
        assertEquals(2L, env.controlRegistry.session(env.conferenceIdHex)!!.mediaKeyEpoch)
    }

    @Test
    fun fixtureG_noMaterializedSessionNoCreationContext_returnsNoCreationContext() {
        val env = newDirectSyncEnvironment(seedConferenceEpoch = 7L, seedMediaKeyEpoch = 2L)
        env.supplementRegistry.put(
            derivedSupplement(env.conferenceIdHex, conferenceEpoch = 7L, membershipVersion = 1L, mediaKeyEpoch = 2L),
        )
        assertNull(env.controlRegistry.session(env.conferenceIdHex))

        val sync =
            env.ingress.syncSessionDeclarationToMembershipHead(
                conferenceId = env.conferenceIdHex,
                channelId = channelId,
                networkInterfaceName = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
                endpointAuthority = Profile01SessionEndpointAuthority.InitialCreationContext(null),
            )
        assertEquals(SessionHeadSyncOutcome.NO_CREATION_CONTEXT, sync)
        assertNull(env.controlRegistry.session(env.conferenceIdHex))
    }

    @Test
    fun fixtureH_duplicateSupplementReadyAtEpoch3_idempotentSessionAndCommitment() {
        val env = createIg2FullChainEnvironment(supplementReadyCount)
        val baselineGeneration = bootstrapIg2ProductionEpoch2Active(env, supplementReadyCount)
        runIg2E3APreconditionToSupplementActive(env)

        assertEquals(3L, env.controlRegistry.session(env.conferenceIdHex)!!.mediaKeyEpoch)
        val generationBefore =
            env.localSourceAuthority.currentCommitment(IG2_SESSION_ID, env.localModuleId)!!.sourceGeneration
        assertEquals(baselineGeneration + 1L, generationBefore)

        replayIg2ProductionSupplementReady(env, supplementReadyCount, mediaKeyEpoch = 3L)

        assertEquals(3L, env.controlRegistry.session(env.conferenceIdHex)!!.mediaKeyEpoch)
        assertEquals(
            generationBefore,
            env.localSourceAuthority.currentCommitment(IG2_SESSION_ID, env.localModuleId)!!.sourceGeneration,
        )
        assertEquals(2, supplementReadyCount.get())
    }

    private data class DirectSyncEnvironment(
        val conferenceIdHex: String,
        val ingress: Profile01ConferenceMediaFactIngress,
        val validator: Profile01ConferenceMediaFactValidator,
        val supplementRegistry: Profile01SessionMediaSupplementRegistry,
        val controlRegistry: ConferenceSessionMediaControlFactRegistry,
        val publisherBridge: ConferenceSessionMediaGbcPublisherBridge,
    )

    private fun derivedSupplement(
        conferenceIdHex: String,
        conferenceEpoch: Long,
        membershipVersion: Long,
        mediaKeyEpoch: Long,
    ): Profile01DerivedMediaKeyMaterial =
        Profile01DerivedMediaKeyMaterial(
            conferenceId = conferenceIdHex,
            conferenceEpoch = conferenceEpoch,
            membershipVersion = membershipVersion,
            mediaKeyEpoch = mediaKeyEpoch,
            masterKey = ByteArray(16) { 0x11 },
            masterSalt = ByteArray(12) { 0x22 },
            keyContextHint64 = ByteArray(8) { 0x33 },
            membershipKeyContextDigest = ByteArray(32) { 0x44 },
        )

    private fun newDirectSyncEnvironment(
        seedConferenceEpoch: Long,
        seedMediaKeyEpoch: Long,
    ): DirectSyncEnvironment {
        val p1d = Profile01P1DProductionIngressHarness.create()
        val supplementRegistry = Profile01SessionMediaSupplementRegistry()
        val controlRegistry = ConferenceSessionMediaControlFactRegistry()
        val publisherBridge = ConferenceSessionMediaGbcPublisherBridge(controlRegistry)
        val validator = p1d.validator
        validator.membershipRegistry().clear()
        val baseWire =
            (
                Profile01WireCborDecoder.decodeMediaKeyPackage(p1d.builtPackage.signedFactBytes)
                    as Profile01WireCborDecoder.DecodeResult.Ready
            ).value
        validator.membershipRegistry().seedFromCreation(
            Profile01WireSessionFact(
                signedFactBytes = byteArrayOf(),
                factDigest = ByteArray(32) { 0x01 },
                conferenceId = p1d.conferenceIdHex,
                channelId = channelId,
                conferenceEpoch = seedConferenceEpoch,
                membershipVersion = 1L,
                mediaKeyEpoch = seedMediaKeyEpoch,
                membershipView =
                    listOf(
                        Profile01WireMembershipMember("M01", ByteArray(16) { 1 }),
                    ),
                mediaKeyCommitment = ByteArray(32) { 0xAB.toByte() },
                endpoint = MediaGroupEndpointBinding(multicastAddress = "239.255.0.1", mediaPort = 5004),
                masterKey = ByteArray(16) { 0x11 },
                masterSalt = ByteArray(12) { 0x22 },
                keyContextHint64 = ByteArray(8) { 0x33 },
            ),
        )
        val ingress =
            Profile01ConferenceMediaFactIngress(
                validator = validator,
                publisherBridge = publisherBridge,
                mediaKeyDecrypt = p1d.ingress.mediaKeyDecryptSeam(),
                supplementRegistry = supplementRegistry,
            )
        return DirectSyncEnvironment(
            conferenceIdHex = p1d.conferenceIdHex,
            ingress = ingress,
            validator = validator,
            supplementRegistry = supplementRegistry,
            controlRegistry = controlRegistry,
            publisherBridge = publisherBridge,
        )
    }

    private fun publishSessionAtHead(
        env: DirectSyncEnvironment,
        conferenceEpoch: Long,
        mediaKeyEpoch: Long,
        membershipVersion: Long,
    ) {
        val supplement =
            env.supplementRegistry.lookup(env.conferenceIdHex, mediaKeyEpoch)
                ?: derivedSupplement(
                    env.conferenceIdHex,
                    conferenceEpoch = conferenceEpoch,
                    membershipVersion = membershipVersion,
                    mediaKeyEpoch = mediaKeyEpoch,
                ).also { env.supplementRegistry.put(it) }
        val declaration =
            AuthoritativeConferenceMediaSessionDeclaration(
                conferenceId = env.conferenceIdHex,
                channelId = channelId,
                conferenceEpoch = conferenceEpoch,
                membershipVersion = membershipVersion,
                mediaKeyEpoch = mediaKeyEpoch,
                endpoint =
                    MediaGroupEndpointBinding(
                        multicastAddress = "239.255.0.1",
                        mediaPort = 5004,
                    ),
                networkInterfaceName = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
                masterKey = supplement.masterKey.copyOf(),
                masterSalt = supplement.masterSalt.copyOf(),
                keyContextHint64 = supplement.keyContextHint64.copyOf(),
                factGeneration = conferenceEpoch * 1_000L + membershipVersion,
                disposition = ConferenceMediaDeclarationDisposition.AUTHORITATIVE,
            )
        assertEquals(ControlFactPublishOutcome.ACCEPTED, env.publisherBridge.publishSessionDeclaration(declaration))
    }
}

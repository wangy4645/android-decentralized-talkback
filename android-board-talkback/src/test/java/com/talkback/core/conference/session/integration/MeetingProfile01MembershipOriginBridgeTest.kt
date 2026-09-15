package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.ControlPlaneConferenceSessionMediaFactPort
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01GoldenVectorSignedFactTrustBoundary
import com.talkback.core.conference.session.profile01.Profile01MembershipConvergenceRegistry
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplement
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority
import com.talkback.core.conference.session.profile01.wire.Profile01PersistedSignedFactSigner
import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.conference.session.profile01.wire.Profile01WireConstants
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import java.util.Base64
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import com.talkback.core.session.ConferenceTopologyMode
import com.talkback.core.session.ConferenceTopologySnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MeetingProfile01MembershipOriginBridgeTest {
    private val sessionId = "rca4-membership-origin"
    private val channelId = "CH-RCA4"
    private val hostModuleId = "M01"

    private lateinit var sessionIndex: MeetingProfile01ConferenceSessionIndex
    private lateinit var mediaKeyAuthority: Profile01ConferenceMediaKeyMaterialAuthority
    private lateinit var membershipConvergence: Profile01MembershipConvergenceRegistry
    private lateinit var creationBridge: MeetingProfile01CreationOriginBridge
    private lateinit var membershipBridge: MeetingProfile01MembershipOriginBridge
    private lateinit var signer: Profile01PersistedSignedFactSigner
    private lateinit var keyPair: KeyPair

    @Before
    fun setUp() {
        sessionIndex = MeetingProfile01ConferenceSessionIndex()
        sessionIndex.registerSession(sessionId, channelId, rosterEpoch = 0L)
        mediaKeyAuthority = Profile01ConferenceMediaKeyMaterialAuthority()
        membershipConvergence = Profile01MembershipConvergenceRegistry()
        keyPair =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
        signer =
            Profile01PersistedSignedFactSigner.fromPkcs8(
                pkcs8PrivateKey = keyPair.private.encoded,
                signerModuleId = hostModuleId,
                signerKeyVersion = 1L,
            )!!
        creationBridge =
            MeetingProfile01CreationOriginBridge(
                sessionIndex = sessionIndex,
                mediaKeyAuthority = mediaKeyAuthority,
                publisher = MeetingProfile01CreationOriginPublisher(signer),
                membershipConvergence = membershipConvergence,
            )
        membershipBridge =
            MeetingProfile01MembershipOriginBridge(
                sessionIndex = sessionIndex,
                creationOriginBridge = creationBridge,
                mediaKeyAuthority = mediaKeyAuthority,
                membershipConvergence = membershipConvergence,
                publisher = MeetingProfile01MembershipOriginPublisher(signer),
            )
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaCoordinatorDelegate.factPort = null
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun peerAdmitted_emitsMembershipWithHostAndPeer() {
        seedCreation(hostOnlySnapshot())
        val emitted = AtomicReference<ByteArray>()
        val outcome =
            membershipBridge.onTopologyPublished(
                sessionId = sessionId,
                snapshot = hostAndPeerSnapshot("M02"),
                localModuleId = hostModuleId,
                isMembershipAuthority = true,
                eligiblePeerModuleIds = setOf("M02"),
                publishToPeer = { _, bytes ->
                    emitted.set(bytes)
                    true
                },
            )
        assertEquals(OriginEmitOutcome.EMITTED, outcome)
        val signed = emitted.get()
        assertNotNull(signed)
        assertEquals(Profile01WireConstants.FACT_TYPE_MEMBERSHIP, Profile01WireCborDecoder.readFactType(signed!!))
        val decoded =
            (Profile01WireCborDecoder.decodeMembership(signed) as Profile01WireCborDecoder.DecodeResult.Ready).value
        assertEquals(1L, decoded.membershipVersion)
        assertEquals(setOf("M01", "M02"), decoded.members.map { it.moduleId }.toSet())
        val generation = membershipConvergence.current(sessionIndex.conferenceIdForSession(sessionId)!!)
        assertNotNull(generation)
        assertTrue(generation!!.hasMember("M01"))
        assertTrue(generation.hasMember("M02"))
    }

    @Test
    fun secondAndThirdPeerAdmitted_supersedesWithFullAuthoritativeRoster() {
        seedCreation(hostOnlySnapshot())
        publishMembership(hostAndPeerSnapshot("M02"), setOf("M02"))
        val thirdPeerEmitted = AtomicReference<ByteArray>()
        val outcome =
            membershipBridge.onTopologyPublished(
                sessionId = sessionId,
                snapshot = threePeerSnapshot(),
                localModuleId = hostModuleId,
                isMembershipAuthority = true,
                eligiblePeerModuleIds = setOf("M02", "M03"),
                publishToPeer = { moduleId, bytes ->
                    if (moduleId == "M03") {
                        thirdPeerEmitted.set(bytes)
                    }
                    true
                },
            )
        assertEquals(OriginEmitOutcome.EMITTED, outcome)
        val signed = thirdPeerEmitted.get()
        assertNotNull(signed)
        val decoded =
            (Profile01WireCborDecoder.decodeMembership(signed!!) as Profile01WireCborDecoder.DecodeResult.Ready).value
        assertEquals(2L, decoded.membershipVersion)
        assertEquals(setOf("M01", "M02", "M03"), decoded.members.map { it.moduleId }.toSet())
        val generation = membershipConvergence.current(sessionIndex.conferenceIdForSession(sessionId)!!)!!
        assertEquals(2L, generation.membershipVersion)
        assertEquals(3, generation.members.size)
    }

    @Test
    fun peerReceivesMembership_materializesLocalBinding() {
        MeetingProductMediaShadow.enabled = true
        seedCreation(hostOnlySnapshot())
        val creationBytes = creationBridge.readSignedCreationFact(sessionId)!!
        val conferenceIdHex = sessionIndex.conferenceIdForSession(sessionId)!!

        val emitted = AtomicReference<ByteArray>()
        membershipBridge.onTopologyPublished(
            sessionId = sessionId,
            snapshot = hostAndPeerSnapshot("M03"),
            localModuleId = hostModuleId,
            isMembershipAuthority = true,
            eligiblePeerModuleIds = setOf("M03"),
            publishToPeer = { _, bytes ->
                emitted.set(bytes)
                true
            },
        )
        val signed = emitted.get()
        assertNotNull(signed)

        val registry = ConferenceSessionMediaControlFactRegistry()
        val publisherBridge = ConferenceSessionMediaGbcPublisherBridge(registry)
        val supplementRegistry = Profile01SessionMediaSupplementRegistry()
        val peerSessionIndex = MeetingProfile01ConferenceSessionIndex()
        val peerSessionId = "rca4-peer-session"
        peerSessionIndex.registerSession(peerSessionId, channelId, rosterEpoch = 0L)
        peerSessionIndex.bindConferenceId(peerSessionId, conferenceIdHex)
        val trustBoundary =
            Profile01GoldenVectorSignedFactTrustBoundary(
                mapOf(hostModuleId to 1L to ecPublicKeyX963(keyPair.public)),
            )
        val ingress =
            Profile01ConferenceMediaFactIngress(
                validator = Profile01ConferenceMediaFactValidator(trustBoundary),
                publisherBridge = publisherBridge,
                supplementRegistry = supplementRegistry,
            )
        val factPort = MeetingProfile01AwareConferenceSessionMediaFactPort(registry, peerSessionIndex)
        ConferenceSessionMediaBridge.wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaCoordinatorDelegate.factPort = factPort
        var retryCount = 0
        val materializer =
            Profile01ShadowMemberBindingMaterializer(
                factPort = factPort,
                ingress = ingress,
                localModuleId = { "M03" },
                readSignedLocalSource = { null },
                retryLocalSourceBuild = { retryCount++ },
            )
        val wireIngress =
            MeetingProfile01FactWireIngress(
                ingress = ingress,
                supplementRegistry = supplementRegistry,
                sessionIndex = peerSessionIndex,
                preBindRetention = MeetingProfile01PreBindFactRetention(),
                networkInterfaceName = { Slice4MulticastNetworkConstants.DEFAULT_IFACE },
                localModuleId = { "M03" },
                localEstablishmentKeyVersion = { 1L },
                memberBindingMaterializer = materializer,
            )
        ingress.ingestCreationSignedFact(
            creationBytes,
            Profile01SessionMediaSupplement(
                channelId = channelId,
                masterKey = ByteArray(16),
                masterSalt = ByteArray(12),
                keyContextHint64 = ByteArray(8),
            ),
            Slice4MulticastNetworkConstants.DEFAULT_IFACE,
        )

        val signal =
            SignalEnvelope(
                type = SignalType.CONFERENCE_SIGNED_FACT,
                from = EndpointAddress(ModuleId(hostModuleId), EndpointId("E01")),
                to = null,
                sessionId = peerSessionId,
                timestampMs = 1L,
                payload = Base64.getEncoder().encodeToString(signed!!),
                nonce = "rca4-membership",
                signature = "rca4-membership",
            )
        val outcome = wireIngress.onConferenceSignedFact(signal)
        assertEquals(WireIngressOutcome.APPLIED, outcome)
        val generation = ingress.membershipRegistry().current(conferenceIdHex)!!
        assertTrue(generation.hasMember("M03"))
        assertTrue(retryCount > 0)
    }

    @Test
    fun duplicateSameRoster_doesNotAdvanceMembershipGeneration() {
        seedCreation(hostOnlySnapshot())
        publishMembership(hostAndPeerSnapshot("M02"), setOf("M02"))
        val generationBefore =
            membershipConvergence.current(sessionIndex.conferenceIdForSession(sessionId)!!)!!.membershipVersion
        val outcome =
            membershipBridge.onTopologyPublished(
                sessionId = sessionId,
                snapshot = hostAndPeerSnapshot("M02"),
                localModuleId = hostModuleId,
                isMembershipAuthority = true,
                eligiblePeerModuleIds = setOf("M02"),
                publishToPeer = { _, _ -> true },
            )
        assertEquals(OriginEmitOutcome.SKIPPED_NO_PENDING_PUBLICATION, outcome)
        val generationAfter =
            membershipConvergence.current(sessionIndex.conferenceIdForSession(sessionId)!!)!!.membershipVersion
        assertEquals(generationBefore, generationAfter)
    }

    private fun seedCreation(snapshot: ConferenceTopologySnapshot) {
        val outcome =
            creationBridge.onTopologyPublished(
                sessionId = sessionId,
                snapshot = snapshot,
                localModuleId = hostModuleId,
                isMembershipAuthority = true,
                eligiblePeerModuleIds = emptySet(),
                publishToPeer = { _, _ -> true },
            )
        assertEquals(OriginEmitOutcome.EMITTED, outcome)
    }

    private fun publishMembership(
        snapshot: ConferenceTopologySnapshot,
        peers: Set<String>,
    ) {
        val publishCount = AtomicInteger(0)
        val outcome =
            membershipBridge.onTopologyPublished(
                sessionId = sessionId,
                snapshot = snapshot,
                localModuleId = hostModuleId,
                isMembershipAuthority = true,
                eligiblePeerModuleIds = peers,
                publishToPeer = { _, _ ->
                    publishCount.incrementAndGet()
                    true
                },
            )
        assertEquals(OriginEmitOutcome.EMITTED, outcome)
        assertTrue(publishCount.get() > 0)
    }

    private fun hostOnlySnapshot(): ConferenceTopologySnapshot =
        ConferenceTopologySnapshot(
            conferenceId = sessionId,
            rosterEpoch = 1L,
            anchorEpoch = 100L,
            anchorId = hostModuleId,
            meshGeneration = 1L,
            topologyMode = ConferenceTopologyMode.ANCHOR,
            hostModuleId = hostModuleId,
            members = listOf(hostModuleId),
            actualMediaEdges = emptySet(),
        )

    private fun hostAndPeerSnapshot(peerModuleId: String): ConferenceTopologySnapshot =
        ConferenceTopologySnapshot(
            conferenceId = sessionId,
            rosterEpoch = 2L,
            anchorEpoch = 100L,
            anchorId = hostModuleId,
            meshGeneration = 2L,
            topologyMode = ConferenceTopologyMode.ANCHOR,
            hostModuleId = hostModuleId,
            members = listOf(hostModuleId, peerModuleId),
            actualMediaEdges = emptySet(),
        )

    private fun ecPublicKeyX963(publicKey: java.security.PublicKey): ByteArray {
        val ec = publicKey as ECPublicKey
        val point = ec.w
        val x = point.affineX.toByteArray()
        val y = point.affineY.toByteArray()
        val normalizedX = ByteArray(32)
        val normalizedY = ByteArray(32)
        val xStart = maxOf(0, x.size - 32)
        val yStart = maxOf(0, y.size - 32)
        x.copyInto(normalizedX, 32 - (x.size - xStart), xStart)
        y.copyInto(normalizedY, 32 - (y.size - yStart), yStart)
        return byteArrayOf(0x04) + normalizedX + normalizedY
    }

    private fun threePeerSnapshot(): ConferenceTopologySnapshot =
        ConferenceTopologySnapshot(
            conferenceId = sessionId,
            rosterEpoch = 3L,
            anchorEpoch = 100L,
            anchorId = hostModuleId,
            meshGeneration = 3L,
            topologyMode = ConferenceTopologyMode.ANCHOR,
            hostModuleId = hostModuleId,
            members = listOf(hostModuleId, "M02", "M03"),
            actualMediaEdges = emptySet(),
        )
}

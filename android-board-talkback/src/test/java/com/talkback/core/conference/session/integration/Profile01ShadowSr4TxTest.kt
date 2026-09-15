package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.OpusTestVectors
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.ControlFactPublishOutcome
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01DirectedWireFixtures
import com.talkback.core.conference.session.profile01.Profile01MembershipIngressResult
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageDecryptResult
import com.talkback.core.conference.session.profile01.wire.Profile01MediaKeyPackageDecryptSeam
import com.talkback.core.conference.session.profile01.wire.Profile01Q5EmbeddedVectors
import com.talkback.core.conference.session.profile01.wire.Profile01Q5TestRecipientKeyEstablishmentSeam
import java.net.NetworkInterface
import com.talkback.core.webrtc.LocalMicFrameSource
import com.talkback.core.webrtc.LocalOutboundPcmSink
import com.talkback.core.webrtc.conferenceaudio.ConferencePcmFormat
import com.talkback.core.webrtc.conferenceaudio.PcmFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Profile01ShadowSr4TxTest {
    private val sessionId = "meeting-session-tx-1"
    private val channelId = Profile01DirectedWireFixtures.CHANNEL_ID
    private val localModuleId = Profile01Q5EmbeddedVectors.RECIPIENT_MODULE_ID

    private val registry = ConferenceSessionMediaControlFactRegistry()
    private val publisherBridge = ConferenceSessionMediaGbcPublisherBridge(registry)
    private val supplementRegistry = Profile01SessionMediaSupplementRegistry()
    private val sessionIndex = MeetingProfile01ConferenceSessionIndex()
    private val validator =
        Profile01ConferenceMediaFactValidator(Profile01DirectedWireFixtures.goldenVectorTrustBoundary())
    private val ingress =
        Profile01ConferenceMediaFactIngress(
            validator = validator,
            publisherBridge = publisherBridge,
            mediaKeyDecrypt =
                Profile01MediaKeyPackageDecryptSeam(
                    Profile01Q5TestRecipientKeyEstablishmentSeam.defaultSeam(),
                ),
            supplementRegistry = supplementRegistry,
        )
    private val factPort = MeetingProfile01AwareConferenceSessionMediaFactPort(registry, sessionIndex)
    private val localSourceAuthority = Profile01LocalConferenceSourceIdentityAuthority()
    private val testMic = TestLocalMicFrameSource()
    private lateinit var wiring: ConferenceSessionMediaWiring
    private lateinit var hostProjection: Profile01HostLocalSessionFactProjection
    private lateinit var transmitSeam: Profile01ShadowMulticastTransmitSeam

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        ConferenceSessionMediaCoordinatorDelegate.factPort = factPort
        sessionIndex.registerSession(sessionId, channelId, rosterEpoch = 0L)
        sessionIndex.bindConferenceId(sessionId, Profile01DirectedWireFixtures.CONFERENCE_ID)
        seedSupplement()
        hostProjection =
            Profile01HostLocalSessionFactProjection(
                readSignedCreationFact = { Profile01DirectedWireFixtures.creationSignedFactBytes },
                readSignedSourceFact = { Profile01DirectedWireFixtures.sourceDeclarationSignedFactBytes },
                ingress = ingress,
                supplementRegistry = supplementRegistry,
                sessionIndex = sessionIndex,
                registry = registry,
                networkInterfaceName = { harnessNetworkInterfaceName() },
            )
        transmitSeam =
            Profile01ShadowMulticastTransmitSeam(
                factPort = factPort,
                registry = registry,
                sessionIndex = sessionIndex,
                localSourceAuthority = localSourceAuthority,
                hostProjection = hostProjection,
                localModuleId = { localModuleId },
                frameSource = testMic,
            )
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaCoordinatorDelegate.factPort = null
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun txU1_hostCreationAndSupplement_idempotentSessionStartedApplied() {
        assertEquals(HostSessionProjectionOutcome.APPLIED, hostProjection.maybeProjectHostShadowSession(sessionId))
        assertTrue(ConferenceSessionMediaBridge.hasSession(sessionId))
        val snap = wiring.runtimeSnapshot(sessionId)
        assertNotNull(snap)
        assertTrue(snap!!.transportScopeActive)

        assertEquals(HostSessionProjectionOutcome.ALREADY_LIVE, hostProjection.maybeProjectHostShadowSession(sessionId))
    }

    @Test
    fun txU3_hostProjectionDeferredWithoutSupplement() {
        val isolatedSupplement = Profile01SessionMediaSupplementRegistry()
        val isolatedProjection =
            Profile01HostLocalSessionFactProjection(
                readSignedCreationFact = { Profile01DirectedWireFixtures.creationSignedFactBytes },
                readSignedSourceFact = { null },
                ingress = ingress,
                supplementRegistry = isolatedSupplement,
                sessionIndex = sessionIndex,
                registry = registry,
                networkInterfaceName = { harnessNetworkInterfaceName() },
            )
        assertEquals(HostSessionProjectionOutcome.DEFERRED, isolatedProjection.maybeProjectHostShadowSession(sessionId))
        assertFalse(ConferenceSessionMediaBridge.hasSession(sessionId))
    }

    @Test
    fun txU4_egressGateFailure_withoutShadowSession_doesNotSend() {
        transmitSeam.onProductionCaptureStart(sessionId)
        assertTrue(transmitSeam.isArmed(sessionId))
        testMic.emitCanonicalFrame(pcmFrame10ms())
        testMic.emitCanonicalFrame(pcmFrame10ms())
        transmitSeam.onProductionCaptureStop(sessionId)
        assertEquals(0L, wiring.transport(sessionId)?.observability?.packetsSent ?: 0L)
    }

    @Test
    fun txU6_productionCaptureActive_feedsShadowTapAndSendsPackets() {
        bootstrapSessionAndLocalSource()
        val productionCaptureRan = AtomicBoolean(false)
        transmitSeam.onProductionCaptureStart(sessionId)
        assertTrue(transmitSeam.isArmed(sessionId))

        productionCaptureRan.set(true)

        testMic.emitCanonicalFrame(pcmFrame10ms())
        testMic.emitCanonicalFrame(pcmFrame10ms())

        transmitSeam.onProductionCaptureStop(sessionId)
        assertFalse(transmitSeam.isArmed(sessionId))
        assertTrue(productionCaptureRan.get())

        val transport = wiring.transport(sessionId)!!
        assertTrue(transport.observability.packetsSent > 0L)
    }

    @Test
    fun txU5_staleSourceGeneration_doesNotSend() {
        bootstrapSessionAndLocalSource()
        val member = Profile01DirectedWireFixtures.decodeMemberSourceWireFact()
        localSourceAuthority.commitLocalSource(
            sessionId = sessionId,
            moduleId = localModuleId,
            mediaKeyEpoch = 1L,
            ssrc = member.ssrc + 1,
        )
        transmitSeam.onProductionCaptureStart(sessionId)
        testMic.emitCanonicalFrame(pcmFrame10ms())
        testMic.emitCanonicalFrame(pcmFrame10ms())
        assertEquals(0L, wiring.transport(sessionId)!!.observability.packetsSent)
    }

    @Test
    fun txU7_staleMediaKeyEpoch_doesNotSend() {
        bootstrapSessionAndLocalSource()
        val conferenceId = Profile01DirectedWireFixtures.CONFERENCE_ID
        val session = registry.session(conferenceId)!!
        assertEquals(
            ControlFactPublishOutcome.ACCEPTED,
            registry.publishSession(
                session.copy(
                    mediaKeyEpoch = session.mediaKeyEpoch + 1L,
                    conferenceEpoch = session.conferenceEpoch + 1L,
                    factGeneration = session.factGeneration + 1L,
                ),
            ),
        )
        transmitSeam.onProductionCaptureStart(sessionId)
        testMic.emitCanonicalFrame(pcmFrame10ms())
        testMic.emitCanonicalFrame(pcmFrame10ms())
        assertEquals(0L, wiring.transport(sessionId)!!.observability.packetsSent)
    }

    @Test
    fun txU8_shadowSendFailure_doesNotPropagateFromCaptureHooks() {
        bootstrapSessionAndLocalSource()
        ConferenceSessionMediaBridge.wiring = null
        var failed = false
        try {
            transmitSeam.onProductionCaptureStart(sessionId)
            testMic.emitCanonicalFrame(pcmFrame10ms())
            testMic.emitCanonicalFrame(pcmFrame10ms())
        } catch (_: Throwable) {
            failed = true
        }
        assertFalse(failed)
        transmitSeam.onProductionCaptureStop(sessionId)
    }

    @Test
    fun txU2_supplementReadyEntry_idempotentAfterCreationCommit() {
        assertEquals(HostSessionProjectionOutcome.APPLIED, hostProjection.maybeProjectHostShadowSession(sessionId))
        assertEquals(HostSessionProjectionOutcome.ALREADY_LIVE, hostProjection.maybeProjectHostShadowSession(sessionId))
    }

    private fun bootstrapSessionAndLocalSource() {
        assertEquals(HostSessionProjectionOutcome.APPLIED, hostProjection.maybeProjectHostShadowSession(sessionId))
        val membership =
            ingress.ingestMembershipSignedFact(Profile01DirectedWireFixtures.membershipSignedFactBytes)
        assertTrue(membership is Profile01MembershipIngressResult.Converged)
        assertEquals(HostSourceProjectionOutcome.APPLIED, hostProjection.maybeProjectHostLocalSourceMember(sessionId))
        val member = Profile01DirectedWireFixtures.decodeMemberSourceWireFact()
        val commitment =
            localSourceAuthority.commitLocalSource(
                sessionId = sessionId,
                moduleId = localModuleId,
                mediaKeyEpoch = 1L,
                ssrc = member.ssrc,
            )
        assertEquals(member.sourceGeneration, commitment.sourceGeneration)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(sessionId, localModuleId)
    }

    private fun seedSupplement() {
        val q5Wire = Profile01Q5EmbeddedVectors.wirePackage()
        val decrypt =
            Profile01MediaKeyPackageDecryptSeam(Profile01Q5TestRecipientKeyEstablishmentSeam.defaultSeam())
        val decrypted =
            decrypt.decrypt(
                q5Wire,
                Profile01Q5EmbeddedVectors.RECIPIENT_MODULE_ID,
                Profile01Q5EmbeddedVectors.RECIPIENT_KEY_VERSION,
            )
        require(decrypted is Profile01MediaKeyPackageDecryptResult.Ready)
        supplementRegistry.put(
            decrypted.material.copy(
                conferenceId = Profile01DirectedWireFixtures.CONFERENCE_ID,
                conferenceEpoch = 7L,
                membershipVersion = 0L,
                mediaKeyEpoch = 1L,
            ),
        )
    }

    private fun harnessNetworkInterfaceName(): String {
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
        return candidates
            .firstOrNull { it.isUp && it.supportsMulticast() && !it.isLoopback }
            ?.name
            ?: candidates.firstOrNull { it.isUp && !it.isLoopback }?.name
            ?: "lo"
    }

    private fun pcmFrame10ms(): PcmFrame {
        val samples = ShortArray(ConferencePcmFormat.CANONICAL.samplesPerFrame)
        val tone = OpusTestVectors.pcmTone(440.0)
        tone.copyInto(samples, 0, 0, samples.size.coerceAtMost(tone.size))
        return PcmFrame(samples, ConferencePcmFormat.CANONICAL)
    }

    private class TestLocalMicFrameSource : LocalMicFrameSource {
        private var sink: LocalOutboundPcmSink? = null

        override fun acquire(sink: LocalOutboundPcmSink): () -> Unit {
            this.sink = sink
            return { this.sink = null }
        }

        fun emitCanonicalFrame(frame: PcmFrame) {
            val sink = sink ?: return
            val buffer =
                ByteBuffer.allocate(frame.samples.size * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
            frame.samples.forEach { buffer.putShort(it) }
            buffer.flip()
            sink.onPcm(
                buffer,
                16,
                frame.format.sampleRateHz,
                frame.format.channels,
                frame.samples.size / frame.format.channels,
            )
        }
    }
}

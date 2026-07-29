package com.talkback.core.signaling.prr

import com.talkback.core.discovery.ModulePresence
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.HelloPayload
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import com.talkback.core.security.SignalSecurity
import com.talkback.core.signaling.InMemorySignalingChannel
import com.talkback.core.signaling.InMemorySignalingHub
import com.talkback.core.signaling.PeerTarget
import com.talkback.core.signaling.link.LinkQualificationState
import com.talkback.core.signaling.link.LinkQualificationTrace
import com.talkback.core.signaling.link.LinkQualificationTracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PrrInboundFactObserverTest {

    private val prrLines = mutableListOf<String>()
    private val qualificationLines = mutableListOf<String>()
    private lateinit var hub: InMemorySignalingHub
    private lateinit var m01Channel: InMemorySignalingChannel
    private lateinit var m03Channel: InMemorySignalingChannel
    private lateinit var sender: UdpSignalingReannounceSender

    @Before
    fun setUp() {
        prrLines.clear()
        qualificationLines.clear()
        PeerReachabilityReannounceTrace.resetForTest { prrLines.add(it) }
        LinkQualificationTrace.resetForTest { qualificationLines.add(it) }

        hub = InMemorySignalingHub()
        m01Channel = InMemorySignalingChannel(hub, TEST_HOST, 50001)
        m03Channel = InMemorySignalingChannel(hub, TEST_HOST, 50003)
        m01Channel.start(50001)
        m03Channel.start(50003)
        m01Channel.onMessage { envelope, from ->
            PrrInboundFactObserver.observe(
                envelope = envelope,
                source = from,
                localEpoch = LOCAL_EPOCH,
                socketId = SOCKET_ID
            )
        }

        val targetProvider = DiscoveryPrrHelloTargetProvider(ModuleId("M03")).also {
            it.updatePresence(
                listOf(
                    ModulePresence(
                        moduleId = ModuleId("M01"),
                        host = TEST_HOST,
                        port = 50001,
                        endpointCount = 1,
                        lastSeenMs = System.currentTimeMillis()
                    )
                )
            )
        }
        sender = UdpSignalingReannounceSender(
            signalingChannel = m03Channel,
            sharedSecret = TEST_SECRET,
            helloTargetProvider = targetProvider
        )
    }

    @After
    fun tearDown() {
        m01Channel.stop()
        m03Channel.stop()
        PeerReachabilityReannounceTrace.resetForTest(null)
        LinkQualificationTrace.resetForTest(null)
    }

    @Test
    fun utPrr6_inboundSignedHelloFromM03_emitsPrrFactObserved() {
        val snapshot = LocalEndpointSnapshot(
            localModuleId = "M03",
            endpoints = emptyList(),
            fromAddress = EndpointAddress(ModuleId("M03"), EndpointId("E03")),
            signalingPort = 50003
        )

        sender.sendReannounce(snapshot, transportEpoch = 6L)

        assertTrue(
            prrLines.any {
                it.startsWith("PRR_FACT_OBSERVED") &&
                    it.contains("remoteModuleId=M03") &&
                    it.contains("fact=HELLO_RECEIVED") &&
                    it.contains("remoteEpoch=6") &&
                    it.contains("localEpoch=$LOCAL_EPOCH") &&
                    it.contains("src=$TEST_HOST:50003")
            }
        )
    }

    @Test
    fun utPrr7_observationDoesNotPromoteBidirectionalReadyOrTouchTracker() {
        val tracker = LinkQualificationTracker()
        val before = tracker.snapshot().linkQualification

        PrrInboundFactObserver.observe(
            envelope = signedHelloEnvelope(transportEpoch = 3L),
            source = PeerTarget(TEST_HOST, 50003),
            localEpoch = LOCAL_EPOCH,
            socketId = SOCKET_ID
        )

        assertEquals(before, tracker.snapshot().linkQualification)
        assertFalse(tracker.snapshot().linkQualification == LinkQualificationState.BIDIRECTIONAL_READY)
        assertTrue(prrLines.any { it.contains("PRR_FACT_OBSERVED") })
        assertFalse(qualificationLines.any { it.contains("BIDIRECTIONAL_READY") })
    }

    @Test
    fun utPrr8_nonHelloOrDecodeFailure_emitsNoPrrFactObserved() {
        PrrInboundFactObserver.observe(
            envelope = signedEnvelope(SignalType.FLOOR_REQUEST, """{"kind":"request"}"""),
            source = PeerTarget(TEST_HOST, 50003),
            localEpoch = LOCAL_EPOCH,
            socketId = SOCKET_ID
        )
        PrrInboundFactObserver.observe(
            envelope = signedEnvelope(SignalType.HELLO, "not-json"),
            source = PeerTarget(TEST_HOST, 50003),
            localEpoch = LOCAL_EPOCH,
            socketId = SOCKET_ID
        )

        assertFalse(prrLines.any { it.contains("PRR_FACT_OBSERVED") })
    }

    @Test
    fun observe_omitsRemoteEpochWhenZero() {
        PrrInboundFactObserver.observe(
            envelope = signedHelloEnvelope(transportEpoch = 0L),
            source = PeerTarget(TEST_HOST, 50003),
            localEpoch = LOCAL_EPOCH,
            socketId = SOCKET_ID
        )

        val line = prrLines.single { it.contains("PRR_FACT_OBSERVED") }
        assertFalse(line.contains("remoteEpoch="))
    }

    private fun signedHelloEnvelope(transportEpoch: Long): SignalEnvelope {
        val payload = HelloPayload(
            moduleId = "M03",
            endpoints = emptyList(),
            transportEpoch = transportEpoch
        )
        return signedEnvelope(SignalType.HELLO, payload.encode())
    }

    private fun signedEnvelope(type: SignalType, payload: String): SignalEnvelope {
        val from = EndpointAddress(ModuleId("M03"), EndpointId("E03"))
        val unsigned = SignalEnvelope(
            type = type,
            from = from,
            to = null,
            sessionId = "hello",
            timestampMs = System.currentTimeMillis(),
            payload = payload,
            nonce = UUID.randomUUID().toString(),
            signature = ""
        )
        return unsigned.copy(signature = SignalSecurity.sign(unsigned, TEST_SECRET))
    }

    companion object {
        private const val TEST_HOST = "127.0.0.1"
        private const val TEST_SECRET = "prr-test-secret"
        private const val LOCAL_EPOCH = 2L
        private const val SOCKET_ID = 99L
    }
}
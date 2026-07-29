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
import com.talkback.core.signaling.SignalingTransportManager
import com.talkback.core.signaling.link.LinkQualificationState
import com.talkback.core.signaling.link.LinkQualificationTrace
import com.talkback.core.signaling.link.QualificationRepairCoordinator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * PRR-4: PRR HELLO re-announce does not bypass the existing LinkQualificationTracker path.
 *
 * Inbound qualification facts are emitted by [com.talkback.core.signaling.UdpSignalingChannel]
 * receive loop (`linkQualificationFacts.onFirstInboundAfterRebind`). This test mirrors that
 * single call on the in-memory channel so we do not duplicate UDP receive logic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PrrQualificationIntegrationTest {

    private val prrLines = mutableListOf<String>()
    private val qualificationLines = mutableListOf<String>()
    private lateinit var hub: InMemorySignalingHub
    private lateinit var m01Channel: InMemorySignalingChannel
    private lateinit var m03Channel: InMemorySignalingChannel
    private lateinit var manager: SignalingTransportManager

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

        manager = SignalingTransportManager(
            repairCoordinator = QualificationRepairCoordinator(scheduler = null)
        )
        manager.onNetworkAvailable("104", "wlan0")
    }

    @After
    fun tearDown() {
        m01Channel.stop()
        m03Channel.stop()
        PeerReachabilityReannounceTrace.resetForTest(null)
        LinkQualificationTrace.resetForTest(null)
    }

    @Test
    fun prr4_epochAdvanceHelloPeerFactAndQualificationInboundChain() {
        val facts = manager.linkQualificationFacts()
        var m03SocketId = 10L
        var m03Generation = 5L

        m01Channel.onMessage { envelope, from ->
            PrrInboundFactObserver.observe(
                envelope = envelope,
                source = from,
                localEpoch = LOCAL_EPOCH_M01,
                socketId = SOCKET_ID_M01
            )
        }

        m03Channel.onMessage { envelope, from ->
            PrrInboundFactObserver.observe(
                envelope = envelope,
                source = from,
                localEpoch = m03Generation,
                socketId = m03SocketId
            )
            facts.onFirstInboundAfterRebind(m03SocketId, m03Generation)
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
        val prrController = PeerReachabilityReannounceController(
            sender = UdpSignalingReannounceSender(
                signalingChannel = m03Channel,
                sharedSecret = TEST_SECRET,
                helloTargetProvider = targetProvider
            ),
            endpointSnapshot = {
                LocalEndpointSnapshot(
                    localModuleId = "M03",
                    endpoints = emptyList(),
                    fromAddress = EndpointAddress(ModuleId("M03"), EndpointId("E03")),
                    signalingPort = 50003
                )
            }
        )
        manager.wirePrrController(prrController)

        facts.onSocketBound(m03SocketId, m03Generation, "104")
        manager.onSocketCreated(m03SocketId, 50003)
        manager.onReceiveLoopStarted(m03SocketId)
        facts.onReceiveLoopStarted(m03SocketId, m03Generation)
        facts.onFirstOutboundAfterRebind(m03SocketId, m03Generation)
        prrLines.clear()

        m03Generation = 6L
        m03SocketId = 11L
        facts.onSocketBound(m03SocketId, m03Generation, "104")
        manager.onSocketRebind(m03SocketId, 50003, "qualification_repair", "104")
        manager.onReceiveLoopStarted(m03SocketId)
        facts.onReceiveLoopStarted(m03SocketId, m03Generation)
        facts.onFirstOutboundAfterRebind(m03SocketId, m03Generation)

        assertTrue(
            prrLines.any {
                it.startsWith("PRR_EPISODE_STARTED") && it.contains("transportEpoch=6")
            }
        )
        assertTrue(prrLines.any { it.startsWith("PRR_HELLO_SENT") })
        assertTrue(prrLines.any { it.startsWith("PRR_ENDPOINT_REANNOUNCED") })
        assertTrue(
            prrLines.any {
                it.contains("PRR_FACT_OBSERVED") &&
                    it.contains("remoteModuleId=M03") &&
                    it.contains("fact=HELLO_RECEIVED")
            }
        )

        m01Channel.send(
            PeerTarget(TEST_HOST, 50003),
            signedHelloEnvelope(moduleId = "M01", transportEpoch = 1L)
        )

        assertTrue(
            qualificationLines.any {
                it.contains("LINK_FACT_RECEIVED") && it.contains("fact=FIRST_INBOUND_AFTER_REBIND")
            }
        )
        assertEquals(
            LinkQualificationState.BIDIRECTIONAL_READY,
            manager.linkQualificationSnapshot().linkQualification
        )
        assertTrue(
            qualificationLines.any {
                it.contains("LINK_QUALIFICATION_STATE_CHANGED") &&
                    it.contains("newState=BIDIRECTIONAL_READY")
            }
        )
    }

    private fun signedHelloEnvelope(moduleId: String, transportEpoch: Long): SignalEnvelope {
        val payload = HelloPayload(
            moduleId = moduleId,
            endpoints = emptyList(),
            transportEpoch = transportEpoch
        )
        return signedEnvelope(SignalType.HELLO, payload.encode())
    }

    private fun signedEnvelope(type: SignalType, payload: String): SignalEnvelope {
        val from = EndpointAddress(ModuleId("M01"), EndpointId("E01"))
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
        private const val LOCAL_EPOCH_M01 = 2L
        private const val SOCKET_ID_M01 = 99L
    }
}

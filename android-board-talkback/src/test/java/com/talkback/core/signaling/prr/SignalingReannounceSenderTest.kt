package com.talkback.core.signaling.prr

import com.talkback.core.discovery.ModulePresence
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.EndpointPriority
import com.talkback.core.model.HelloPayload
import com.talkback.core.model.ModuleId
import com.talkback.core.model.RemoteEndpointInfo
import com.talkback.core.model.SignalType
import com.talkback.core.signaling.InMemorySignalingChannel
import com.talkback.core.signaling.InMemorySignalingHub
import com.talkback.core.signaling.PeerTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SignalingReannounceSenderTest {

    private val hub = InMemorySignalingHub()
    private val received = ArrayBlockingQueue<Pair<com.talkback.core.model.SignalEnvelope, PeerTarget>>(4)
    private lateinit var m01Channel: InMemorySignalingChannel
    private lateinit var m02Channel: InMemorySignalingChannel
    private lateinit var sender: UdpSignalingReannounceSender

    @Before
    fun setUp() {
        received.clear()
        m01Channel = InMemorySignalingChannel(hub, TEST_HOST, 50001)
        m02Channel = InMemorySignalingChannel(hub, TEST_HOST, 50002)
        m01Channel.start(50001)
        m02Channel.start(50002)
        m02Channel.onMessage { envelope, from -> received.offer(envelope to from) }

        val targetProvider = DiscoveryPrrHelloTargetProvider(ModuleId("M01")).also {
            it.updatePresence(
                listOf(
                    ModulePresence(
                        moduleId = ModuleId("M02"),
                        host = TEST_HOST,
                        port = 50002,
                        endpointCount = 1,
                        lastSeenMs = System.currentTimeMillis()
                    )
                )
            )
        }
        sender = UdpSignalingReannounceSender(
            signalingChannel = m01Channel,
            sharedSecret = TEST_SECRET,
            helloTargetProvider = targetProvider
        )
    }

    @After
    fun tearDown() {
        m01Channel.stop()
        m02Channel.stop()
    }

    @Test
    fun utPrr5_sendsHelloWithEpochAndEndpointsToDiscoveryPeer() {
        val snapshot = LocalEndpointSnapshot(
            localModuleId = "M01",
            endpoints = listOf(
                RemoteEndpointInfo(
                    endpointId = "E01",
                    displayName = "Handset",
                    online = true,
                    priority = EndpointPriority.NORMAL
                )
            ),
            fromAddress = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            signalingPort = 50001
        )

        sender.sendReannounce(snapshot, transportEpoch = 6L)

        val (envelope, fromPeer) = received.poll(2, TimeUnit.SECONDS)
            ?: error("expected HELLO on M02")
        assertEquals(SignalType.HELLO, envelope.type)
        assertEquals(50001, fromPeer.port)
        val payload = HelloPayload.decode(envelope.payload)
        assertNotNull(payload)
        assertEquals("M01", payload!!.moduleId)
        assertEquals(6L, payload.transportEpoch)
        assertEquals(1, payload.endpoints.size)
        assertEquals("E01", payload.endpoints.single().endpointId)
        assertTrue(envelope.signature.isNotBlank())
    }

    @Test
    fun utPrr6_emptyEndpoints_stillSendsHelloWithEpoch() {
        val snapshot = LocalEndpointSnapshot(
            localModuleId = "M01",
            endpoints = emptyList(),
            fromAddress = EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            signalingPort = 50001
        )

        sender.sendReannounce(snapshot, transportEpoch = 3L)

        val (envelope, _) = received.poll(2, TimeUnit.SECONDS)
            ?: error("expected HELLO on M02")
        val payload = HelloPayload.decode(envelope.payload)
        assertNotNull(payload)
        assertEquals("M01", payload!!.moduleId)
        assertEquals(3L, payload.transportEpoch)
        assertTrue(payload.endpoints.isEmpty())
    }

    companion object {
        private const val TEST_HOST = "127.0.0.1"
        private const val TEST_SECRET = "prr-test-secret"
    }
}

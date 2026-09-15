package com.talkback.core.conference.capacity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket

class Gres3UdpEgressSocketConfiguratorTest {
    @Test
    fun configure_setsRequestedSendBufferAndStaysUnconnected() {
        DatagramSocket().use { socket ->
            val profile = Gres3UdpEgressSocketConfigurator.configure(socket)
            assertEquals("unconnected", profile.socketMode)
            assertFalse(profile.connected)
            assertEquals(Gres3UdpEgressSocketConfigurator.REQUESTED_SEND_BUFFER_BYTES, profile.sendBufferSizeRequested)
            assertEquals(Gres3UdpEgressSocketConfigurator.REQUESTED_SEND_BUFFER_BYTES, profile.sendBufferSizeEffective)
            assertEquals(socket.sendBufferSize, profile.sendBufferSizeEffective)
        }
    }

    @Test
    fun withWarmup_recordsValidityNotCompleted() {
        val warmed =
            Gres3UdpEgressSocketProfile(
                socketMode = "unconnected",
                connected = false,
                reuseAddress = false,
                broadcast = false,
                sendBufferSizeDefault = 1000,
                sendBufferSizeRequested = 262144,
                sendBufferSizeEffective = 262144,
                receiveBufferSizeEffective = 1000,
                trafficClass = 0,
            ).withWarmup(
                Gres3EgressWarmupResult(
                    attempted = true,
                    legsSucceeded = 9,
                    legsFailed = 0,
                    legFailures = emptyList(),
                ),
            )
        assertTrue(warmed.egressWarmupAttempted)
        assertTrue(warmed.egressWarmupSucceeded)
        assertEquals(9, warmed.egressWarmupLegsSucceeded)
        assertEquals(0, warmed.egressWarmupLegsFailed)
    }

    @Test
    fun withWarmup_partialFailure_notSucceeded() {
        val failure =
            Gres3EgressWarmupLegFailure(
                legIndex = 0,
                receiverModuleId = "M01",
                moduleFixedIp = "10.0.0.1",
                mediaPort = 47001,
                endpointKey = "M01@10.0.0.1:47001",
                exceptionClass = "java.io.IOException",
                message = "send failed",
                causeClass = null,
                causeMessage = null,
            )
        val warmed =
            Gres3UdpEgressSocketProfile(
                socketMode = "unconnected",
                connected = false,
                reuseAddress = false,
                broadcast = false,
                sendBufferSizeDefault = 1000,
                sendBufferSizeRequested = 262144,
                sendBufferSizeEffective = 262144,
                receiveBufferSizeEffective = 1000,
                trafficClass = 0,
            ).withWarmup(
                Gres3EgressWarmupResult(
                    attempted = true,
                    legsSucceeded = 0,
                    legsFailed = 9,
                    legFailures = List(9) { failure.copy(legIndex = it) },
                ),
            )
        assertTrue(warmed.egressWarmupAttempted)
        assertFalse(warmed.egressWarmupSucceeded)
        assertEquals(0, warmed.egressWarmupLegsSucceeded)
        assertEquals(9, warmed.egressWarmupLegsFailed)
        assertEquals(9, warmed.egressWarmupLegFailures.size)
    }
}

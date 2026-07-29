package com.talkback.core.signaling.prr

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PeerReachabilityReannounceControllerTest {

    private val lines = mutableListOf<String>()
    private val sendCalls = mutableListOf<Pair<LocalEndpointSnapshot, Long>>()
    private lateinit var controller: PeerReachabilityReannounceController
    private val testSnapshot = LocalEndpointSnapshot(
        localModuleId = "M01",
        fromAddress = EndpointAddress(ModuleId("M01"), EndpointId("E01"))
    )

    @Before
    fun setUp() {
        lines.clear()
        sendCalls.clear()
        PeerReachabilityReannounceTrace.resetForTest { lines.add(it) }
        controller = PeerReachabilityReannounceController(
            sender = object : SignalingReannounceSender {
                override fun sendReannounce(snapshot: LocalEndpointSnapshot, transportEpoch: Long) {
                    sendCalls.add(snapshot to transportEpoch)
                }
            },
            endpointSnapshot = { testSnapshot }
        )
    }

    @After
    fun tearDown() {
        PeerReachabilityReannounceTrace.resetForTest(null)
    }

    @Test
    fun utPrr1_epochAdvance_emitsStartedHelloAndEndpointReannounced() {
        controller.onTransportEpochChanged(
            transportEpoch = 6L,
            socketId = 42L,
            networkId = "104",
            reason = "network_available"
        )

        assertEquals(1, sendCalls.size)
        assertEquals(6L, sendCalls.single().second)
        assertTrue(lines.any { it.startsWith("PRR_EPISODE_STARTED") && it.contains("transportEpoch=6") })
        assertTrue(lines.any { it.startsWith("PRR_HELLO_SENT") && it.contains("transportEpoch=6") })
        assertTrue(lines.any { it.startsWith("PRR_ENDPOINT_REANNOUNCED") && it.contains("transportEpoch=6") })
        assertTrue(lines.none { it.startsWith("PRR_EPISODE_SKIPPED") })
    }

    @Test
    fun utPrr2_sameEpochRepeated_emitsSkippedOnly() {
        controller.onTransportEpochChanged(6L, 42L, "104", "network_available")
        lines.clear()
        sendCalls.clear()

        controller.onTransportEpochChanged(6L, 42L, "104", "network_available")

        assertTrue(sendCalls.isEmpty())
        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("PRR_EPISODE_SKIPPED"))
        assertTrue(lines.single().contains("transportEpoch=6"))
        assertTrue(lines.single().contains("reason=IDEMPOTENT"))
    }

    @Test
    fun utPrr3_qualificationRepairReason_doesNotRebind() {
        controller.onTransportEpochChanged(
            transportEpoch = 3L,
            socketId = 10L,
            networkId = "104",
            reason = "qualification_repair"
        )

        assertEquals(1, sendCalls.size)
        assertTrue(lines.any { it.startsWith("PRR_EPISODE_STARTED") && it.contains("reason=qualification_repair") })
        assertTrue(lines.any { it.startsWith("PRR_HELLO_SENT") })
        assertTrue(lines.any { it.startsWith("PRR_ENDPOINT_REANNOUNCED") })
    }

    @Test
    fun utPrr4_networkAvailableReason_triggersEpisode() {
        controller.onTransportEpochChanged(
            transportEpoch = 7L,
            socketId = 55L,
            networkId = "wlan0",
            reason = "network_available"
        )

        assertEquals(1, sendCalls.size)
        assertTrue(lines.any { it.startsWith("PRR_EPISODE_STARTED") && it.contains("reason=network_available") })
        assertTrue(lines.any { it.startsWith("PRR_HELLO_SENT") })
        assertTrue(lines.any { it.startsWith("PRR_ENDPOINT_REANNOUNCED") })
    }
}
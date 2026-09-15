package com.talkback.core.conference.probe

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UnderlayMulticastProbePacketTest {
    @Test
    fun encodeDecode_roundTrip_120Bytes() {
        val pkt = UnderlayMulticastProbePacket(runIdHash = 42, seq = 99L, sendWallMs = 1_234_567L)
        val bytes = pkt.encode()
        assertEquals(120, bytes.size)
        val decoded = UnderlayMulticastProbePacket.decode(bytes)
        assertNotNull(decoded)
        assertEquals(pkt, decoded)
    }

    @Test
    fun decode_rejectsWrongSize() {
        assertNull(UnderlayMulticastProbePacket.decode(ByteArray(119)))
    }
}

class UnderlayMulticastProbeTransportFactsTest {
    @Test
    fun formatLogLine_joinsFacts() {
        val line =
            UnderlayMulticastProbeTransportFacts.formatLogLine(
                mapOf(
                    "requestedNetworkInterfaceName" to "wlan0",
                    "socketLocalIpv4" to "192.168.10.26",
                    "socketLocalPort" to 46999,
                ),
            )
        assertTrue(line.contains("requestedNetworkInterfaceName=wlan0"))
        assertTrue(line.contains("socketLocalIpv4=192.168.10.26"))
    }
}

class UnderlayMulticastProbeMetricsTest {
    @Test
    fun tracksGapAndJitter() {
        val hash = UnderlayMulticastProbePacket.runIdHash("run-a")
        val m = UnderlayMulticastProbeMetrics(hash)
        val base = 1_000L
        m.onPacket(UnderlayMulticastProbePacket(hash, 0, base), base)
        m.onPacket(UnderlayMulticastProbePacket(hash, 1, base + 20), base + 20)
        // gap: seq 2..4 missing → consecutive loss 3
        m.onPacket(UnderlayMulticastProbePacket(hash, 5, base + 120), base + 120)

        assertEquals(3, m.maxConsecutiveLoss)
        assertEquals(3, m.receivedCount)
        val snap = m.snapshot()
        assertEquals(3, snap["receivedCount"])
        assertNotNull(snap["interArrivalJitterMsP50"])
    }

    @Test
    fun tracksGapOver120MsCountAndMaxGap() {
        val hash = UnderlayMulticastProbePacket.runIdHash("run-gap")
        val m = UnderlayMulticastProbeMetrics(hash)
        val base = 10_000L
        m.onPacket(UnderlayMulticastProbePacket(hash, 0, base), base)
        m.onPacket(UnderlayMulticastProbePacket(hash, 1, base + 20), base + 20)
        m.onPacket(UnderlayMulticastProbePacket(hash, 2, base + 40), base + 40)
        // 150 ms gap — exceeds 120 ms playout reference
        m.onPacket(UnderlayMulticastProbePacket(hash, 3, base + 190), base + 190)
        // 25 ms gap — within envelope
        m.onPacket(UnderlayMulticastProbePacket(hash, 4, base + 215), base + 215)

        val snap = m.snapshot()
        assertEquals(1, snap["interArrivalGapOver120MsCount"])
        assertEquals(150L, snap["maxInterArrivalGapMs"])
    }

    @Test
    fun ignoresForeignRunId() {
        val hash = UnderlayMulticastProbePacket.runIdHash("run-a")
        val m = UnderlayMulticastProbeMetrics(hash)
        m.onPacket(UnderlayMulticastProbePacket(hash + 1, 0, 0), 0)
        assertEquals(0, m.receivedCount)
        assertEquals(1, m.foreignRunCount)
    }
}

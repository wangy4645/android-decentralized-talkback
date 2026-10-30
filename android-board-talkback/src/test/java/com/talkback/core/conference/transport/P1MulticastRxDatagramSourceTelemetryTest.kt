package com.talkback.core.conference.transport

import com.talkback.core.conference.session.integration.Profile01ShadowRuntimeObservability
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class P1MulticastRxDatagramSourceTelemetryTest {
    @Before
    fun setUp() {
        P1MulticastRxDatagramSourceTelemetry.resetForTest()
        Profile01ShadowRuntimeObservability.activeSessionId = "test-session"
    }

    @After
    fun tearDown() {
        P1MulticastRxDatagramSourceTelemetry.resetForTest()
        Profile01ShadowRuntimeObservability.activeSessionId = null
    }

    @Test
    fun parseRtpSsrc_readsFixedHeader() {
        val payload =
            byteArrayOf(
                0x80.toByte(),
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x12,
                0x34,
                0x56,
                0x78,
            )
        assertEquals(0x12345678, P1MulticastRxDatagramSourceTelemetry.parseRtpSsrc(payload, payload.size))
    }

    @Test
    fun parseRtpSsrc_returnsNullForShortPayload() {
        assertNull(P1MulticastRxDatagramSourceTelemetry.parseRtpSsrc(byteArrayOf(0x80.toByte()), 1))
    }

    @Test
    fun recordDatagram_classifiesLocalEcho() {
        P1MulticastRxDatagramSourceTelemetry.extraLocalIpv4 = setOf("192.168.1.50")
        val rtp = rtpPayloadWithSsrc(0xAABBCCDD)
        P1MulticastRxDatagramSourceTelemetry.recordDatagram(
            srcHost = "192.168.1.50",
            srcPort = 47099,
            payload = rtp,
            length = rtp.size,
            rxWallMs = 1_700_000_000_000L,
            socketLocalHost = "192.168.1.50",
        )
        P1MulticastRxDatagramSourceTelemetry.recordDatagram(
            srcHost = "192.168.1.46",
            srcPort = 47099,
            payload = rtp,
            length = rtp.size,
            rxWallMs = 1_700_000_000_100L,
            socketLocalHost = "192.168.1.50",
        )
        P1MulticastRxDatagramSourceTelemetry.flushActiveForTest(1_700_000_000_100L)
    }

    private fun rtpPayloadWithSsrc(ssrc: Int): ByteArray {
        val b = ByteArray(12)
        b[0] = 0x80.toByte()
        b[8] = ((ssrc ushr 24) and 0xff).toByte()
        b[9] = ((ssrc ushr 16) and 0xff).toByte()
        b[10] = ((ssrc ushr 8) and 0xff).toByte()
        b[11] = (ssrc and 0xff).toByte()
        return b
    }
}

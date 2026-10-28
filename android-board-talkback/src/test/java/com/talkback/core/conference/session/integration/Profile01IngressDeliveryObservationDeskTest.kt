package com.talkback.core.conference.session.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Profile01IngressDeliveryObservationDeskTest {
    @Before
    fun setUp() {
        Profile01IngressDeliveryObservation.resetForTest()
        Profile01IngressDeliveryObservation.enabled = true
    }

    @Test
    fun sockGapAndSockToAdmitLatencyAreEmitted() {
        val lines = mutableListOf<String>()
        Profile01IngressDeliveryObservation.testLogSink = { lines += it }
        Profile01IngressDeliveryObservation.noteSocketBufferBytes(212992)
        val ssrc = 0x01020304
        // continuous then gap
        for (seq in 100..148) {
            Profile01IngressDeliveryObservation.noteSockRx(
                payload = rtp(ssrc, seq),
                rxWallMs = 1_000L + seq,
                sourceEndpoint = "10.0.0.1:50000",
            )
            Profile01IngressDeliveryObservation.noteAdmit(
                payload = rtp(ssrc, seq),
                sequence = seq,
                accepted = true,
                admitWallMs = 1_000L + seq + 2,
            )
        }
        // force window flush with gap
        Profile01IngressDeliveryObservation.noteSockRx(
            payload = rtp(ssrc, 200),
            rxWallMs = 1_300L,
            sourceEndpoint = "10.0.0.1:50000",
        )
        Profile01IngressDeliveryObservation.noteAdmit(
            payload = rtp(ssrc, 200),
            sequence = 200,
            accepted = true,
            admitWallMs = 1_305L,
        )
        assertTrue(lines.any { it.contains("INGRESS_DELIVERY_SOCK") && it.contains("gaps=") })
        assertTrue(lines.any { it.contains("INGRESS_DELIVERY_ADMIT") && it.contains("sockToAdmitAvgUs=") })
        assertTrue(lines.any { it.contains("soRcvBuf=212992") })
    }

    @Test
    fun txGapsAreCountedBySeqNotTotals() {
        val lines = mutableListOf<String>()
        Profile01IngressDeliveryObservation.testLogSink = { lines += it }
        for (seq in 1..49) {
            Profile01IngressDeliveryObservation.noteTx("M01", 99, seq, 5000L + seq)
        }
        Profile01IngressDeliveryObservation.noteTx("M01", 99, 60, 5100L)
        assertTrue(lines.any { it.startsWith("INGRESS_DELIVERY_TX") && it.contains("gaps=") })
        val tx = lines.first { it.startsWith("INGRESS_DELIVERY_TX") }
        assertTrue(tx.contains("local=M01"))
    }

    @Test
    fun rtpHeaderParsers() {
        val p = rtp(0xAABBCCDD.toInt(), 0x1234)
        assertEquals(0x1234, Profile01IngressDeliveryObservation.rtpSeq(p))
        assertEquals(0xAABBCCDD.toInt(), Profile01IngressDeliveryObservation.rtpSsrc(p))
        assertEquals(9, Profile01IngressDeliveryObservation.seqDistance(10, 20))
    }

    private fun rtp(
        ssrc: Int,
        seq: Int,
    ): ByteArray {
        val b = ByteArray(12)
        b[0] = 0x80.toByte()
        b[2] = ((seq ushr 8) and 0xff).toByte()
        b[3] = (seq and 0xff).toByte()
        b[8] = ((ssrc ushr 24) and 0xff).toByte()
        b[9] = ((ssrc ushr 16) and 0xff).toByte()
        b[10] = ((ssrc ushr 8) and 0xff).toByte()
        b[11] = (ssrc and 0xff).toByte()
        return b
    }
}

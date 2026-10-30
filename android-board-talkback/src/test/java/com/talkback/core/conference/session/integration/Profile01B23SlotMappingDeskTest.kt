package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.PerIncarnationJitterBuffer
import com.talkback.core.conference.transport.PerIncarnationIngressTimeline
import com.talkback.core.conference.transport.SourceScopedSrtpEgress
import com.talkback.core.conference.transport.WireIngressMediaMapper
import com.talkback.core.conference.wire.ConferenceWireEgress
import com.talkback.core.conference.wire.RtpStructuralGate
import com.talkback.core.conference.wire.WireIngressResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-23 desk — RTP seq/timestamp → jitter slot mapping (read-only).
 *
 * Product mapping: mediaSlot = RTP sequence (16-bit). RTP timestamp is not the slot key.
 */
class Profile01B23SlotMappingDeskTest {
    @Test
    fun mediaSlot_isRtpSequence_notTimestamp() {
        val accepted =
            accepted(
                sequence = 20_481,
                headerAndHe = headerWithTimestamp(timestamp = 99_999),
            )
        val frame =
            WireIngressMediaMapper.mediaFrame(
                sourceIdentity = "M01",
                incarnationId = 2L,
                accepted = accepted,
                arrivalMs = 10_000L,
            )
        assertEquals(20_481L, frame.mediaSlot)
        assertNotEquals(99_999L, frame.mediaSlot)
        assertEquals(20_481L * MediaJitterConstants.MEDIA_SLOT_MS, frame.mediaTimeMs)
    }

    @Test
    fun ingressTimeline_mediaTime_tracksSeqDelta_notRtpTimestamp() {
        val timeline = PerIncarnationIngressTimeline("M02", 1L)
        val t0 = timeline.mediaTimeMs(sequence = 20_480, arrivalMs = 5_000L)
        val t1 = timeline.mediaTimeMs(sequence = 20_481, arrivalMs = 9_999L)
        assertEquals(5_000L, t0)
        assertEquals(5_000L + MediaJitterConstants.MEDIA_SLOT_MS, t1)
    }

    @Test
    fun seqAndNextExpected_sameDomain_noConversionOffset() {
        val buf = PerIncarnationJitterBuffer("M01", 1L)
        assertEquals(FrameAdmitDisposition.QUEUED, buf.admit(frame("M01", 1L, 20_480L), nowMs = 1L))
        assertEquals(20_480L, buf.nextExpected())
        assertEquals(FrameAdmitDisposition.QUEUED, buf.admit(frame("M01", 1L, 20_481L), nowMs = 2L))
        assertEquals(20_480L, buf.nextExpected())
        assertTrue(buf.peekFrame(20_480L) != null)
        assertTrue(buf.peekFrame(20_481L) != null)
    }

    @Test
    fun incarnationKeys_doNotShareCursor() {
        val a = PerIncarnationJitterBuffer("M01", 1L)
        val b = PerIncarnationJitterBuffer("M01", 2L)
        a.admit(frame("M01", 1L, 100L), 1L)
        b.admit(frame("M01", 2L, 9_000L), 1L)
        assertEquals(100L, a.nextExpected())
        assertEquals(9_000L, b.nextExpected())
        assertNull(a.peekFrame(9_000L))
        assertNull(b.peekFrame(100L))
    }

    @Test
    fun hole_packetIsInBuffer_underDifferentSlotThanNextExpected() {
        val buf = PerIncarnationJitterBuffer("M04", 1L)
        buf.soakDiscardBelowAndResync(10L)
        buf.queueWithinPlayoutWindow(frame("M04", 1L, 15L))
        val next = buf.nextExpected()!!
        val ready =
            buf.bufferedSlots().filter { it >= next }.minOrNull()
        assertEquals(10L, next)
        assertEquals(15L, ready)
        assertTrue(ready!! > next)
        assertNull(buf.peekFrame(next))
        assertTrue(buf.peekFrame(15L) != null)
    }

    @Test
    fun slotsBelowNextExpected_areInvisibleToSchedulerLookup() {
        val buf = PerIncarnationJitterBuffer("M02", 1L)
        buf.admit(frame("M02", 1L, 50L), 1L)
        buf.soakDiscardBelowAndResync(80L)
        buf.queueWithinPlayoutWindow(frame("M02", 1L, 80L))
        val next = buf.nextExpected()!!
        assertEquals(80L, next)
        val ready = buf.bufferedSlots().filter { it >= next }.minOrNull()
        assertEquals(80L, ready)
        assertFalse(buf.bufferedSlots().any { it in 50L until 80L })
    }

    @Test
    fun wireSeqIs16Bit_txCounterCanExceedSlotKey() {
        val egress =
            SourceScopedSrtpEgress(
                sourceIdentity = "M03",
                masterKey = hex("000102030405060708090a0b0c0d0e0f"),
                masterSalt = hex("101112131415161718191a1b"),
                ssrc = 0x11223344,
                roc = 0,
                initialSeq = 0xFFFF,
                headerHeTemplate = SourceScopedSrtpEgress.buildHeaderHeTemplate(0x11223344),
            )
        val opus = ByteArray(8) { 1 }
        val first = egress.protectNext(opus) as ConferenceWireEgress.EgressResult.Protected
        val seq0 =
            (RtpStructuralGate.parse(first.udpPayload) as RtpStructuralGate.ParseResult.Ok).parsed.sequence
        assertEquals(0xFFFF, seq0)
        val second = egress.protectNext(opus) as ConferenceWireEgress.EgressResult.Protected
        val seq1 =
            (RtpStructuralGate.parse(second.udpPayload) as RtpStructuralGate.ParseResult.Ok).parsed.sequence
        assertEquals(0, seq1)
        val slot =
            WireIngressMediaMapper
                .mediaFrame(
                    sourceIdentity = "M03",
                    incarnationId = 1L,
                    accepted =
                        accepted(
                            sequence = seq1,
                            headerAndHe = ByteArray(32),
                        ),
                    arrivalMs = 1L,
                ).mediaSlot
        assertEquals(0L, slot)
        assertTrue(egress.currentSeq > 0xFFFF)
    }

    private fun accepted(
        sequence: Int,
        headerAndHe: ByteArray,
    ): WireIngressResult.Accepted =
        WireIngressResult.Accepted(
            plaintextPayload = ByteArray(4),
            packetIndex = sequence.toLong(),
            ssrc = 1,
            sequence = sequence,
            roc = 0,
            headerAndHe = headerAndHe,
        )

    private fun headerWithTimestamp(timestamp: Int): ByteArray {
        val h = ByteArray(32)
        h[4] = ((timestamp ushr 24) and 0xff).toByte()
        h[5] = ((timestamp ushr 16) and 0xff).toByte()
        h[6] = ((timestamp ushr 8) and 0xff).toByte()
        h[7] = (timestamp and 0xff).toByte()
        h[31] = 0x80.toByte()
        return h
    }

    private fun frame(
        source: String,
        incarnation: Long,
        slot: Long,
    ): AdmittedMediaFrame =
        AdmittedMediaFrame(
            sourceIdentity = source,
            incarnationId = incarnation,
            mediaSlot = slot,
            mediaTimeMs = slot * MediaJitterConstants.MEDIA_SLOT_MS,
            arrivalMs = slot,
        )

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
}
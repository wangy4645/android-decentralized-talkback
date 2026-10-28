package com.talkback.core.conference.session.integration

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

class Profile01A3Q3bTransferProofTest {
    @After
    fun tearDown() {
        Profile01A3Q3bTransferProof.resetForTest()
    }

    @Test
    fun p0HalfScaleWindowIsAboutMinus6Dbfs() {
        val lines = mutableListOf<String>()
        Profile01A3Q3bTransferProof.testLogSink = { lines += it }
        val pcm = ShortArray(480) { 16384 }
        val buf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { buf.putShort(it) }
        buf.flip()
        Profile01A3Q3bTransferProof.recordP0(
            sessionId = "s",
            audioData = buf,
            bitsPerSample = 16,
            sampleRate = 48_000,
            channels = 1,
            frames = 480,
            nowMs = 1_000L,
        )
        Profile01A3Q3bTransferProof.recordP0(
            sessionId = "s",
            audioData = buf.duplicate(),
            bitsPerSample = 16,
            sampleRate = 48_000,
            channels = 1,
            frames = 480,
            nowMs = 6_000L,
        )
        val line = lines.single()
        assertTrue(line, line.contains("point=P0") && line.contains("source=LOCAL"))
        assertTrue(line, line.contains("rms=-6.0") && line.contains("peak=-6.0"))
        assertTrue(line, line.contains("rate=48000") && line.contains("ch=1") && line.contains("bits=16"))
    }

    @Test
    fun p2CountsSequenceGapsAndPayload() {
        val lines = mutableListOf<String>()
        Profile01A3Q3bTransferProof.testLogSink = { lines += it }
        Profile01A3Q3bTransferProof.recordP2Rx("s", "M01", 10, 40, nowMs = 0L)
        Profile01A3Q3bTransferProof.recordP2Rx("s", "M01", 11, 80, nowMs = 1_000L)
        Profile01A3Q3bTransferProof.recordP2Rx("s", "M01", 13, 40, nowMs = 2_000L)
        Profile01A3Q3bTransferProof.recordP2Rx("s", "M01", 14, 40, nowMs = 5_000L)
        val line = lines.single()
        assertTrue(line, line.contains("point=P2 dir=RX") && line.contains("source=M01"))
        assertTrue(line, line.contains("frames=3") && line.contains("seqGaps=1"))
        assertTrue(line, line.contains("payloadMin=40") && line.contains("payloadMax=80"))
        assertTrue(line, line.contains("seqFirst=10") && line.contains("seqLast=13"))
    }

    @Test
    fun p3QuietWindowStaysSeparateFromHalfScale() {
        val lines = mutableListOf<String>()
        Profile01A3Q3bTransferProof.testLogSink = { lines += it }
        Profile01A3Q3bTransferProof.recordP3("s", "M01", ShortArray(960) { 8 }, nowMs = 0L)
        Profile01A3Q3bTransferProof.recordP3("s", "M01", ShortArray(960) { 16384 }, nowMs = 5_000L)
        val line = lines.single()
        assertTrue(line, line.contains("point=P3") && line.contains("source=M01"))
        assertTrue(line, line.contains("rms=-72.2"))
        assertTrue(line, !line.contains("rms=-6.0"))
    }
}

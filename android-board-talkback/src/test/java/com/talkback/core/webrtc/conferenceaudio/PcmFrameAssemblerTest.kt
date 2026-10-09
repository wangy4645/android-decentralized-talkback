package com.talkback.core.webrtc.conferenceaudio

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmFrameAssemblerTest {

    @Test
    fun assemblesCanonicalFrameFromMicSource() {
        val assembler = PcmFrameAssembler()
        val emitted = mutableListOf<PcmFrame>()
        val frame = PcmFrame.constantLevel(2_000)
        val buffer = PcmFrameCodec.toByteBuffer(frame)
        assembler.append(
            buffer,
            frame.format.bitsPerSample,
            frame.format.sampleRateHz,
            frame.format.channels,
            frame.samples.size,
            onFrame = { emitted.add(it) }
        )
        assertEquals(1, emitted.size)
        assertEquals(480, emitted.single().samples.size)
    }

    @Test
    fun stubMicSource_emitsThroughAssembler() {
        val source = StubLocalMicFrameSource()
        val assembler = PcmFrameAssembler()
        val frames = mutableListOf<PcmFrame>()
        source.acquire { buffer, bits, rate, channels, count ->
            assembler.append(buffer, bits, rate, channels, count) { frames.add(it) }
        }
        source.emitConstant()
        assertEquals(1, frames.size)
    }
}

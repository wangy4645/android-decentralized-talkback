package com.talkback.core.conference.runtime

import com.talkback.core.conference.wire.ConferenceWireConstants
import io.github.jaredmdobson.concentus.OpusBandwidth
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusPacketInfo
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production TX encoder vs ADR-0058 frozen wire cap (72 B / 20 ms). 24 kbps CBR must never exceed it,
 * for silence, tones, and full-scale noise alike, and must leave SILK-WB (hollow 8 kHz cut).
 */
class ConcentusOpusEncoderWireCapDeskTest {
    @Test
    fun cbrBudget_fitsWireCap() {
        val bytesPer20ms = OpusCodecConstants.TX_BITRATE_BPS * 20 / 8 / 1000
        assertTrue("budget=$bytesPer20ms", bytesPer20ms <= ConferenceWireConstants.MAX_OPUS_PAYLOAD_OCTETS)
    }

    @Test
    fun everyFrame_withinWireCap_acrossSignalTypes() {
        val seam = ConcentusOpusEncoderSeam()
        val rnd = Random(7)
        val frames =
            listOf(
                ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS),
                tone(440.0, 8_000),
                tone(3_000.0, 20_000),
                tone(11_000.0, 20_000),
                ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS) { rnd.nextInt(-32_768, 32_768).toShort() },
            )
        var maxBytes = 0
        repeat(50) { i ->
            val frame = frames[i % frames.size]
            val encoded = seam.encode(frame, sourceGeneration = 1L)
            maxBytes = maxOf(maxBytes, encoded.size)
            assertTrue("frame $i -> ${encoded.size} B", encoded.size <= ConferenceWireConstants.MAX_OPUS_PAYLOAD_OCTETS)
        }
        assertTrue("maxBytes=$maxBytes", maxBytes > 40) // 16 kbps would cap near 40 B
    }

    @Test
    fun steadyState_isHybridAboveWideband() {
        val seam = ConcentusOpusEncoderSeam()
        val frame = tone(5_000.0, 12_000)
        var last: ByteArray = ByteArray(0)
        repeat(25) { last = seam.encode(frame, sourceGeneration = 1L) }
        val bw = OpusPacketInfo.getBandwidth(last, 0)
        assertTrue(
            "bandwidth=$bw",
            bw == OpusBandwidth.OPUS_BANDWIDTH_SUPERWIDEBAND || bw == OpusBandwidth.OPUS_BANDWIDTH_FULLBAND,
        )
        val decoder = OpusDecoder(OpusCodecConstants.SAMPLE_RATE_HZ, OpusCodecConstants.CHANNELS)
        val pcm = ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS)
        val n = decoder.decode(last, 0, last.size, pcm, 0, pcm.size, false)
        assertEquals(OpusCodecConstants.FRAME_SAMPLES_20MS, n)
    }

    private fun tone(
        hz: Double,
        amp: Int,
    ): ShortArray =
        ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS) { i ->
            (sin(2 * PI * hz * i / OpusCodecConstants.SAMPLE_RATE_HZ) * amp).toInt().toShort()
        }
}

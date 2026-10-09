package com.talkback.core.webrtc.conferenceaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 1a-1 — AudioMixer unit tests (explicit fixtures only). */
class AudioMixerTest {

    private val format = ConferencePcmFormat.CANONICAL

    @Test
    fun canonicalFrame_is480SamplesAt10ms() {
        assertEquals(480, format.samplesPerFrame)
        assertEquals(48_000, format.sampleRateHz)
        assertEquals(10, format.frameDurationMs)
    }

    @Test
    fun singleSource_rendersAtPushLevel() {
        val mixer = AudioMixer(rampFrames = 0)
        mixer.addSource("M02")
        val level: Short = 4_000
        mixer.push("M02", PcmFrame.constantLevel(level))
        val mixed = mixer.renderMixedFrame()
        assertTrue(mixed.peakAbs() > 0)
    }

    @Test
    fun twoSources_mixBothContributors() {
        val mixer = AudioMixer(rampFrames = 0, silenceThreshold = 32)
        mixer.addSource("M02")
        mixer.addSource("M03")
        mixer.push("M02", PcmFrame.constantLevel(3_000))
        mixer.push("M03", PcmFrame.constantLevel(6_000))
        val mixed = mixer.renderMixedFrame()
        val single = AudioMixer(rampFrames = 0, silenceThreshold = 32).apply {
            addSource("M02")
            push("M02", PcmFrame.constantLevel(3_000))
        }.renderMixedFrame()
        assertTrue(mixed.peakAbs() > single.peakAbs())
    }

    @Test
    fun underrun_countedWhenActiveSourceMissesFrame() {
        val mixer = AudioMixer(rampFrames = 0)
        mixer.addSource("M02")
        mixer.push("M02", PcmFrame.constantLevel(2_000))
        mixer.renderMixedFrame()
        mixer.renderMixedFrame()
        assertTrue(mixer.currentStats.underruns >= 1)
    }

    @Test
    fun silenceSource_skippedWithoutUnderrun() {
        val mixer = AudioMixer(rampFrames = 0, silenceThreshold = 100)
        mixer.addSource("M02")
        mixer.push("M02", PcmFrame.silence())
        mixer.renderMixedFrame()
        assertEquals(1, mixer.currentStats.silentSourceSkips)
        assertEquals(0, mixer.currentStats.underruns)
    }

    @Test
    fun joinRamp_increasesGainOverFrames() {
        val mixer = AudioMixer(rampFrames = 3, silenceThreshold = 32)
        mixer.addSource("M02")
        val level: Short = 8_000
        mixer.push("M02", PcmFrame.constantLevel(level))
        val first = mixer.renderMixedFrame().peakAbs()
        mixer.push("M02", PcmFrame.constantLevel(level))
        val second = mixer.renderMixedFrame().peakAbs()
        mixer.push("M02", PcmFrame.constantLevel(level))
        val third = mixer.renderMixedFrame().peakAbs()
        assertTrue(first < third)
        assertTrue(second <= third)
    }

    @Test
    fun leaveRamp_decreasesThenRemovesSource() {
        val mixer = AudioMixer(rampFrames = 2, silenceThreshold = 32)
        mixer.addSource("M02")
        val level: Short = 8_000
        repeat(3) {
            mixer.push("M02", PcmFrame.constantLevel(level))
            mixer.renderMixedFrame()
        }
        mixer.removeSource("M02")
        mixer.push("M02", PcmFrame.constantLevel(level))
        val rampDown = mixer.renderMixedFrame().peakAbs()
        mixer.push("M02", PcmFrame.constantLevel(level))
        mixer.renderMixedFrame()
        mixer.push("M02", PcmFrame.constantLevel(level))
        val after = mixer.renderMixedFrame().peakAbs()
        assertTrue(rampDown > after)
        assertEquals(0, after)
        assertEquals(null, mixer.sourceState("M02"))
    }

    @Test
    fun tenSources_nominalLevels_noHardClip() {
        val mixer = AudioMixer(rampFrames = 0, silenceThreshold = 32)
        repeat(10) { idx ->
            val id = "M%02d".format(idx + 1)
            mixer.addSource(id)
            mixer.push(id, PcmFrame.constantLevel(1_500))
        }
        val mixed = mixer.renderMixedFrame()
        assertTrue(mixed.peakAbs() < 32_767)
        assertEquals(0, mixer.currentStats.clipSamples)
    }

    @Test
    fun hotInput_softLimitedNotRawOverflow() {
        val mixer = AudioMixer(rampFrames = 0, silenceThreshold = 32)
        mixer.addSource("M02")
        mixer.push("M02", PcmFrame.constantLevel(30_000))
        val mixed = mixer.renderMixedFrame()
        assertTrue(mixed.peakAbs() <= 32_767)
        assertTrue(mixed.peakAbs() > 0)
    }

    @Test
    fun renderClock_independentOfSourceCount() {
        val one = AudioMixer(rampFrames = 0)
        one.addSource("A")
        one.push("A", PcmFrame.constantLevel(1_000))
        val oneFrame = one.renderMixedFrame()

        val ten = AudioMixer(rampFrames = 0, silenceThreshold = 32)
        repeat(10) { i ->
            val id = "S$i"
            ten.addSource(id)
            ten.push(id, PcmFrame.constantLevel(800))
        }
        val tenFrame = ten.renderMixedFrame()
        assertEquals(oneFrame.samples.size, tenFrame.samples.size)
        assertEquals(format.samplesPerFrame, tenFrame.samples.size)
    }

    @Test
    fun push_rejectsMismatchedFormat() {
        val mixer = AudioMixer()
        mixer.addSource("M02")
        val alt = ConferencePcmFormat(
            sampleRateHz = 16_000,
            channels = 1,
            bitsPerSample = 16,
            frameDurationMs = 10
        )
        assertFalse(mixer.push("M02", PcmFrame(ShortArray(alt.samplesPerFrame), alt)))
    }
}

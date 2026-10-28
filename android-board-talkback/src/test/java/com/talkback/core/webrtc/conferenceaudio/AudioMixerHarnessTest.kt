package com.talkback.core.webrtc.conferenceaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 1a-1 — topology-neutral mixer harness. */
class AudioMixerHarnessTest {

    @Test
    fun multiSource_mixWithoutTopologyKnowledge() {
        val harness = AudioMixerHarness(rampFrames = 0, silenceThreshold = 32)
        harness.admitSource("S1", level = 3_000)
        harness.admitSource("S2", level = 6_000)

        harness.pushSource("S1")
        harness.pushSource("S2")
        val output = harness.renderTick()

        assertEquals(2, output.contributingSourceIds.size)
        assertTrue(output.frame.peakAbs() > 0)
    }

    @Test
    fun revokeDuringMix_removesContributorOverRamp() {
        val harness = AudioMixerHarness(rampFrames = 2, silenceThreshold = 32)
        harness.admitSource("S1", level = 8_000)
        repeat(3) { harness.pushAndRender("S1") }

        harness.revokeSource("S1")
        repeat(3) {
            harness.pushSource("S1")
            harness.renderTick()
        }
        assertFalse(harness.outputs.last().contributingSourceIds.contains("S1"))
        assertEquals(0, harness.outputs.last().frame.peakAbs())
    }

    @Test
    fun tenSources_limiterPreventsHardClip() {
        val harness = AudioMixerHarness(rampFrames = 0, silenceThreshold = 32)
        repeat(10) { idx ->
            harness.admitSource("M%02d".format(idx + 1), level = 1_500)
        }
        repeat(10) { idx ->
            harness.pushSource("M%02d".format(idx + 1))
        }
        val output = harness.renderTick()
        assertTrue(output.frame.peakAbs() < 32_767)
        assertEquals(0, output.stats.clipSamples)
    }

    @Test
    fun renderOrdering_independentOfAdmitOrder() {
        val harness = AudioMixerHarness(rampFrames = 0, silenceThreshold = 32)
        harness.admitSource("LATE", level = 5_000)
        harness.admitSource("EARLY", level = 5_000)
        harness.pushSource("EARLY")
        harness.pushSource("LATE")
        val output = harness.renderTick()
        assertTrue(output.contributingSourceIds.contains("EARLY"))
        assertTrue(output.contributingSourceIds.contains("LATE"))
        assertEquals(ConferencePcmFormat.CANONICAL.samplesPerFrame, output.frame.samples.size)
    }

    @Test
    fun events_captureAdmissionAndRenderTicks() {
        val harness = AudioMixerHarness()
        harness.admitSource("S1")
        harness.pushAndRender("S1")
        assertTrue(harness.events.any { it is AudioMixerEvent.SourceAdmitted })
        assertTrue(harness.events.any { it is AudioMixerEvent.RenderTick })
    }
}

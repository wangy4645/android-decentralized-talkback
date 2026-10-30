package com.talkback.core.conference.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * E2b-04 harness: R01 remaining decode/mix/peak, R08, R11.
 */
class Profile03E2b04DecodeMixHarnessTest {
    @Test
    fun r01_twoSources_decodeMix_peakWithin089() {
        val rt = DecodeMixRuntime()
        installV1(rt, "S1", level = 20)
        installV1(rt, "S2", level = 30)
        rt.selectTopK(0L)

        // Loud PCM that would clip without peak protection if naively summed.
        val loud = ShortArray(160) { 30_000 }
        rt.setDecodeSeam(
            OpusDecodeSeam { id, _, _, _ ->
                PcmFrame(loud.copyOf(), usableForMix = true)
            },
        )

        val block =
            rt.mixEligibleSources(
                listOf(
                    Triple("S1", 1L, 0L),
                    Triple("S2", 1L, 0L),
                ),
                nowMs = 0L,
            )
        assertEquals(2, block.mixParticipantCount)
        assertTrue(block.peakAbsFs <= MediaMixConstants.OUTPUT_PEAK_ABS_FS + 1e-9)
        for (s in block.samples) {
            val fs = abs(s.toInt()).toDouble() / MediaMixConstants.S16_FULL_SCALE
            assertTrue(fs <= MediaMixConstants.OUTPUT_PEAK_ABS_FS + 1e-9)
        }
        assertEquals(ExecutionFenceState.OPEN, rt.selection.registry.get("S1")!!.fence)
    }

    @Test
    fun r08a_immediatelyUsable_noMandatory40msWait() {
        val rt = DecodeMixRuntime()
        installV1(rt, "S1", 20)
        rt.selectTopK(0L)
        rt.setDecodeSeam(
            OpusDecodeSeam { _, _, _, _ ->
                PcmFrame(ShortArray(80) { 1_000 }, usableForMix = true)
            },
        )
        // at t=0, well before warmup deadline — must be mixable now
        val pcm = rt.produceMixablePcm("S1", 1L, 0L, nowMs = 0L)
        assertNotNull(pcm)
        assertTrue(pcm!!.usableForMix)
        assertEquals(0, rt.warmupExpiryCount)
    }

    @Test
    fun r08b_warmupCompletesWithin40ms() {
        val rt = DecodeMixRuntime()
        installV1(rt, "S1", 20)
        rt.selectTopK(0L)
        var usableAfter = 25L
        rt.setDecodeSeam(
            OpusDecodeSeam { _, _, _, now ->
                PcmFrame(
                    ShortArray(80) { 1_000 },
                    usableForMix = now >= usableAfter,
                )
            },
        )
        assertNull(rt.produceMixablePcm("S1", 1L, 0L, nowMs = 0L))
        assertNull(rt.produceMixablePcm("S1", 1L, 0L, nowMs = 20L))
        val pcm = rt.produceMixablePcm("S1", 1L, 0L, nowMs = 25L)
        assertNotNull(pcm)
        assertTrue(25L <= MediaMixConstants.DECODER_WARMUP_MS)
    }

    @Test
    fun cE2b04_04_warmupExpiry_noFenceOrTopKChange() {
        val rt = DecodeMixRuntime()
        installV1(rt, "S1", 20)
        rt.selectTopK(0L)
        assertTrue(rt.selection.isDecodeEligible("S1", 1L))
        rt.setDecodeSeam(
            OpusDecodeSeam { _, _, _, _ ->
                PcmFrame(ShortArray(80) { 100 }, usableForMix = false)
            },
        )
        // Allocate as transition then expire
        assertNull(
            rt.produceMixablePcm(
                "S1",
                1L,
                0L,
                nowMs = 0L,
                allocateAsTransition = true,
            ),
        )
        assertNull(
            rt.produceMixablePcm(
                "S1",
                1L,
                0L,
                nowMs = MediaMixConstants.DECODER_WARMUP_MS + 1,
                allocateAsTransition = true,
            ),
        )
        assertEquals(1, rt.warmupExpiryCount)
        assertEquals(ExecutionFenceState.OPEN, rt.selection.registry.get("S1")!!.fence)
        assertTrue(rt.selection.isDecodeEligible("S1", 1L))
        assertEquals(1, rt.selection.currentTopK().members.size)
    }

    @Test
    fun r11_fiveLiveDecoders_mixStillFour() {
        val rt = DecodeMixRuntime()
        val ids = listOf("S1", "S2", "S3", "S4", "S5")
        ids.forEachIndexed { i, id -> installV1(rt, id, level = 10 + i * 10) }
        // Top-K keeps loudest 4: S1..S4 (levels 10,20,30,40); S5=50 excluded
        rt.selectTopK(0L)
        assertEquals(4, rt.selection.currentTopK().members.size)
        assertFalse(rt.selection.isDecodeEligible("S5", 1L))

        rt.setDecodeSeam(
            OpusDecodeSeam { _, _, _, _ ->
                PcmFrame(ShortArray(80) { 2_000 }, usableForMix = true)
            },
        )

        // Actively mix Top-4
        val block =
            rt.mixEligibleSources(
                listOf(
                    Triple("S1", 1L, 0L),
                    Triple("S2", 1L, 0L),
                    Triple("S3", 1L, 0L),
                    Triple("S4", 1L, 0L),
                ),
                nowMs = 0L,
            )
        assertEquals(4, block.mixParticipantCount)
        assertEquals(4, rt.decoderPool.activeMixCount())

        // Force a 5th live decoder as bounded transition (e.g. warm-down of prior occupant).
        // Use a separate admitted identity that is NOT mix-eligible: allocate transition manually.
        // S5 is admitted but not Top-K — cannot produceMixable via eligibility.
        // Allocate transition seat via pool directly for harness moment, then verify caps.
        val t5 =
            rt.decoderPool.allocate(
                sourceIdentity = "S5",
                incarnationId = 1L,
                nowMs = 0L,
                forTransitionOnly = true,
            )
        assertNotNull(t5)
        assertEquals(DecoderRole.TRANSITION, t5!!.role)
        assertEquals(5, rt.decoderPool.liveCount())
        assertEquals(4, rt.decoderPool.activeMixCount())
        assertEquals(1, rt.decoderPool.transitionCount())
        assertEquals(4, rt.selection.currentTopK().members.size)
        assertEquals(4, block.mixParticipantCount)
        // S5 must not enter mix via eligibility bypass
        assertNull(rt.produceMixablePcm("S5", 1L, 0L, nowMs = 0L))
    }

    @Test
    fun cE2b04_05_fakeCannotBypassFence() {
        val rt = DecodeMixRuntime()
        installV1(rt, "S1", 20)
        rt.selectTopK(0L)
        rt.setDecodeSeam(
            OpusDecodeSeam { _, _, _, _ ->
                PcmFrame(ShortArray(40) { 5_000 }, usableForMix = true)
            },
        )
        assertTrue(rt.hardFence("S1", 1L))
        assertNull(rt.produceMixablePcm("S1", 1L, 0L, nowMs = 0L))
    }

    private fun installV1(rt: DecodeMixRuntime, id: String, level: Int) {
        rt.install(AdmittedMediaSource(id, 1L))
        assertTrue(rt.observeVoice(VoiceLevelObservation(id, 1L, true, level)))
    }
}

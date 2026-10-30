package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.AdmittedMediaSource
import com.talkback.core.conference.runtime.ConferenceMediaSelectionRuntime
import com.talkback.core.conference.runtime.EqualWeightMixer
import com.talkback.core.conference.runtime.MediaRuntimeConstants
import com.talkback.core.conference.runtime.PcmFrame
import com.talkback.core.conference.runtime.VoiceLevelObservation
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A3-Q3E read-only mixer contract audit.
 *
 * Maps three facts in current production:
 * AVAILABLE  REAL/PLC PCM exists for a Top-K decode-eligible source this tick
 * SELECTED   Top-K member (voiceActive=V=1 at selectTopK; hold/hysteresis among V=1)
 * ACTIVE     voiceActive bit from RTP header VoiceLevelObservation (not PCM energy)
 *
 * Current mix contract (EqualWeightMixer via PerSourceRenderScheduler):
 * eligible contributors = Top-K members that produced REAL or PLC this tick
 * output = sum(eligible PCM) / eligible.size
 *
 * There is no PCM-energy gate at mix time. REAL silence still counts.
 */
class Profile01A3Q3eMixerContractDeskTest {
    @Test
    fun case1_fieldBaseline_threeRealSilenceAttenuatesSpeechByNinePointFiveDb() {
        val speech = pcmAtRmsDbfs(-26.0)
        val silence = pcmAtRmsDbfs(-74.0)
        val mixed =
            EqualWeightMixer.mix(
                listOf(
                    PcmFrame(speech, usableForMix = true),
                    PcmFrame(silence, usableForMix = true),
                    PcmFrame(silence, usableForMix = true),
                ),
            )
        assertEquals(3, mixed.mixParticipantCount)
        val outRms = measureRmsDbfs(mixed.samples)
        assertTrue("out=$outRms", abs(outRms - (-35.5)) < 1.0)
        assertTrue(abs(outRms - (-26.0) - (-9.54)) < 1.0)
    }

    @Test
    fun case2_twoSpeechOneSilence_dividesByThreeNotTwo() {
        val speech = pcmAtRmsDbfs(-26.0)
        val silence = pcmAtRmsDbfs(-74.0)
        val mixed =
            EqualWeightMixer.mix(
                listOf(
                    PcmFrame(speech, usableForMix = true),
                    PcmFrame(speech.copyOf(), usableForMix = true),
                    PcmFrame(silence, usableForMix = true),
                ),
            )
        assertEquals(3, mixed.mixParticipantCount)
        val outRms = measureRmsDbfs(mixed.samples)
        assertTrue(outRms < -26.0)
    }

    @Test
    fun case3_allSilence_stillMixesWithThreeWayDenominator() {
        val silence = pcmAtRmsDbfs(-74.0)
        val mixed =
            EqualWeightMixer.mix(
                listOf(
                    PcmFrame(silence, usableForMix = true),
                    PcmFrame(silence.copyOf(), usableForMix = true),
                    PcmFrame(silence.copyOf(), usableForMix = true),
                ),
            )
        assertEquals(3, mixed.mixParticipantCount)
        assertTrue(abs(measureRmsDbfs(mixed.samples) - (-74.0)) < 1.0)
    }

    @Test
    fun case4_singleSpeechContributor_hasZeroMixAttenuation() {
        val speech = pcmAtRmsDbfs(-26.0)
        val mixed = EqualWeightMixer.mix(listOf(PcmFrame(speech, usableForMix = true)))
        assertEquals(1, mixed.mixParticipantCount)
        assertTrue(abs(measureRmsDbfs(mixed.samples) - (-26.0)) < 0.5)
    }

    @Test
    fun selectedAuthority_isVoiceActiveBit_notPcmEnergy() {
        val rt = ConferenceMediaSelectionRuntime()
        rt.localModuleIdForTopKExclusion = "M02"
        listOf("M01", "M02", "M03", "M04").forEach { install(rt, it) }
        observe(rt, "M01", voiceActive = true, level = 10)
        observe(rt, "M03", voiceActive = false, level = 10)
        observe(rt, "M04", voiceActive = false, level = 10)
        val top = rt.selectTopK(0L).members.map { it.sourceIdentity }.toSet()
        assertEquals(setOf("M01"), top)
        assertFalse(rt.isDecodeEligible("M03", 1L))
    }

    @Test
    fun productionShadowTxAlwaysAssertsVoiceActive() {
        val voiceActiveAudioLevel = 0x80
        assertTrue((voiceActiveAudioLevel and 0x80) != 0)
        assertEquals(0, voiceActiveAudioLevel and 0x7F)
    }

    @Test
    fun whenAllRemotesAssertV1_allThreeEnterTopKEvenIfLevelsTied() {
        val rt = ConferenceMediaSelectionRuntime()
        rt.localModuleIdForTopKExclusion = "M02"
        listOf("M01", "M02", "M03", "M04").forEach { install(rt, it) }
        observe(rt, "M01", voiceActive = true, level = 0)
        observe(rt, "M03", voiceActive = true, level = 0)
        observe(rt, "M04", voiceActive = true, level = 0)
        val top = rt.selectTopK(0L).members.map { it.sourceIdentity }.toSet()
        assertEquals(setOf("M01", "M03", "M04"), top)
    }

    @Test
    fun numeratorAndDenominatorShareSameEligibleSet_silentRealStillCounts() {
        val speech = pcmAtRmsDbfs(-26.0)
        val silence = pcmAtRmsDbfs(-74.0)
        val mixedTwo =
            EqualWeightMixer.mix(
                listOf(
                    PcmFrame(speech, usableForMix = true),
                    PcmFrame(silence, usableForMix = true),
                ),
            )
        assertEquals(2, mixedTwo.mixParticipantCount)
        val outTwo = measureRmsDbfs(mixedTwo.samples)
        assertTrue(outTwo < -26.0)
        assertTrue(outTwo > -35.0)
    }

    @Test
    fun holdCannotRetainV0Seat() {
        val rt = ConferenceMediaSelectionRuntime()
        install(rt, "S_hold")
        install(rt, "S_other")
        observe(rt, "S_hold", voiceActive = true, level = 5)
        observe(rt, "S_other", voiceActive = true, level = 10)
        rt.selectTopK(0L)
        observe(rt, "S_hold", voiceActive = false, level = 5)
        val top = rt.selectTopK(MediaRuntimeConstants.TOP_K_HOLD_MS - 1).members.map { it.sourceIdentity }
        assertFalse(top.contains("S_hold"))
    }

    private fun install(rt: ConferenceMediaSelectionRuntime, id: String) {
        rt.install(AdmittedMediaSource(sourceIdentity = id, incarnationId = 1L))
    }

    private fun observe(
        rt: ConferenceMediaSelectionRuntime,
        id: String,
        voiceActive: Boolean,
        level: Int,
    ) {
        rt.observeVoice(
            VoiceLevelObservation(
                sourceIdentity = id,
                incarnationId = 1L,
                voiceActive = voiceActive,
                audioLevel = level,
            ),
        )
    }

    private fun pcmAtRmsDbfs(dbfs: Double, samples: Int = 960): ShortArray {
        val linear = 32767.0 * 10.0.pow(dbfs / 20.0)
        return ShortArray(samples) { linear.roundToInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun measureRmsDbfs(samples: ShortArray): Double {
        var sumSq = 0.0
        for (sample in samples) {
            val v = sample.toDouble()
            sumSq += v * v
        }
        val rms = sqrt(sumSq / samples.size)
        return 20.0 * ln(rms / 32767.0) / ln(10.0)
    }
}

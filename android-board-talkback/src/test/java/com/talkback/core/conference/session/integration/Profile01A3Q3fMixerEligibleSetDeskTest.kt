package com.talkback.core.conference.session.integration

import com.talkback.core.conference.playout.AvailableMixCandidate
import com.talkback.core.conference.playout.admitMixEligible
import com.talkback.core.conference.runtime.AdmittedMediaSource
import com.talkback.core.conference.runtime.AdmittedMediaSourceRegistry
import com.talkback.core.conference.runtime.EqualWeightMixer
import com.talkback.core.conference.runtime.MixedBlock
import com.talkback.core.conference.runtime.PcmFrame
import com.talkback.core.conference.runtime.SourceMixInputKind
import com.talkback.core.conference.runtime.VoiceLevelObservation
import com.talkback.core.conference.runtime.VoiceLevelStore
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
 * A3-Q3F mixer admission: MIX_ELIGIBLE = AVAILABLE ∩ ACTIVE.
 * Numerator and denominator share the same eligible set via EqualWeightMixer.
 */
class Profile01A3Q3fMixerEligibleSetDeskTest {
    @Test
    fun case1_oneActiveSpeech_twoInactiveSilence_mixesAtUnityGain() {
        val mixed =
            mixEligible(
                speech("M01", voiceActive = true),
                silence("M03", voiceActive = false),
                silence("M04", voiceActive = false),
            )
        assertEquals(1, mixed.mixParticipantCount)
        assertTrue(abs(measureRmsDbfs(mixed.samples) - (-26.0)) < 0.5)
    }

    @Test
    fun case2_numeratorAndDenominatorShareSameEligibleSet_notHalfFix() {
        val mixed =
            mixEligible(
                speech("M01", voiceActive = true),
                silence("M03", voiceActive = false),
                silence("M04", voiceActive = false),
            )
        assertEquals(1, mixed.mixParticipantCount)
        val outRms = measureRmsDbfs(mixed.samples)
        assertFalse("half-fix would attenuate to ~-35.5 dBFS", abs(outRms - (-35.5)) < 1.0)
        assertTrue(abs(outRms - (-26.0)) < 0.5)
    }

    @Test
    fun case3_allInactive_producesEmptyMix_notThreeWaySilence() {
        val mixed =
            mixEligible(
                silence("M01", voiceActive = false),
                silence("M03", voiceActive = false),
                silence("M04", voiceActive = false),
            )
        assertEquals(0, mixed.mixParticipantCount)
        assertTrue(mixed.samples.isEmpty())
    }

    @Test
    fun case4_plcFollowsVoiceActive_notPcmEnergy() {
        val mixed =
            mixEligible(
                candidate("M01", voiceActive = true, pcmAtRmsDbfs(-26.0), SourceMixInputKind.PLC),
                candidate("M03", voiceActive = false, pcmAtRmsDbfs(-74.0), SourceMixInputKind.PLC),
            )
        assertEquals(1, mixed.mixParticipantCount)
        assertTrue(abs(measureRmsDbfs(mixed.samples) - (-26.0)) < 0.5)
    }

    @Test
    fun case5_allActive_preservesQ3eThreeWayAttenuation() {
        val mixed =
            mixEligible(
                speech("M01", voiceActive = true),
                silence("M03", voiceActive = true),
                silence("M04", voiceActive = true),
            )
        assertEquals(3, mixed.mixParticipantCount)
        val outRms = measureRmsDbfs(mixed.samples)
        assertTrue("out=$outRms", abs(outRms - (-35.5)) < 1.0)
        assertTrue(abs(outRms - (-26.0) - (-9.54)) < 1.0)
    }

    @Test
    fun missingVoiceObservation_failClosed_excludedFromMix() {
        val registry = AdmittedMediaSourceRegistry()
        val voiceStore = VoiceLevelStore(registry)
        install(registry, voiceStore, "M01", voiceActive = true)
        install(registry, "M03")
        val mixed =
            mixFromStore(
                voiceStore,
                speech("M01", voiceActive = true),
                silence("M03", voiceActive = false),
            )
        assertEquals(1, mixed.mixParticipantCount)
    }

    @Test
    fun incarnationMismatch_failClosed_excludedFromMix() {
        val registry = AdmittedMediaSourceRegistry()
        val voiceStore = VoiceLevelStore(registry)
        registry.install(AdmittedMediaSource(sourceIdentity = "M03", incarnationId = 2L))
        voiceStore.observe(
            VoiceLevelObservation(
                sourceIdentity = "M03",
                incarnationId = 1L,
                voiceActive = true,
                audioLevel = 10,
            ),
        )
        val mixed =
            mixFromStore(
                voiceStore,
                silence("M03", voiceActive = true, incarnationId = 2L),
            )
        assertEquals(0, mixed.mixParticipantCount)
    }

    private data class MixFixture(
        val id: String,
        val incarnationId: Long = 1L,
        val voiceActive: Boolean,
        val pcm: ShortArray,
        val kind: SourceMixInputKind = SourceMixInputKind.REAL,
        val observeVoice: Boolean = true,
    )

    private fun mixEligible(vararg fixtures: MixFixture): MixedBlock {
        val registry = AdmittedMediaSourceRegistry()
        val voiceStore = VoiceLevelStore(registry)
        val available = mutableListOf<AvailableMixCandidate>()
        for (fixture in fixtures) {
            registry.install(AdmittedMediaSource(fixture.id, fixture.incarnationId))
            if (fixture.observeVoice) {
                voiceStore.observe(
                    VoiceLevelObservation(
                        sourceIdentity = fixture.id,
                        incarnationId = fixture.incarnationId,
                        voiceActive = fixture.voiceActive,
                        audioLevel = 10,
                    ),
                )
            }
            available +=
                AvailableMixCandidate(
                    sourceIdentity = fixture.id,
                    incarnationId = fixture.incarnationId,
                    pcm = PcmFrame(fixture.pcm, usableForMix = true),
                    kind = fixture.kind,
                )
        }
        return mixFromStore(voiceStore, available)
    }

    private fun mixFromStore(
        voiceStore: VoiceLevelStore,
        available: List<AvailableMixCandidate>,
    ) = EqualWeightMixer.mix(admitMixEligible(voiceStore, available).map { it.pcm })

    private fun mixFromStore(
        voiceStore: VoiceLevelStore,
        vararg fixtures: MixFixture,
    ): MixedBlock {
        val available =
            fixtures.map { fixture ->
                AvailableMixCandidate(
                    sourceIdentity = fixture.id,
                    incarnationId = fixture.incarnationId,
                    pcm = PcmFrame(fixture.pcm, usableForMix = true),
                    kind = fixture.kind,
                )
            }
        return mixFromStore(voiceStore, available)
    }

    private fun speech(
        id: String,
        voiceActive: Boolean,
        incarnationId: Long = 1L,
    ) = MixFixture(id, incarnationId, voiceActive, pcmAtRmsDbfs(-26.0))

    private fun silence(
        id: String,
        voiceActive: Boolean,
        incarnationId: Long = 1L,
    ) = MixFixture(id, incarnationId, voiceActive, pcmAtRmsDbfs(-74.0))

    private fun candidate(
        id: String,
        voiceActive: Boolean,
        pcm: ShortArray,
        kind: SourceMixInputKind,
        incarnationId: Long = 1L,
    ) = MixFixture(id, incarnationId, voiceActive, pcm, kind)

    private fun install(
        registry: AdmittedMediaSourceRegistry,
        voiceStore: VoiceLevelStore,
        id: String,
        voiceActive: Boolean,
    ) {
        install(registry, id)
        voiceStore.observe(
            VoiceLevelObservation(
                sourceIdentity = id,
                incarnationId = 1L,
                voiceActive = voiceActive,
                audioLevel = 10,
            ),
        )
    }

    private fun install(
        registry: AdmittedMediaSourceRegistry,
        id: String,
        incarnationId: Long = 1L,
    ) {
        registry.install(AdmittedMediaSource(sourceIdentity = id, incarnationId = incarnationId))
    }

    private fun pcmAtRmsDbfs(dbfs: Double, samples: Int = 960): ShortArray {
        val linear = 32767.0 * 10.0.pow(dbfs / 20.0)
        return ShortArray(samples) { linear.roundToInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun measureRmsDbfs(samples: ShortArray): Double {
        if (samples.isEmpty()) return Double.NEGATIVE_INFINITY
        var sumSq = 0.0
        for (sample in samples) {
            val v = sample.toDouble()
            sumSq += v * v
        }
        val rms = sqrt(sumSq / samples.size)
        return 20.0 * ln(rms / 32767.0) / ln(10.0)
    }
}

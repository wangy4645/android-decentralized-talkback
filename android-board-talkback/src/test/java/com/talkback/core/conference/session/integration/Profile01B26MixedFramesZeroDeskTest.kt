package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.EqualWeightMixer
import com.talkback.core.conference.runtime.MixCycleResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Profile01B26MixedFramesZeroDeskTest {
    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        lines.clear()
        Profile01B26MixedFramesZeroObservation.resetForTest()
        Profile01B26MixedFramesZeroObservation.testLogSink = { lines += it }
    }

    @After
    fun tearDown() {
        Profile01B26MixedFramesZeroObservation.resetForTest()
    }

    @Test
    fun classify_zeroMixFunnel() {
        assertEquals(
            Profile01B26MixedFramesZeroObservation.ZeroMixCause.TOPK_EMPTY,
            Profile01B26MixedFramesZeroObservation.classifyZeroMix(0, 0, 0, false),
        )
        assertEquals(
            Profile01B26MixedFramesZeroObservation.ZeroMixCause.ALL_TICK_EMPTY,
            Profile01B26MixedFramesZeroObservation.classifyZeroMix(2, 0, 0, false),
        )
        assertEquals(
            Profile01B26MixedFramesZeroObservation.ZeroMixCause.VAD_GATE,
            Profile01B26MixedFramesZeroObservation.classifyZeroMix(2, 2, 0, false),
        )
        assertNull(
            Profile01B26MixedFramesZeroObservation.classifyZeroMix(2, 2, 1, true),
        )
    }

    @Test
    fun zeroMixWindow_emitsAfter25Ticks() {
        val emptyCycle = emptyMixCycle()
        repeat(Profile01B26MixedFramesZeroObservation.WINDOW) {
            Profile01B26MixedFramesZeroObservation.noteRenderMixTick(
                sessionId = "s1",
                mixCycle = emptyCycle,
                topKCount = 0,
                availableCount = 0,
                eligibleCount = 0,
                realTicks = 0,
                plcTicks = 0,
                emptyTicks = 0,
            )
        }
        val line = lines.single { it.startsWith(Profile01B26MixedFramesZeroObservation.PHASE_ZERO_MIX) }
        assertTrue(line.contains("topkEmpty=25"))
        assertTrue(line.contains("lastCause=TOPK_EMPTY"))
    }

    @Test
    fun liveness_emitsOnDemand() {
        Profile01B26MixedFramesZeroObservation.noteMediaLiveness(
            phase = "RX_ARMED",
            sessionId = "s1",
            detail = "outcome=APPLIED",
        )
        val line = lines.single { it.startsWith(Profile01B26MixedFramesZeroObservation.PHASE_MEDIA_LIVENESS) }
        assertTrue(line.contains("phase=RX_ARMED"))
        assertTrue(line.contains("session=s1"))
    }

    private fun emptyMixCycle(): MixCycleResult =
        MixCycleResult(
            topKIdentities = emptySet(),
            decodeInvocationIdentities = emptySet(),
            mixParticipantIdentities = emptySet(),
            mixedBlock = EqualWeightMixer.mix(emptyList()),
            sourcePullDispositions = emptyMap(),
            sourceMixInputs = emptyMap(),
            renderTickKinds = emptyMap(),
        )
}

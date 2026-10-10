package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.MixCycleResult
import com.talkback.core.conference.runtime.MixedBlock
import com.talkback.core.conference.runtime.SlotPullDisposition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Profile01A3MixOutputTelemetryPullWindowTest {
    @After
    fun tearDown() {
        Profile01A3MixOutputTelemetry.resetForTest()
        Profile01ShadowRuntimeObservability.clearActiveSession("s1")
    }

    @Test
    fun aggregationWindow_includesPerSourcePullCountsAndTotals() {
        var lastLine: String? = null
        Profile01A3MixOutputTelemetry.testLogSink = { lastLine = it }
        val sessionId = "s1"
        repeat(25) { cycle ->
            val source = if (cycle % 2 == 0) "A" else "B"
            val pull =
                when (cycle % 3) {
                    0 -> SlotPullDisposition.PLC_SYNTHESIS
                    1 -> SlotPullDisposition.EMPTY
                    else -> SlotPullDisposition.DECODE_FRAME
                }
            val inMix = pull == SlotPullDisposition.DECODE_FRAME
            val samples = if (inMix) shortArrayOf(1000, -1000) else shortArrayOf()
            record(
                sessionId = sessionId,
                topK = setOf(source),
                pull = mapOf(source to pull),
                mixIds = if (inMix) setOf(source) else emptySet(),
                samples = samples,
                plcCount = cycle,
            )
        }
        val line = lastLine ?: error("expected A3_MIX_OUTPUT emit")
        assertTrue(line.contains("pullRealTotal="))
        assertTrue(line.contains("pullPlcTotal="))
        assertTrue(line.contains("pullEmptyTotal="))
        assertTrue(line.contains("plcCountDelta=24"))
        assertTrue(line.contains("sourceContribution="))
    }

    @Test
    fun resolvedMixSlotDeltaClassifiesForwardBackwardAndNormal() {
        assertEquals(
            Profile01ShadowRuntimeObservability.ResolvedMixSlotDeltaClass.BACKWARD,
            Profile01ShadowRuntimeObservability.classifyResolvedMixSlotDelta(-5L),
        )
        assertEquals(
            Profile01ShadowRuntimeObservability.ResolvedMixSlotDeltaClass.NORMAL,
            Profile01ShadowRuntimeObservability.classifyResolvedMixSlotDelta(1L),
        )
        assertEquals(
            Profile01ShadowRuntimeObservability.ResolvedMixSlotDeltaClass.FORWARD_SMALL,
            Profile01ShadowRuntimeObservability.classifyResolvedMixSlotDelta(50L),
        )
        assertEquals(
            Profile01ShadowRuntimeObservability.ResolvedMixSlotDeltaClass.FORWARD_LARGE,
            Profile01ShadowRuntimeObservability.classifyResolvedMixSlotDelta(200L),
        )
    }

    private fun record(
        sessionId: String,
        topK: Set<String>,
        pull: Map<String, SlotPullDisposition>,
        mixIds: Set<String>,
        samples: ShortArray,
        plcCount: Int,
    ) {
        Profile01A3MixOutputTelemetry.recordCycle(
            sessionId = sessionId,
            owner = Profile01A3MixOutputTelemetry.OWNER_MULTICAST_PRODUCTION,
            mixCycle =
                MixCycleResult(
                    topKIdentities = topK,
                    decodeInvocationIdentities = mixIds,
                    mixParticipantIdentities = mixIds,
                    mixedBlock =
                        MixedBlock(
                            samples = samples,
                            mixParticipantCount = mixIds.size,
                            peakAbsFs = if (samples.isEmpty()) 0.0 else 1000.0 / 32768.0,
                        ),
                    sourcePullDispositions = pull,
                ),
            playoutObserved = samples.isNotEmpty(),
            pipelinePlcCount = plcCount,
        )
    }
}

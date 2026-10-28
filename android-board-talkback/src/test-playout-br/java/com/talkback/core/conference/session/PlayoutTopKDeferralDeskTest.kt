package com.talkback.core.conference.session

import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.PcmFrame
import com.talkback.core.conference.runtime.TopKMember
import com.talkback.core.conference.session.integration.BrightScreenMeetingMixPolicy
import com.talkback.core.conference.session.integration.Profile01A3MixOutputTelemetry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D1 desk — T1–T5 from D1-DESK-TEST-DESIGN-20261016.txt (no field test).
 */
class PlayoutTopKDeferralDeskTest {
    private val sessionId = "d1-desk-session"

    @Before
    fun setUp() {
        BrightScreenMeetingMixPolicy.resetForTest()
        PlayoutTopKDeferral.resetForTest()
        Profile01A3MixOutputTelemetry.resetForTest()
    }

    @After
    fun tearDown() {
        PlayoutTopKDeferral.resetForTest()
        Profile01A3MixOutputTelemetry.resetForTest()
    }

    private fun member(id: String) = TopKMember(sourceIdentity = id, incarnationId = 1L, audioLevel = 100)

    private fun ranked(vararg ids: String) = ids.map { member(it) }

    @Test
    fun t1_fallbackAndDeferralDoNotOverlapOnSameTick() {
        val ranked = ranked("A", "B", "C")
        val effectiveOnlyA = ranked("A")
        val fallbackVoice = ranked("V1")
        val partialPick =
            PlayoutMixSlotResolver.selectTopKForPlayoutPick(
                rankedTopK = ranked,
                deferralEffective = effectiveOnlyA,
                fallbackTopK = ranked,
                rankedAnyPullable = true,
            )
        assertEquals(listOf("A"), partialPick.map { it.sourceIdentity })

        val allUnpullablePick =
            PlayoutMixSlotResolver.selectTopKForPlayoutPick(
                rankedTopK = ranked,
                deferralEffective = emptyList(),
                fallbackTopK = fallbackVoice,
                rankedAnyPullable = false,
            )
        assertEquals(listOf("V1"), allUnpullablePick.map { it.sourceIdentity })
        assertFalse(
            allUnpullablePick.map { it.sourceIdentity }.toSet()
                .intersect(effectiveOnlyA.map { it.sourceIdentity }.toSet())
                .isNotEmpty(),
        )
    }

    @Test
    fun t2_singleWallClockBudgetAndNotRankedNoTimer() {
        val pullable = mutableMapOf("B" to false, "C" to false)
        val three = ranked("A", "B", "C")
        pullable["A"] = true

        var result =
            PlayoutTopKDeferral.advanceEffectiveTopK(
                sessionId,
                three,
                0L,
                isPullable = { pullable[it.sourceIdentity] == true },
            )
        assertEquals(3, result.effectiveMembers.size)
        assertFalse(PlayoutTopKDeferral.isDeferred(sessionId, "B", 1L))

        result =
            PlayoutTopKDeferral.advanceEffectiveTopK(
                sessionId,
                three,
                119L,
                isPullable = { pullable[it.sourceIdentity] == true },
            )
        assertFalse(PlayoutTopKDeferral.isDeferred(sessionId, "B", 1L))

        val events = mutableListOf<String>()
        PlayoutTopKDeferral.testEventSink = { events += it }
        result =
            PlayoutTopKDeferral.advanceEffectiveTopK(
                sessionId,
                three,
                121L,
                isPullable = { pullable[it.sourceIdentity] == true },
            )
        assertTrue(PlayoutTopKDeferral.isDeferred(sessionId, "B", 1L))
        assertTrue(PlayoutTopKDeferral.isDeferred(sessionId, "C", 1L))
        assertTrue(events.any { it.startsWith("MIX_DEFERRED") && it.contains("sourceId=B") })
        assertEquals(1, result.effectiveMembers.size)
        assertEquals("A", result.effectiveMembers.single().sourceIdentity)

        PlayoutTopKDeferral.resetForTest()
        pullable["B"] = false
        repeat(10) {
            PlayoutTopKDeferral.advanceEffectiveTopK(
                sessionId,
                ranked("B"),
                10L,
                isPullable = { false },
            )
        }
        assertFalse(PlayoutTopKDeferral.isDeferred(sessionId, "B", 1L))

        PlayoutTopKDeferral.resetForTest()
        PlayoutTopKDeferral.advanceEffectiveTopK(
            sessionId,
            ranked("B"),
            0L,
            isPullable = { false },
        )
        PlayoutTopKDeferral.advanceEffectiveTopK(sessionId, emptyList(), 5_000L, isPullable = { false })
        PlayoutTopKDeferral.advanceEffectiveTopK(
            sessionId,
            ranked("B"),
            5_020L,
            isPullable = { false },
        )
        assertFalse(PlayoutTopKDeferral.isDeferred(sessionId, "B", 1L))
        PlayoutTopKDeferral.advanceEffectiveTopK(
            sessionId,
            ranked("B"),
            5_020L + PlayoutTopKDeferral.WAIT_BUDGET_MS + 1,
            isPullable = { false },
        )
        assertTrue(PlayoutTopKDeferral.isDeferred(sessionId, "B", 1L))
    }

    @Test
    fun t3_recoveryK3FadeM5AndGainSteps() {
        val events = mutableListOf<String>()
        PlayoutTopKDeferral.testEventSink = { events += it }
        val one = ranked("B")
        val budget = PlayoutTopKDeferral.WAIT_BUDGET_MS + 1
        PlayoutTopKDeferral.advanceEffectiveTopK(sessionId, one, 0L) { false }
        PlayoutTopKDeferral.advanceEffectiveTopK(sessionId, one, budget) { false }
        assertTrue(PlayoutTopKDeferral.isDeferred(sessionId, "B", 1L))

        PlayoutTopKDeferral.advanceEffectiveTopK(sessionId, one, budget + 20) { true }
        assertTrue(PlayoutTopKDeferral.isDeferred(sessionId, "B", 1L))
        PlayoutTopKDeferral.advanceEffectiveTopK(sessionId, one, budget + 40) { true }
        assertTrue(PlayoutTopKDeferral.isDeferred(sessionId, "B", 1L))

        PlayoutTopKDeferral.advanceEffectiveTopK(sessionId, one, budget + 60) { true }
        assertFalse(PlayoutTopKDeferral.isDeferred(sessionId, "B", 1L))
        assertTrue(events.any { it.startsWith("MIX_RECOVERED") })

        assertEquals(0.2f, PlayoutTopKDeferral.fadeGainLinear(sessionId, "B", 1L), 0.001f)
        PlayoutTopKDeferral.completeMixTick(sessionId, PlayoutTopKDeferral.lastTickSnapshot(sessionId)!!, setOf("B"), true)
        assertEquals(0.4f, PlayoutTopKDeferral.fadeGainLinear(sessionId, "B", 1L), 0.001f)

        PlayoutTopKDeferral.resetForTest()
        PlayoutTopKDeferral.advanceEffectiveTopK(sessionId, one, 0L) { false }
        PlayoutTopKDeferral.advanceEffectiveTopK(sessionId, one, budget) { false }
        PlayoutTopKDeferral.advanceEffectiveTopK(sessionId, one, budget + 20) { true }
        PlayoutTopKDeferral.advanceEffectiveTopK(sessionId, one, budget + 40) { true }
        PlayoutTopKDeferral.advanceEffectiveTopK(sessionId, one, budget + 60) { false }
        assertTrue(PlayoutTopKDeferral.isDeferred(sessionId, "B", 1L))
    }

    @Test
    fun t4_telemetryContributorSeparation() {
        val snap =
            PlayoutTopKDeferral.TickSnapshot(
                rankedTopKCount = 3,
                effectiveTopKCount = 1,
                deferredSourceCount = 2,
                pullSucceededSourceCount = 1,
                mixProducedThisTick = true,
                allTopKUnpullableTick = false,
            )
        val completed =
            PlayoutTopKDeferral.completeMixTick(
                sessionId,
                snap,
                mixParticipantIdentities = setOf("A"),
                mixProducedPcm = true,
            )
        assertEquals(1, completed.pullSucceededSourceCount)
        assertTrue(completed.mixProducedThisTick)
        assertEquals(2, completed.deferredSourceCount)

        val allUnpullable =
            PlayoutTopKDeferral.TickSnapshot(
                rankedTopKCount = 3,
                effectiveTopKCount = 0,
                deferredSourceCount = 3,
                pullSucceededSourceCount = 0,
                mixProducedThisTick = false,
                allTopKUnpullableTick = true,
            )
        assertTrue(allUnpullable.allTopKUnpullableTick)
        assertFalse(allUnpullable.mixProducedThisTick)
    }

    @Test
    fun t5_fadeDoesNotDoubleEnergy() {
        val samples = ShortArray(160) { 10_000 }
        val pcm = PcmFrame(samples, usableForMix = true)
        PlayoutTopKDeferral.resetForTest()
        val one = ranked("B")
        PlayoutTopKDeferral.advanceEffectiveTopK(sessionId, one, 0L) { false }
        PlayoutTopKDeferral.advanceEffectiveTopK(
            sessionId,
            one,
            PlayoutTopKDeferral.WAIT_BUDGET_MS + 1,
        ) { false }
        repeat(3) {
            PlayoutTopKDeferral.advanceEffectiveTopK(sessionId, one, 10_000L + it * 20) { true }
        }
        val faded = PlayoutTopKDeferral.applyFadeForMix(sessionId, "B", 1L, pcm)
        val gain = PlayoutTopKDeferral.fadeGainLinear(sessionId, "B", 1L)
        assertTrue(gain < 1f)
        assertEquals((samples[0] * gain).toInt(), faded.samples[0].toInt())
        val slotMs = MediaJitterConstants.MEDIA_SLOT_MS
        assertTrue(slotMs == 20L)
    }
}

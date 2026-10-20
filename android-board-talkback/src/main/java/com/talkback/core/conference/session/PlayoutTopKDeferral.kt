package com.talkback.core.conference.session

import com.talkback.core.conference.runtime.AdmittedMediaSource
import com.talkback.core.conference.runtime.MediaExecutionPipeline
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.PcmFrame
import com.talkback.core.conference.runtime.TopKMember
import com.talkback.core.conference.session.integration.BrightScreenMeetingMixPolicy
import com.talkback.core.conference.session.integration.Profile01ShadowRuntimeObservability
import com.talkback.core.conference.transport.PerIncarnationIngressTimelineRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * D1-b + D1-a — bounded wait then effective Top-K exclusion for unpullable ranked sources.
 */
internal object PlayoutTopKDeferral {
    const val WAIT_BUDGET_MS: Long = MediaJitterConstants.MAX_PLAYOUT_DELAY_MS
    const val TICK_GUARD_MAX: Int = 6
    const val RECOVERY_PULLABLE_TICKS: Int = 3
    const val FADE_IN_CYCLES: Int = 5

    data class SourceKey(
        val sourceIdentity: String,
        val incarnationId: Long,
    )

    data class TickSnapshot(
        val rankedTopKCount: Int,
        val effectiveTopKCount: Int,
        val deferredSourceCount: Int,
        val pullSucceededSourceCount: Int,
        val mixProducedThisTick: Boolean,
        val allTopKUnpullableTick: Boolean,
    )

    data class AdvanceResult(
        val effectiveMembers: List<TopKMember>,
        val snapshot: TickSnapshot,
    )

    private data class SourceState(
        var waitSegmentStartMs: Long? = null,
        var unpullableTicksInSegment: Int = 0,
        var deferred: Boolean = false,
        var recoveryStreak: Int = 0,
        var fadeInRemaining: Int = 0,
        var deferredTicksWhileExcluded: Int = 0,
        var tickGuardBreaches: Int = 0,
    )

    private val perSession = ConcurrentHashMap<String, ConcurrentHashMap<SourceKey, SourceState>>()
    private val lastTickSnapshot = ConcurrentHashMap<String, TickSnapshot>()

    @Volatile
    var testEventSink: ((String) -> Unit)? = null

    fun lastTickSnapshot(sessionId: String): TickSnapshot? = lastTickSnapshot[sessionId]

    fun isSourceDeferred(
        sessionId: String,
        sourceIdentity: String,
        incarnationId: Long,
    ): Boolean {
        val st = perSession[sessionId]?.get(SourceKey(sourceIdentity, incarnationId))
        return st?.deferred == true
    }

    fun clearSession(sessionId: String) {
        perSession.remove(sessionId)
        lastTickSnapshot.remove(sessionId)
    }

    fun resetForTest() {
        perSession.clear()
        lastTickSnapshot.clear()
        testEventSink = null
    }

    fun advanceEffectiveTopK(
        sessionId: String,
        ranked: List<TopKMember>,
        admitted: Map<String, AdmittedMediaSource>,
        pipeline: MediaExecutionPipeline,
        ingressTimeline: PerIncarnationIngressTimelineRegistry,
        tickMediaTimeMs: Long,
        targetMediaSlot: Long?,
        selectionNowMs: Long,
    ): AdvanceResult {
        if (!BrightScreenMeetingMixPolicy.playableTopKDeferralEnabled) {
            val allUnpullable =
                ranked.isNotEmpty() &&
                    ranked.none {
                        isMemberPullable(
                            it,
                            admitted,
                            pipeline,
                            ingressTimeline,
                            tickMediaTimeMs,
                            targetMediaSlot,
                            selectionNowMs,
                        )
                    }
            return AdvanceResult(
                effectiveMembers = ranked,
                snapshot =
                    TickSnapshot(
                        rankedTopKCount = ranked.size,
                        effectiveTopKCount = ranked.size,
                        deferredSourceCount = 0,
                        pullSucceededSourceCount = 0,
                        mixProducedThisTick = false,
                        allTopKUnpullableTick = allUnpullable,
                    ),
            )
        }
        return advanceEffectiveTopK(
            sessionId = sessionId,
            ranked = ranked,
            selectionNowMs = selectionNowMs,
            isPullable = { member ->
                isMemberPullable(
                    member,
                    admitted,
                    pipeline,
                    ingressTimeline,
                    tickMediaTimeMs,
                    targetMediaSlot,
                    selectionNowMs,
                )
            },
        )
    }

    internal fun advanceEffectiveTopK(
        sessionId: String,
        ranked: List<TopKMember>,
        selectionNowMs: Long,
        isPullable: (TopKMember) -> Boolean,
    ): AdvanceResult {
        val store = perSession.computeIfAbsent(sessionId) { ConcurrentHashMap() }
        val rankedKeys = ranked.map { SourceKey(it.sourceIdentity, it.incarnationId) }.toSet()
        store.keys.retainAll(rankedKeys)

        var deferredCount = 0
        var allUnpullable = ranked.isNotEmpty()

        for (member in ranked) {
            val key = SourceKey(member.sourceIdentity, member.incarnationId)
            val state = store.computeIfAbsent(key) { SourceState() }
            val pullable = isPullable(member)
            if (pullable) {
                allUnpullable = false
                state.waitSegmentStartMs = null
                state.unpullableTicksInSegment = 0
                if (state.deferred) {
                    state.recoveryStreak++
                    if (state.recoveryStreak >= RECOVERY_PULLABLE_TICKS) {
                        state.deferred = false
                        state.recoveryStreak = 0
                        state.fadeInRemaining = FADE_IN_CYCLES
                        emitEvent(
                            sessionId,
                            "MIX_RECOVERED",
                            mapOf(
                                "sourceId" to member.sourceIdentity,
                                "incarnation" to member.incarnationId.toString(),
                                "deferredTicks" to state.deferredTicksWhileExcluded.toString(),
                                "fadeInCycles" to FADE_IN_CYCLES.toString(),
                            ),
                        )
                        state.deferredTicksWhileExcluded = 0
                    }
                } else {
                    state.recoveryStreak = 0
                }
            } else {
                if (state.waitSegmentStartMs == null) {
                    state.waitSegmentStartMs = selectionNowMs
                    state.unpullableTicksInSegment = 1
                } else {
                    state.unpullableTicksInSegment++
                    if (state.unpullableTicksInSegment > TICK_GUARD_MAX) {
                        state.tickGuardBreaches++
                    }
                }
                if (!state.deferred) {
                    val start = state.waitSegmentStartMs ?: selectionNowMs
                    val waitedMs = selectionNowMs - start
                    if (waitedMs >= WAIT_BUDGET_MS) {
                        state.deferred = true
                        state.recoveryStreak = 0
                        state.deferredTicksWhileExcluded = 0
                        emitEvent(
                            sessionId,
                            "MIX_DEFERRED",
                            mapOf(
                                "sourceId" to member.sourceIdentity,
                                "incarnation" to member.incarnationId.toString(),
                                "waitedMs" to waitedMs.toString(),
                                "rankedStillContains" to "true",
                            ),
                        )
                    }
                } else {
                    state.deferredTicksWhileExcluded++
                    state.recoveryStreak = 0
                }
            }
            if (state.deferred) {
                deferredCount++
            }
        }

        val effective =
            ranked.filter { member ->
                val st = store[SourceKey(member.sourceIdentity, member.incarnationId)]
                st?.deferred != true
            }

        val snapshot =
            TickSnapshot(
                rankedTopKCount = ranked.size,
                effectiveTopKCount = effective.size,
                deferredSourceCount = deferredCount,
                pullSucceededSourceCount = 0,
                mixProducedThisTick = false,
                allTopKUnpullableTick = allUnpullable,
            )
        lastTickSnapshot[sessionId] = snapshot
        return AdvanceResult(effectiveMembers = effective, snapshot = snapshot)
    }

    fun completeMixTick(
        sessionId: String,
        prior: TickSnapshot,
        mixParticipantIdentities: Set<String>,
        mixProducedPcm: Boolean,
    ): TickSnapshot {
        val pullSucceeded = mixParticipantIdentities.size
        val completed =
            prior.copy(
                pullSucceededSourceCount = pullSucceeded,
                mixProducedThisTick = mixProducedPcm,
            )
        val store = perSession[sessionId]
        if (store != null) {
            for (identity in mixParticipantIdentities) {
                val state =
                    store.entries.firstOrNull { it.key.sourceIdentity == identity }?.value
                        ?: continue
                if (state.fadeInRemaining > 0) {
                    state.fadeInRemaining--
                }
            }
        }
        lastTickSnapshot[sessionId] = completed
        return completed
    }

    fun fadeGainLinear(
        sessionId: String,
        sourceIdentity: String,
        incarnationId: Long,
    ): Float {
        val state = perSession[sessionId]?.get(SourceKey(sourceIdentity, incarnationId))
        val remaining = state?.fadeInRemaining ?: 0
        if (remaining <= 0) {
            return 1f
        }
        val cycleIndex = FADE_IN_CYCLES - remaining + 1
        return (cycleIndex.toFloat() / FADE_IN_CYCLES.toFloat()).coerceIn(0f, 1f)
    }

    fun applyFadeForMix(
        sessionId: String,
        sourceIdentity: String,
        incarnationId: Long,
        pcm: PcmFrame,
    ): PcmFrame {
        val gain = fadeGainLinear(sessionId, sourceIdentity, incarnationId)
        if (gain >= 1f) {
            return pcm
        }
        return PcmFrame(
            samples =
                ShortArray(pcm.samples.size) { i ->
                    (pcm.samples[i] * gain).toInt().coerceIn(-32_768, 32_767).toShort()
                },
            usableForMix = pcm.usableForMix,
        )
    }

    fun isDeferred(
        sessionId: String,
        sourceIdentity: String,
        incarnationId: Long,
    ): Boolean = perSession[sessionId]?.get(SourceKey(sourceIdentity, incarnationId))?.deferred == true

    internal fun isMemberPullable(
        member: TopKMember,
        admitted: Map<String, AdmittedMediaSource>,
        pipeline: MediaExecutionPipeline,
        ingressTimeline: PerIncarnationIngressTimelineRegistry,
        tickMediaTimeMs: Long,
        targetMediaSlot: Long?,
        selectionNowMs: Long,
    ): Boolean {
        val source = admitted[member.sourceIdentity] ?: return false
        if (source.incarnationId != member.incarnationId) {
            return false
        }
        val perSourceTarget =
            ingressTimeline.mediaSlotForPlayoutTickMs(
                member.sourceIdentity,
                source.incarnationId,
                tickMediaTimeMs,
            ) ?: targetMediaSlot
                ?: return false
        return PlayoutMixSlotResolver.hasPullablePlayoutSlot(
            pipeline,
            member.sourceIdentity,
            source.incarnationId,
            perSourceTarget,
            selectionNowMs,
        )
    }

    private fun emitEvent(
        sessionId: String,
        phase: String,
        fields: Map<String, String>,
    ) {
        Profile01ShadowRuntimeObservability.logPhase(
            phase = phase,
            sessionId = sessionId,
            fields = fields,
        )
        testEventSink?.invoke(
            buildString {
                append(phase)
                fields.forEach { (k, v) -> append(' ').append(k).append('=').append(v) }
            },
        )
    }
}

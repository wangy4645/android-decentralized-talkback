package com.talkback.core.conference.session



import com.talkback.core.conference.runtime.AdmittedMediaSource

import com.talkback.core.conference.runtime.ConferenceMediaExecutionOrchestrator

import com.talkback.core.conference.runtime.MediaExecutionPipeline

import com.talkback.core.conference.runtime.TopKMember

import com.talkback.core.conference.transport.PerIncarnationIngressTimelineRegistry



/**

 * RCA — multicast playout slot resolution for [ConferenceSessionMediaWiring.resolvePlayoutMixSlot].

 *

 * Baseline uses Top-K sources only (mix decodes Top-K, not all admitted).

 * Each source's RTP slot domain is projected via [PerIncarnationIngressTimelineRegistry] (ADR-0058 Layer A);

 * session [targetMediaSlot] (Layer B) is telemetry-only and must not be used as a universal pull index.

 */

internal object PlayoutMixSlotResolver {

    const val ZERO_PCM_STREAK_RECOVERY_THRESHOLD: Int = 2

    const val MAX_RECOVERY_SLOT_SCAN: Int = 512



    data class PlayoutSlotProgress(

        var lastCommittedMixSlot: Long? = null,

        var lastAttemptedMixSlot: Long? = null,

        var consecutiveZeroMixCycles: Int = 0,

    )



    fun resolve(

        orchestrator: ConferenceMediaExecutionOrchestrator,

        admitted: Map<String, AdmittedMediaSource>,

        ingressTimeline: PerIncarnationIngressTimelineRegistry,

        tickMediaTimeMs: Long,

        targetMediaSlot: Long?,

        progress: PlayoutSlotProgress,

        selectionNowMs: Long,

        onSkipReason: ((PlayoutMixResolveSkipReason) -> Unit)? = null,

    ): ConferenceSessionMediaWiring.BufferedMixSlot? {

        orchestrator.selectTopK(selectionNowMs)

        val pipeline = orchestrator.pipeline

        var topKMembers = orchestrator.selection.currentTopK().members

        topKMembers =
            BrightScreenPlayoutTopKFallback.effectiveTopKMembers(
                selection = orchestrator.selection,
                admitted = admitted,
                pipeline = pipeline,
                ingressTimeline = ingressTimeline,
                tickMediaTimeMs = tickMediaTimeMs,
                targetMediaSlot = targetMediaSlot,
                selectionNowMs = selectionNowMs,
                currentTopK = topKMembers,
            )

        if (topKMembers.isEmpty()) {

            onSkipReason?.invoke(

                diagnoseResolveSkip(

                    orchestrator = orchestrator,

                    admitted = admitted,

                    ingressTimeline = ingressTimeline,

                    tickMediaTimeMs = tickMediaTimeMs,

                    targetMediaSlot = targetMediaSlot,

                    topKMembers = topKMembers,

                ),

            )

            return null

        }

        val baseline =

            pickPerSourcePlayoutSlots(

                pipeline = pipeline,

                admitted = admitted,

                ingressTimeline = ingressTimeline,

                tickMediaTimeMs = tickMediaTimeMs,

                sessionTargetMediaSlot = targetMediaSlot,

                topKMembers = topKMembers,

                nowMs = selectionNowMs,

            )



        val stuckOnRepeat =

            baseline != null &&

                progress.lastAttemptedMixSlot != null &&

                baseline.slot == progress.lastAttemptedMixSlot &&

                progress.consecutiveZeroMixCycles > 0



        val needsRecovery =

            progress.consecutiveZeroMixCycles >= ZERO_PCM_STREAK_RECOVERY_THRESHOLD ||

                baseline == null ||

                stuckOnRepeat



        val chosen =

            if (needsRecovery) {

                pickPerSourceForwardRecoverySlots(

                    pipeline = pipeline,

                    admitted = admitted,

                    ingressTimeline = ingressTimeline,

                    tickMediaTimeMs = tickMediaTimeMs,

                    sessionTargetMediaSlot = targetMediaSlot,

                    topKMembers = topKMembers,

                    progress = progress,

                    nowMs = selectionNowMs,

                ) ?: baseline

            } else {

                baseline

            }



        if (chosen == null) {

            onSkipReason?.invoke(

                diagnoseResolveSkip(

                    orchestrator = orchestrator,

                    admitted = admitted,

                    ingressTimeline = ingressTimeline,

                    tickMediaTimeMs = tickMediaTimeMs,

                    targetMediaSlot = targetMediaSlot,

                    topKMembers = topKMembers,

                ),

            )

        }

        return chosen

    }



    /** B8-C desk / starvation log — classify null resolve without changing mix policy. */

    internal fun diagnoseResolveSkip(

        orchestrator: ConferenceMediaExecutionOrchestrator,

        admitted: Map<String, AdmittedMediaSource>,

        ingressTimeline: PerIncarnationIngressTimelineRegistry,

        tickMediaTimeMs: Long,

        targetMediaSlot: Long?,

        topKMembers: List<TopKMember>,

    ): PlayoutMixResolveSkipReason {

        val pipeline = orchestrator.pipeline

        val topKIds = topKMembers.map { it.sourceIdentity }.toSet()

        if (topKMembers.isEmpty()) {

            val bufferedOffTopK =

                admitted.any { (sourceIdentity, source) ->

                    sourceIdentity !in topKIds &&

                        pipeline.jitterSize(sourceIdentity, source.incarnationId) > 0

                }

            return if (bufferedOffTopK) {

                PlayoutMixResolveSkipReason.BUFFERED_ONLY_OFF_TOP_K

            } else {

                PlayoutMixResolveSkipReason.TOP_K_EMPTY

            }

        }

        var layerAMissing = false

        var bufferEmpty = false

        var gapExceedsReorder = false

        var prefixUnplayable = false

        var bufferBeforeCursor = false

        for (member in topKMembers) {

            val source = admitted[member.sourceIdentity] ?: continue

            val perSourceTarget =

                ingressTimeline.mediaSlotForPlayoutTickMs(

                    member.sourceIdentity,

                    source.incarnationId,

                    tickMediaTimeMs,

                ) ?: targetMediaSlot

            if (perSourceTarget == null) {

                layerAMissing = true

                continue

            }

            val nextExpected =

                pipeline.nextExpectedSlot(member.sourceIdentity, source.incarnationId)

            val buffered =

                pipeline.bufferedSlots(member.sourceIdentity, source.incarnationId)

            if (buffered.isEmpty()) {

                bufferEmpty = true

                continue

            }

            val eligible = buffered.filter { slot -> nextExpected == null || slot >= nextExpected }

            if (eligible.isEmpty() && nextExpected != null) {

                val earliest = buffered.min()

                val gap = nextExpected - earliest

                val frame =

                    pipeline.peekBufferedFrame(

                        member.sourceIdentity,

                        source.incarnationId,

                        earliest,

                    )

                when {

                    gap > com.talkback.core.conference.runtime.MediaJitterConstants.MAX_REORDER_PACKETS ->

                        gapExceedsReorder = true

                    frame == null || tickMediaTimeMs > frame.usefulDeadlineMs() ->

                        prefixUnplayable = true

                    else -> bufferBeforeCursor = true

                }

            }

        }

        return when {

            gapExceedsReorder ->

                PlayoutMixResolveSkipReason.TOP_K_BUFFER_BEFORE_CURSOR_GAP_EXCEEDS_REORDER

            prefixUnplayable ->

                PlayoutMixResolveSkipReason.TOP_K_BUFFER_BEFORE_CURSOR_UNPLAYABLE

            bufferBeforeCursor -> PlayoutMixResolveSkipReason.TOP_K_BUFFER_BEFORE_CURSOR

            bufferEmpty -> PlayoutMixResolveSkipReason.TOP_K_BUFFER_EMPTY

            layerAMissing -> PlayoutMixResolveSkipReason.TOP_K_LAYER_A_MISSING

            else -> PlayoutMixResolveSkipReason.TOP_K_NO_PULLABLE_SLOT

        }

    }



    fun recordMixOutcome(

        progress: PlayoutSlotProgress,

        slot: Long,

        mixProducedPcm: Boolean,

    ) {

        progress.lastAttemptedMixSlot = slot

        if (mixProducedPcm) {

            progress.lastCommittedMixSlot = slot

            progress.consecutiveZeroMixCycles = 0

        } else {

            progress.consecutiveZeroMixCycles++

        }

    }



    private fun pickPerSourcePlayoutSlots(

        pipeline: MediaExecutionPipeline,

        admitted: Map<String, AdmittedMediaSource>,

        ingressTimeline: PerIncarnationIngressTimelineRegistry,

        tickMediaTimeMs: Long,

        sessionTargetMediaSlot: Long?,

        topKMembers: List<TopKMember>,

        nowMs: Long,

    ): ConferenceSessionMediaWiring.BufferedMixSlot? {

        val perSourceSlots = linkedMapOf<String, Long>()

        var representativeMediaTimeMs: Long? = null

        for (member in topKMembers) {

            val source = admitted[member.sourceIdentity] ?: continue

            val perSourceTarget =

                ingressTimeline.mediaSlotForPlayoutTickMs(

                    member.sourceIdentity,

                    source.incarnationId,

                    tickMediaTimeMs,

                ) ?: sessionTargetMediaSlot

            if (perSourceTarget == null) {

                continue

            }

            val slot =

                pickEligibleSlotForSource(

                    pipeline,

                    member.sourceIdentity,

                    source.incarnationId,

                    perSourceTarget,

                    nowMs,

                ) ?: continue

            perSourceSlots[member.sourceIdentity] = slot

            val frame =

                pipeline.peekBufferedFrame(member.sourceIdentity, source.incarnationId, slot)

                    ?: continue

            if (representativeMediaTimeMs == null || frame.mediaTimeMs < representativeMediaTimeMs) {

                representativeMediaTimeMs = frame.mediaTimeMs

            }

        }

        if (perSourceSlots.isEmpty()) {

            return null

        }

        val telemetrySlot = sessionTargetMediaSlot ?: perSourceSlots.values.minOrNull()!!

        return ConferenceSessionMediaWiring.BufferedMixSlot(

            slot = telemetrySlot,

            slotMediaTimeMs = representativeMediaTimeMs ?: tickMediaTimeMs,

            perSourceSlots = perSourceSlots,

        )

    }



    private fun pickPerSourceForwardRecoverySlots(

        pipeline: MediaExecutionPipeline,

        admitted: Map<String, AdmittedMediaSource>,

        ingressTimeline: PerIncarnationIngressTimelineRegistry,

        tickMediaTimeMs: Long,

        sessionTargetMediaSlot: Long?,

        topKMembers: List<TopKMember>,

        progress: PlayoutSlotProgress,

        nowMs: Long,

    ): ConferenceSessionMediaWiring.BufferedMixSlot? {

        val perSourceSlots = linkedMapOf<String, Long>()

        var representativeMediaTimeMs: Long? = null

        for (member in topKMembers) {

            val source = admitted[member.sourceIdentity] ?: continue

            val perSourceTarget =

                ingressTimeline.mediaSlotForPlayoutTickMs(

                    member.sourceIdentity,

                    source.incarnationId,

                    tickMediaTimeMs,

                ) ?: sessionTargetMediaSlot

            if (perSourceTarget == null) {

                continue

            }

            val nextExpected = pipeline.nextExpectedSlot(member.sourceIdentity, source.incarnationId)

            val start =

                when {

                    nextExpected != null -> nextExpected

                    progress.lastCommittedMixSlot != null -> progress.lastCommittedMixSlot!! + 1L

                    progress.lastAttemptedMixSlot != null -> progress.lastAttemptedMixSlot!! + 1L

                    else -> pipeline.bufferedSlots(member.sourceIdentity, source.incarnationId).minOrNull()

                } ?: continue

            val end = minOf(perSourceTarget, start + MAX_RECOVERY_SLOT_SCAN - 1L)

            if (start > end) {

                continue

            }

            var picked: Long? = null

            for (candidate in start..end) {

                val frame =

                    pipeline.peekBufferedFrame(member.sourceIdentity, source.incarnationId, candidate)

                if (frame != null && nowMs <= frame.usefulDeadlineMs()) {

                    picked = candidate

                    break

                }

            }

            if (picked == null) {

                continue

            }

            perSourceSlots[member.sourceIdentity] = picked

            val frame =

                pipeline.peekBufferedFrame(member.sourceIdentity, source.incarnationId, picked)

                    ?: continue

            if (representativeMediaTimeMs == null || frame.mediaTimeMs < representativeMediaTimeMs) {

                representativeMediaTimeMs = frame.mediaTimeMs

            }

        }

        if (perSourceSlots.isEmpty()) {

            return null

        }

        val telemetrySlot = sessionTargetMediaSlot ?: perSourceSlots.values.minOrNull()!!

        return ConferenceSessionMediaWiring.BufferedMixSlot(

            slot = telemetrySlot,

            slotMediaTimeMs = representativeMediaTimeMs ?: tickMediaTimeMs,

            perSourceSlots = perSourceSlots,

        )

    }



    private fun pickEligibleSlotForSource(

        pipeline: MediaExecutionPipeline,

        sourceIdentity: String,

        incarnationId: Long,

        targetMediaSlot: Long,

        nowMs: Long,

    ): Long? {

        val nextExpected = pipeline.nextExpectedSlot(sourceIdentity, incarnationId)

        val buffered = pipeline.bufferedSlots(sourceIdentity, incarnationId)

        val eligible = buffered.filter { slot -> nextExpected == null || slot >= nextExpected }

        if (eligible.isEmpty()) {

            return null

        }

        fun playable(slot: Long): Boolean {

            val frame = pipeline.peekBufferedFrame(sourceIdentity, incarnationId, slot)

            return frame != null && nowMs <= frame.usefulDeadlineMs()

        }

        val playableEligible = eligible.filter(::playable)

        if (playableEligible.isEmpty()) {

            return null

        }

        // Layer A wall projection can lag behind the jitter playout cursor (nextExpected).

        // Do not fail resolve when buffered frames exist at/after the cursor — that stalls pulls,

        // freezes nextExpected, and grows bySlot (M02 16:10 field pattern).

        val playheadCap =

            if (nextExpected != null) {

                maxOf(targetMediaSlot, nextExpected)

            } else {

                targetMediaSlot

            }

        val atOrBeforeCap = playableEligible.filter { it <= playheadCap }

        if (atOrBeforeCap.isNotEmpty()) {

            if (nextExpected != null && nextExpected in atOrBeforeCap) {

                return nextExpected

            }

            return atOrBeforeCap.minOrNull()

        }

        return playableEligible.minOrNull()

    }

    internal fun hasPullablePlayoutSlot(
        pipeline: MediaExecutionPipeline,
        sourceIdentity: String,
        incarnationId: Long,
        targetMediaSlot: Long,
        nowMs: Long,
    ): Boolean =
        pickEligibleSlotForSource(
            pipeline,
            sourceIdentity,
            incarnationId,
            targetMediaSlot,
            nowMs,
        ) != null

}



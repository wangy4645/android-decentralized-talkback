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

    ): ConferenceSessionMediaWiring.BufferedMixSlot? {

        orchestrator.selectTopK(selectionNowMs)

        val topKMembers = orchestrator.selection.currentTopK().members

        if (topKMembers.isEmpty()) {

            return null

        }

        val pipeline = orchestrator.pipeline

        val baseline =

            pickPerSourcePlayoutSlots(

                pipeline = pipeline,

                admitted = admitted,

                ingressTimeline = ingressTimeline,

                tickMediaTimeMs = tickMediaTimeMs,

                sessionTargetMediaSlot = targetMediaSlot,

                topKMembers = topKMembers,

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

                ) ?: baseline

            } else {

                baseline

            }



        return chosen

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

                if (pipeline.peekBufferedFrame(member.sourceIdentity, source.incarnationId, candidate) != null) {

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

    ): Long? {

        val nextExpected = pipeline.nextExpectedSlot(sourceIdentity, incarnationId)

        val buffered = pipeline.bufferedSlots(sourceIdentity, incarnationId)

        val eligible = buffered.filter { slot -> nextExpected == null || slot >= nextExpected }

        if (eligible.isEmpty()) {

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

        val atOrBeforeCap = eligible.filter { it <= playheadCap }

        if (atOrBeforeCap.isNotEmpty()) {

            if (nextExpected != null && nextExpected in atOrBeforeCap) {

                return nextExpected

            }

            return atOrBeforeCap.minOrNull()

        }

        return eligible.minOrNull()

    }

}



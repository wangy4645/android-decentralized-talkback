package com.talkback.core.conference.session

import com.talkback.core.conference.runtime.AdmittedMediaSource
import com.talkback.core.conference.runtime.ConferenceMediaSelectionRuntime
import com.talkback.core.conference.runtime.MediaExecutionPipeline
import com.talkback.core.conference.runtime.MediaRuntimeConstants
import com.talkback.core.conference.runtime.TopKMember
import com.talkback.core.conference.session.integration.BrightScreenMeetingMixPolicy
import com.talkback.core.conference.transport.PerIncarnationIngressTimelineRegistry

/**
 * When ranked Top-K cannot pull any playable frame, substitute voice-active (V=1) sources
 * that are admitted, executable, and have a playable jitter slot — without bypassing VAD.
 */
internal object BrightScreenPlayoutTopKFallback {
    fun effectiveTopKMembers(
        selection: ConferenceMediaSelectionRuntime,
        admitted: Map<String, AdmittedMediaSource>,
        pipeline: MediaExecutionPipeline,
        ingressTimeline: PerIncarnationIngressTimelineRegistry,
        tickMediaTimeMs: Long,
        targetMediaSlot: Long?,
        selectionNowMs: Long,
        currentTopK: List<TopKMember>,
    ): List<TopKMember> {
        if (!BrightScreenMeetingMixPolicy.playableTopKFallbackEnabled) {
            return currentTopK
        }
        val currentPullable =
            currentTopK.any { member ->
                hasPullableSlot(
                    member,
                    admitted,
                    pipeline,
                    ingressTimeline,
                    tickMediaTimeMs,
                    targetMediaSlot,
                    selectionNowMs,
                )
            }
        if (currentPullable) {
            return currentTopK
        }
        val fallback = voiceActivePlayableMembers(
            selection,
            admitted,
            pipeline,
            ingressTimeline,
            tickMediaTimeMs,
            targetMediaSlot,
            selectionNowMs,
        )
        if (fallback.isEmpty()) {
            return currentTopK
        }
        return fallback
    }

    private fun voiceActivePlayableMembers(
        selection: ConferenceMediaSelectionRuntime,
        admitted: Map<String, AdmittedMediaSource>,
        pipeline: MediaExecutionPipeline,
        ingressTimeline: PerIncarnationIngressTimelineRegistry,
        tickMediaTimeMs: Long,
        targetMediaSlot: Long?,
        selectionNowMs: Long,
    ): List<TopKMember> {
        val voice = selection.voiceStore.snapshot()
        val localEx = selection.localModuleIdForTopKExclusion
        val candidates = mutableListOf<TopKMember>()
        for ((identity, inst) in selection.registry.installedSnapshot()) {
            if (localEx != null && identity == localEx) continue
            if (!inst.isExecutable) continue
            val obs = voice[identity] ?: continue
            if (obs.incarnationId != inst.source.incarnationId) continue
            if (!obs.voiceActive) continue
            val source = admitted[identity] ?: continue
            if (source.incarnationId != inst.source.incarnationId) continue
            val member =
                TopKMember(
                    sourceIdentity = identity,
                    incarnationId = source.incarnationId,
                    audioLevel = obs.audioLevel,
                )
            if (
                hasPullableSlot(
                    member,
                    admitted,
                    pipeline,
                    ingressTimeline,
                    tickMediaTimeMs,
                    targetMediaSlot,
                    selectionNowMs,
                )
            ) {
                candidates += member
            }
        }
        return candidates
            .sortedWith(
                compareByDescending<TopKMember> { it.audioLevel }
                    .thenBy { it.sourceIdentity },
            ).take(MediaRuntimeConstants.TOP_K)
    }

    private fun hasPullableSlot(
        member: TopKMember,
        admitted: Map<String, AdmittedMediaSource>,
        pipeline: MediaExecutionPipeline,
        ingressTimeline: PerIncarnationIngressTimelineRegistry,
        tickMediaTimeMs: Long,
        targetMediaSlot: Long?,
        selectionNowMs: Long,
    ): Boolean {
        val source = admitted[member.sourceIdentity] ?: return false
        if (source.incarnationId != member.incarnationId) return false
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
}

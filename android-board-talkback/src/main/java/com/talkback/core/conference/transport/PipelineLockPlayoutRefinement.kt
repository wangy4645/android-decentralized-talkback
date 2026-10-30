package com.talkback.core.conference.transport

import com.talkback.core.conference.runtime.ConferenceMediaExecutionOrchestrator
import com.talkback.core.conference.runtime.MixedBlock

/**
 * Phase 1 — Playout Lock Scope Refinement.
 *
 * ```text
 * inside pipelineLock:  jitter pull · Top-K · decode · mix · immutable PCM block
 * outside pipelineLock: AudioTrack.write · playout write metrics
 * ```
 *
 * Lock protects media state, not external sink I/O.
 */
object PipelineLockPlayoutRefinement {
    const val FIX_NAME = "PLAYOUT_LOCK_SCOPE_REFINEMENT_AUDIOTRACK_OUTSIDE"

    data class PlayoutTickProduct(
        val mixedBlock: MixedBlock,
        val decodeDurationUs: Long,
        val mixDurationUs: Long,
        val topKCount: Int,
        val liveDecoders: Int,
        val mixParticipantCount: Int,
    )

    /** Ownership transfer — PCM must not alias mutable decoder/mixer buffers after lock release. */
    fun sealedMixedBlock(block: MixedBlock): MixedBlock =
        MixedBlock(
            samples = block.samples.copyOf(),
            mixParticipantCount = block.mixParticipantCount,
            peakAbsFs = block.peakAbsFs,
        )

    fun writeAudioTrackOutsideLock(
        orchestrator: ConferenceMediaExecutionOrchestrator,
        block: MixedBlock,
        nowMs: Long,
    ): Boolean = orchestrator.playout(block, nowMs)
}

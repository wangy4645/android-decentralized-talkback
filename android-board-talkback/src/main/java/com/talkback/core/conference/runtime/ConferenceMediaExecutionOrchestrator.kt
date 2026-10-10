package com.talkback.core.conference.runtime

import com.talkback.core.conference.authority.AuthorityFactStore
import com.talkback.core.conference.authority.AuthorityWiringRuntime

/**
 * ADR-0058 E2b Slice 1 — unified Profile 03 production execution chain.
 *
 * Single [ConferenceMediaSelectionRuntime] shared by authority, jitter pipeline,
 * decode/mix, and eligibility gate. Does not integrate GROUP PTT / wire ingress
 * product path (Slice 1 scope).
 */
data class MixCycleResult(
    val topKIdentities: Set<String>,
    val decodeInvocationIdentities: Set<String>,
    val mixParticipantIdentities: Set<String>,
    val mixedBlock: MixedBlock,
    /** A3-only: per-source pull disposition when available (empty on GA pull path). */
    val sourcePullDispositions: Map<String, SlotPullDisposition> = emptyMap(),
    /** A3-only: PCM that actually entered EqualWeightMixer this cycle. */
    val sourceMixInputs: Map<String, SourceMixInputSnapshot> = emptyMap(),
)

class ConferenceMediaExecutionOrchestrator(
    val selection: ConferenceMediaSelectionRuntime = ConferenceMediaSelectionRuntime(),
    val store: AuthorityFactStore = AuthorityFactStore(),
    val pipeline: MediaExecutionPipeline = MediaExecutionPipeline(selection),
    val decodeMix: DecodeMixRuntime = DecodeMixRuntime(selection),
    private val playoutSeam: AudioTrackPlayoutSeam = RecordingAudioTrackSeam(),
) {
    val authority: AuthorityWiringRuntime =
        AuthorityWiringRuntime(
            store = store,
            selection = selection,
            pipeline = pipeline,
        )

    fun setDecodeSeam(seam: OpusDecodeSeam) {
        decodeMix.setDecodeSeam(seam)
    }

    fun install(source: AdmittedMediaSource) {
        selection.install(source)
    }

    fun observeVoice(observation: VoiceLevelObservation): Boolean =
        selection.observeVoice(observation)

    fun selectTopK(nowMs: Long) = pipeline.selectTopK(nowMs)

    fun admitFrame(frame: AdmittedMediaFrame, nowMs: Long): FrameAdmitDisposition =
        pipeline.admitFrame(frame, nowMs)

    /**
     * Production path for one media slot:
     * shared Top-K → [MediaExecutionPipeline] jitter pull → [DecodeMixRuntime] decode
     * → [EqualWeightMixer] → optional playout seam.
     *
     * Participating sources must have [AdmittedMediaFrame] for [slot] admitted beforehand.
     */
    fun executeTopKSlotMixCycle(
        nowMs: Long,
        slot: Long,
        slotMediaTimeMs: Long,
    ): MixCycleResult {
        pipeline.selectTopK(nowMs)
        val topKMembers = selection.currentTopK().members
        val topKIds = topKMembers.map { it.sourceIdentity }.toSet()

        for (member in topKMembers) {
            pipeline.pullSlot(
                sourceIdentity = member.sourceIdentity,
                incarnationId = member.incarnationId,
                slot = slot,
                slotMediaTimeMs = slotMediaTimeMs,
                nowMs = nowMs,
            )
        }

        val decodeIds = linkedSetOf<String>()
        val mixIds = linkedSetOf<String>()
        val pcmFrames = mutableListOf<PcmFrame>()
        val sourceMixInputs = linkedMapOf<String, SourceMixInputSnapshot>()
        val sourcePullDispositions = linkedMapOf<String, SlotPullDisposition>()

        for (member in topKMembers) {
            if (!selection.isDecodeEligible(member.sourceIdentity, member.incarnationId)) {
                continue
            }
            val pcm =
                decodeMix.produceMixablePcm(
                    sourceIdentity = member.sourceIdentity,
                    incarnationId = member.incarnationId,
                    mediaSlot = slot,
                    nowMs = nowMs,
                )
            if (pcm != null) {
                decodeIds += member.sourceIdentity
                mixIds += member.sourceIdentity
                pcmFrames += pcm
                // Clean GA path has no SharedMix pull kinds; treat mixer-admitted PCM as REAL.
                sourcePullDispositions[member.sourceIdentity] = SlotPullDisposition.DECODE_FRAME
                sourceMixInputs[member.sourceIdentity] =
                    SourceMixInputSnapshot(
                        kind = SourceMixInputKind.REAL,
                        samples = pcm.samples,
                    )
            }
        }

        val block = EqualWeightMixer.mix(pcmFrames)
        return MixCycleResult(
            topKIdentities = topKIds,
            decodeInvocationIdentities = decodeIds,
            mixParticipantIdentities = mixIds,
            mixedBlock = block,
            sourcePullDispositions = sourcePullDispositions,
            sourceMixInputs = sourceMixInputs,
        )
    }

    /** Harness-only negative probe: excluded sources must not decode on production chain. */
    fun attemptDecode(
        sourceIdentity: String,
        incarnationId: Long,
        slot: Long,
        nowMs: Long,
    ): PcmFrame? {
        if (!selection.isDecodeEligible(sourceIdentity, incarnationId)) return null
        return decodeMix.produceMixablePcm(sourceIdentity, incarnationId, slot, nowMs)
    }

    fun playout(block: MixedBlock, nowMs: Long): Boolean = playoutSeam.write(block, nowMs)
}

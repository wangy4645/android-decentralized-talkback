package com.talkback.core.conference.runtime

/**
 * E2b-04 decode / warm-up / mix facade (no AudioTrack).
 *
 * eligible → allocate decoder(≤5) → decode → warm-up → mix(≤4) → peak protect.
 */
class DecodeMixRuntime(
    val selection: ConferenceMediaSelectionRuntime = ConferenceMediaSelectionRuntime(),
    val decoderPool: LiveDecoderPool = LiveDecoderPool(),
    private var decodeSeam: OpusDecodeSeam = OpusDecodeSeam { _, _, _, _ -> null },
) {
    var warmupExpiryCount: Int = 0
        private set
    var lastMixedBlock: MixedBlock? = null
        private set

    fun install(source: AdmittedMediaSource) = selection.install(source)

    fun hardFence(sourceIdentity: String, incarnationId: Long): Boolean {
        val ok = selection.hardFence(sourceIdentity, incarnationId)
        if (ok) decoderPool.hardFenceRelease(sourceIdentity, incarnationId)
        return ok
    }

    fun observeVoice(o: VoiceLevelObservation) = selection.observeVoice(o)

    fun selectTopK(nowMs: Long) = selection.selectTopK(nowMs)

    fun setDecodeSeam(seam: OpusDecodeSeam) {
        decodeSeam = seam
    }

    /**
     * Attempt decode+warm-up for one Source. Does not bypass eligibility/fence.
     * @return mix-ready PCM or null if not yet / expired transition / ineligible
     */
    fun produceMixablePcm(
        sourceIdentity: String,
        incarnationId: Long,
        mediaSlot: Long,
        nowMs: Long,
        allocateAsTransition: Boolean = false,
    ): PcmFrame? {
        if (!selection.isDecodeEligible(sourceIdentity, incarnationId)) return null
        val inst = selection.registry.get(sourceIdentity) ?: return null
        if (inst.fence == ExecutionFenceState.HARD_FENCED) return null

        var slot = decoderPool.get(sourceIdentity)
        if (slot == null || slot.incarnationId != incarnationId) {
            slot =
                decoderPool.allocate(
                    sourceIdentity = sourceIdentity,
                    incarnationId = incarnationId,
                    nowMs = nowMs,
                    forTransitionOnly = allocateAsTransition,
                ) ?: return null
        }

        val pcm =
            decodeSeam.decode(sourceIdentity, incarnationId, mediaSlot, nowMs)
                ?: return handleWarmup(null, slot, sourceIdentity, nowMs)

        return when (WarmupGate.evaluate(pcm, slot, nowMs)) {
            WarmupGate.Decision.MIX_ELIGIBLE_NOW -> {
                if (slot.role == DecoderRole.TRANSITION) {
                    decoderPool.promoteToActiveMix(sourceIdentity)
                }
                pcm
            }
            WarmupGate.Decision.WARMING -> null
            WarmupGate.Decision.TRANSITION_EXPIRED -> {
                handleWarmupExpiry(sourceIdentity, slot)
                null
            }
        }
    }

    private fun handleWarmup(
        pcm: PcmFrame?,
        slot: LiveDecoderSlot,
        sourceIdentity: String,
        nowMs: Long,
    ): PcmFrame? =
        when (WarmupGate.evaluate(pcm, slot, nowMs)) {
            WarmupGate.Decision.MIX_ELIGIBLE_NOW -> pcm
            WarmupGate.Decision.WARMING -> null
            WarmupGate.Decision.TRANSITION_EXPIRED -> {
                handleWarmupExpiry(sourceIdentity, slot)
                null
            }
        }

    private fun handleWarmupExpiry(sourceIdentity: String, slot: LiveDecoderSlot) {
        // C-E2B04-04: release transition resources only.
        if (slot.role == DecoderRole.TRANSITION) {
            decoderPool.releaseTransition(sourceIdentity)
        } else {
            // ACTIVE_MIX that somehow never became usable: release decoder seat only.
            decoderPool.release(sourceIdentity)
        }
        warmupExpiryCount += 1
        // MUST NOT fence / revoke / change Top-K.
    }

    /**
     * Mix up to MixMaxSources from [candidates] that produce usable PCM.
     */
    fun mixEligibleSources(
        candidates: List<Triple<String, Long, Long>>,
        nowMs: Long,
    ): MixedBlock {
        val frames = mutableListOf<PcmFrame>()
        for ((id, incarnation, slot) in candidates) {
            if (frames.size >= MediaMixConstants.MIX_MAX_SOURCES) break
            val pcm = produceMixablePcm(id, incarnation, slot, nowMs) ?: continue
            frames += pcm
        }
        val block = EqualWeightMixer.mix(frames)
        lastMixedBlock = block
        return block
    }
}

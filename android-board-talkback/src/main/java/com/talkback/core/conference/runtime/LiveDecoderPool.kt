package com.talkback.core.conference.runtime

/**
 * Live decoder resource pool (Profile 03 Q7).
 *
 * C-E2B04-02: MaxLiveDecoders=5; #5 is transition-only — never Top-K #5 / mix #5.
 */
class LiveDecoderPool {
    private val slots = linkedMapOf<String, LiveDecoderSlot>()

    fun liveCount(): Int = slots.size

    fun snapshot(): List<LiveDecoderSlot> = slots.values.toList()

    fun get(sourceIdentity: String): LiveDecoderSlot? = slots[sourceIdentity]

    fun activeMixCount(): Int = slots.values.count { it.role == DecoderRole.ACTIVE_MIX }

    fun transitionCount(): Int = slots.values.count { it.role == DecoderRole.TRANSITION }

    /**
     * @return allocated slot, or null if cap would be exceeded without a legal transition seat.
     */
    fun allocate(
        sourceIdentity: String,
        incarnationId: Long,
        nowMs: Long,
        forTransitionOnly: Boolean,
    ): LiveDecoderSlot? {
        slots[sourceIdentity]?.let { existing ->
            if (existing.incarnationId == incarnationId) return existing
            // Superseding incarnation replaces in place (same identity).
        }
        if (slots.size >= MediaMixConstants.MAX_LIVE_DECODERS && sourceIdentity !in slots) {
            return null
        }
        // When already at 4 ACTIVE_MIX, a new allocation must be TRANSITION-only.
        val activeMix = activeMixCount()
        val role =
            when {
                forTransitionOnly -> DecoderRole.TRANSITION
                activeMix >= MediaMixConstants.MIX_MAX_SOURCES -> DecoderRole.TRANSITION
                else -> DecoderRole.ACTIVE_MIX
            }
        if (role == DecoderRole.ACTIVE_MIX && activeMix >= MediaMixConstants.MIX_MAX_SOURCES) {
            return null
        }
        if (slots.size >= MediaMixConstants.MAX_LIVE_DECODERS && sourceIdentity !in slots) {
            return null
        }
        val slot =
            LiveDecoderSlot(
                sourceIdentity = sourceIdentity,
                incarnationId = incarnationId,
                role = role,
                allocatedAtMs = nowMs,
                warmupDeadlineMs = nowMs + MediaMixConstants.DECODER_WARMUP_MS,
            )
        slots[sourceIdentity] = slot
        return slot
    }

    fun promoteToActiveMix(sourceIdentity: String): Boolean {
        val cur = slots[sourceIdentity] ?: return false
        if (cur.role == DecoderRole.ACTIVE_MIX) return true
        if (activeMixCount() >= MediaMixConstants.MIX_MAX_SOURCES) return false
        slots[sourceIdentity] = cur.copy(role = DecoderRole.ACTIVE_MIX)
        return true
    }

    /** C-E2B04-04: stop transition resource use only — no fence/authority/Top-K side effects. */
    fun releaseTransition(sourceIdentity: String): Boolean {
        val cur = slots[sourceIdentity] ?: return false
        if (cur.role != DecoderRole.TRANSITION) return false
        slots.remove(sourceIdentity)
        return true
    }

    fun release(sourceIdentity: String) {
        slots.remove(sourceIdentity)
    }

    fun hardFenceRelease(sourceIdentity: String, incarnationId: Long) {
        val cur = slots[sourceIdentity] ?: return
        if (cur.incarnationId == incarnationId) {
            slots.remove(sourceIdentity)
        }
    }
}

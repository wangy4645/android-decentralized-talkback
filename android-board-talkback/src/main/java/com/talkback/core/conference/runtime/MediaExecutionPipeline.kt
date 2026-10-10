package com.talkback.core.conference.runtime

/**
 * E2b-03 execution pipeline (no Opus/mix/AudioTrack):
 * post-P02 frame → installed incarnation → jitter/reorder → deadline
 * → decode-eligibility → decode-count OR lost-slot PLC.
 *
 * Jitter source capacity is enforced only via [JitterSourceAllocator] (E2b Slice 2).
 */
class MediaExecutionPipeline(
    val selection: ConferenceMediaSelectionRuntime = ConferenceMediaSelectionRuntime(),
    val jitterAllocator: JitterSourceAllocator = JitterSourceAllocator(selection.registry),
) {
    private val jitters = linkedMapOf<String, PerIncarnationJitterBuffer>()
    private val plcByIdentity = linkedMapOf<String, PlcController>()

    var decodeCount: Int = 0
        private set
    var plcCount: Int = 0
        private set
    var silenceGapCount: Int = 0
        private set
    var lateForPlayoutCount: Int = 0
        private set
    val decodeOrderSlots: MutableList<Long> = mutableListOf()

    /** Last allocator outcome from [admitFrame] jitter acquisition (harness observability). */
    var lastJitterAllocResult: JitterAllocResult? = null
        private set

    val promotionLiveEdgeFenceMetrics = PromotionLiveEdgeFenceMetrics()

    private var previousTopKIds: Set<String> = emptySet()

    fun activeJitterSourceCount(): Int = jitterAllocator.activeCount()

    fun install(source: AdmittedMediaSource) {
        selection.install(source)
    }

    fun hardFence(sourceIdentity: String, incarnationId: Long): Boolean {
        val ok = selection.hardFence(sourceIdentity, incarnationId)
        if (ok) {
            jitters["$sourceIdentity#$incarnationId"]?.invalidateForHardFence()
            // Also invalidate any key alias by scanning
            jitters.values
                .filter { it.sourceIdentity == sourceIdentity && it.incarnationId == incarnationId }
                .forEach { it.invalidateForHardFence() }
        }
        return ok
    }

    fun observeVoice(observation: VoiceLevelObservation): Boolean =
        selection.observeVoice(observation)

    fun selectTopK(nowMs: Long): TopKSelector.SelectionState {
        val previousIds = previousTopKIds
        val state = selection.selectTopK(nowMs)
        val newIds = state.members.map { it.sourceIdentity }.toSet()
        for (id in newIds - previousIds) {
            val member = state.members.find { it.sourceIdentity == id } ?: continue
            val event = applyPromotionLiveEdgeFence(id, member.incarnationId, nowMs)
            promotionLiveEdgeFenceMetrics.record(event)
        }
        previousTopKIds = newIds
        return state
    }

    /**
     * On Top-K promotion: discard jitter frames already outside the 120ms playout window.
     */
    fun applyPromotionLiveEdgeFence(
        sourceIdentity: String,
        incarnationId: Long,
        currentPlayoutTimeMs: Long,
    ): PromotionFenceEvent {
        val buf = jitters[jitterKey(sourceIdentity, incarnationId)]
        if (buf == null || !buf.isExecutable()) {
            return PromotionFenceEvent(
                sourceIdentity = sourceIdentity,
                staleFramesDiscarded = 0,
                oldestRetainedAgeMs = null,
                fencedAtMs = currentPlayoutTimeMs,
            )
        }
        val result = buf.promotionDiscardLateForPlayout(currentPlayoutTimeMs)
        repeat(result.staleDiscarded) {
            lateForPlayoutCount += 1
        }
        return PromotionFenceEvent(
            sourceIdentity = sourceIdentity,
            staleFramesDiscarded = result.staleDiscarded,
            oldestRetainedAgeMs = result.oldestRetainedAgeMs,
            fencedAtMs = currentPlayoutTimeMs,
        )
    }

    private fun jitterKey(identity: String, incarnationId: Long) = "$identity#$incarnationId"

    fun admitFrame(frame: AdmittedMediaFrame, nowMs: Long): FrameAdmitDisposition {
        val inst = selection.registry.get(frame.sourceIdentity)
        if (inst == null || inst.source.incarnationId != frame.incarnationId) {
            return FrameAdmitDisposition.NOT_ADMITTED_INCARNATION
        }
        if (inst.fence == ExecutionFenceState.HARD_FENCED) {
            return FrameAdmitDisposition.FENCED_NON_EXECUTABLE
        }
        val key = jitterKey(frame.sourceIdentity, frame.incarnationId)
        var buf = jitters[key]
        if (buf == null) {
            val allocResult = jitterAllocator.allocate(frame.sourceIdentity, frame.incarnationId)
            lastJitterAllocResult = allocResult
            when (allocResult.outcome) {
                JitterAllocOutcome.NOT_ADMITTED ->
                    return FrameAdmitDisposition.NOT_ADMITTED_INCARNATION
                JitterAllocOutcome.REJECT_NEW ->
                    return FrameAdmitDisposition.JITTER_CAP_REJECTED
                JitterAllocOutcome.RECLAIM_EXISTING_THEN_ALLOCATE -> {
                    releaseJitterBuffersForIdentity(allocResult.reclaimedIdentity!!)
                    buf = PerIncarnationJitterBuffer(frame.sourceIdentity, frame.incarnationId)
                    jitters[key] = buf
                }
                JitterAllocOutcome.ALLOCATED -> {
                    buf = PerIncarnationJitterBuffer(frame.sourceIdentity, frame.incarnationId)
                    jitters[key] = buf
                }
            }
        }
        if (!buf.isExecutable()) {
            return FrameAdmitDisposition.FENCED_NON_EXECUTABLE
        }
        val disposition = buf.admit(frame, nowMs)
        if (disposition == FrameAdmitDisposition.LATE_FOR_PLAYOUT) {
            lateForPlayoutCount += 1
        }
        return disposition
    }

    /**
     * Advance one media slot for [sourceIdentity] at [slot] / [slotMediaTimeMs].
     * Caller owns the media-time clock (fixture).
     */
    fun pullSlot(
        sourceIdentity: String,
        incarnationId: Long,
        slot: Long,
        slotMediaTimeMs: Long,
        nowMs: Long,
    ): SlotPullDisposition {
        val key = jitterKey(sourceIdentity, incarnationId)
        val buf = jitters[key]
        if (buf != null && !buf.isExecutable()) {
            return SlotPullDisposition.FENCED_SKIP
        }
        val inst = selection.registry.get(sourceIdentity)
        if (inst == null ||
            inst.source.incarnationId != incarnationId ||
            inst.fence == ExecutionFenceState.HARD_FENCED
        ) {
            return SlotPullDisposition.FENCED_SKIP
        }

        val decodeEligible =
            selection.isDecodeEligible(sourceIdentity, incarnationId)

        val frame = buf?.peekFrame(slot)
        if (frame != null) {
            if (nowMs > frame.usefulDeadlineMs()) {
                // Still sitting past deadline — treat as late drop, not decode.
                buf.takeFrame(slot)
                lateForPlayoutCount += 1
                buf.advanceExpectedTo(slot + 1)
                return SlotPullDisposition.EMPTY
            }
            buf.takeFrame(slot)
            buf.advanceExpectedTo(slot + 1)
            if (!decodeEligible) {
                return SlotPullDisposition.EMPTY
            }
            plcFor(sourceIdentity).onDecodedRealFrame()
            decodeCount += 1
            decodeOrderSlots += slot
            return SlotPullDisposition.DECODE_FRAME
        }

        // No frame: only declare lost after useful deadline for this slot.
        if (nowMs <= slotMediaTimeMs + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS) {
            return SlotPullDisposition.EMPTY
        }
        // B5 — slotMediaTimeMs must share wall clock with nowMs. Reject fantasy advances
        // from slot-index*20ms (pulls cursor ahead of real RTP → LATE deadlock).
        if (nowMs - slotMediaTimeMs > MediaJitterConstants.WALL_MEDIA_TIME_SKEW_LIMIT_MS) {
            return SlotPullDisposition.EMPTY
        }

        buf?.advanceExpectedTo(slot + 1)
        val plc = plcFor(sourceIdentity)
        return when (val d = plc.onDeadlineLostSlot(decodeEligible)) {
            SlotPullDisposition.PLC_SYNTHESIS -> {
                plcCount += 1
                d
            }
            SlotPullDisposition.SILENCE_GAP -> {
                silenceGapCount += 1
                d
            }
            else -> d
        }
    }

    /**
     * B8-A (ADR-0058 eligibility) — off-Top-K **non-mix release** (action A2) at [nextExpected]:
     * - past [AdmittedMediaFrame.usefulDeadlineMs], or
     * - [nextExpected] strictly above Layer-A [playheadSlotCap] while a frame is buffered.
     * Action A3: still-playable frames at/before cap are not consumed. No hole advance.
     */
    fun releaseOffTopKNonMixPrefixBounded(
        sourceIdentity: String,
        incarnationId: Long,
        nowMs: Long,
        playheadSlotCap: Long?,
        maxSlots: Int,
    ): Int {
        if (maxSlots <= 0) return 0
        val key = jitterKey(sourceIdentity, incarnationId)
        val buf = jitters[key] ?: return 0
        if (!buf.isExecutable()) return 0
        val inst = selection.registry.get(sourceIdentity)
        if (inst == null ||
            inst.source.incarnationId != incarnationId ||
            inst.fence == ExecutionFenceState.HARD_FENCED
        ) {
            return 0
        }
        var released = 0
        while (released < maxSlots) {
            val next = buf.nextExpected() ?: break
            val frame = buf.peekFrame(next) ?: break
            val pastDeadline = nowMs > frame.usefulDeadlineMs()
            val pastPlayheadCap = playheadSlotCap != null && next > playheadSlotCap
            if (!pastDeadline && !pastPlayheadCap) {
                break
            }
            pullSlot(
                sourceIdentity = sourceIdentity,
                incarnationId = incarnationId,
                slot = next,
                slotMediaTimeMs = frame.mediaTimeMs,
                nowMs = nowMs,
            )
            released++
        }
        return released
    }

    /** @see releaseOffTopKNonMixPrefixBounded */
    fun discardExpiredBufferedPrefixBounded(
        sourceIdentity: String,
        incarnationId: Long,
        nowMs: Long,
        playheadSlotCap: Long?,
        maxSlots: Int,
    ): Int =
        releaseOffTopKNonMixPrefixBounded(
            sourceIdentity,
            incarnationId,
            nowMs,
            playheadSlotCap,
            maxSlots,
        )

    /**
     * Drain consecutive buffered slots in media-time order while present and eligible.
     */
    fun drainReadyInOrder(
        sourceIdentity: String,
        incarnationId: Long,
        nowMs: Long,
    ): List<SlotPullDisposition> {
        val buf = jitters[jitterKey(sourceIdentity, incarnationId)] ?: return emptyList()
        if (!buf.isExecutable()) return listOf(SlotPullDisposition.FENCED_SKIP)
        val out = mutableListOf<SlotPullDisposition>()
        while (true) {
            val expected = buf.nextExpected() ?: break
            if (buf.peekFrame(expected) == null) break
            val frame = buf.peekFrame(expected)!!
            out +=
                pullSlot(
                    sourceIdentity = sourceIdentity,
                    incarnationId = incarnationId,
                    slot = expected,
                    slotMediaTimeMs = frame.mediaTimeMs,
                    nowMs = nowMs,
                )
        }
        return out
    }

    private fun plcFor(identity: String): PlcController =
        plcByIdentity.getOrPut(identity) { PlcController() }

    fun jitterSize(sourceIdentity: String, incarnationId: Long): Int =
        jitters[jitterKey(sourceIdentity, incarnationId)]?.size() ?: 0

    fun gapEmptyBetweenLatestBufferedAnd(
        sourceIdentity: String,
        incarnationId: Long,
        beforeSlot: Long,
    ): Boolean =
        jitters[jitterKey(sourceIdentity, incarnationId)]
            ?.gapEmptyBetweenLatestBufferedAnd(beforeSlot)
            ?: true

    fun allBufferedPastUsefulDeadline(
        sourceIdentity: String,
        incarnationId: Long,
        nowMs: Long,
    ): Boolean {
        val buf = jitters[jitterKey(sourceIdentity, incarnationId)] ?: return true
        if (buf.size() == 0) return true
        return buf.allBufferedPastUsefulDeadline(nowMs)
    }

    fun hasBufferedFrame(
        sourceIdentity: String,
        incarnationId: Long,
        slot: Long,
    ): Boolean = jitters[jitterKey(sourceIdentity, incarnationId)]?.peekFrame(slot) != null

    fun peekBufferedFrame(
        sourceIdentity: String,
        incarnationId: Long,
        slot: Long,
    ): AdmittedMediaFrame? = jitters[jitterKey(sourceIdentity, incarnationId)]?.peekFrame(slot)

    fun bufferedSlots(
        sourceIdentity: String,
        incarnationId: Long,
    ): List<Long> = jitters[jitterKey(sourceIdentity, incarnationId)]?.bufferedSlots() ?: emptyList()

    fun isJitterExecutable(sourceIdentity: String, incarnationId: Long): Boolean =
        jitters[jitterKey(sourceIdentity, incarnationId)]?.isExecutable() ?: true

    fun nextExpectedSlot(sourceIdentity: String, incarnationId: Long): Long? =
        jitters[jitterKey(sourceIdentity, incarnationId)]?.nextExpected()

    fun jitterSlotDomainSnapshot(
        sourceIdentity: String,
        incarnationId: Long,
    ): com.talkback.core.conference.session.JitterSlotDomainSnapshot? {
        val buf = jitters[jitterKey(sourceIdentity, incarnationId)] ?: return null
        return com.talkback.core.conference.session.JitterSlotDomainSnapshot(
            sourceIdentity = sourceIdentity,
            incarnationId = incarnationId,
            nextExpectedSlot = buf.nextExpected(),
            bySlotSize = buf.size(),
            earliestBufferedSlot = buf.earliestBufferedSlot(),
            latestBufferedSlot = buf.latestBufferedSlot(),
            executable = buf.isExecutable(),
        )
    }

    /**
     * Soak/harness only: discard stale buffered slots and jump expected to [liveSlot].
     * Does **not** alter [admitFrame] / Profile 03 reorder contract.
     * @return packets discarded from the jitter buffer
     */
    fun soakResyncJitterToLiveEdge(
        sourceIdentity: String,
        incarnationId: Long,
        liveSlot: Long,
    ): Int {
        val buf = jitters[jitterKey(sourceIdentity, incarnationId)] ?: return 0
        return buf.soakDiscardBelowAndResync(liveSlot)
    }

    /** Advance nextExpected without discarding buffered frames. */
    fun slideJitterExpectedTo(
        sourceIdentity: String,
        incarnationId: Long,
        slot: Long,
    ): Boolean {
        val buf = jitters[jitterKey(sourceIdentity, incarnationId)] ?: return false
        buf.slideExpectedTo(slot)
        return true
    }

    fun latestBufferedSlot(
        sourceIdentity: String,
        incarnationId: Long,
    ): Long? = jitters[jitterKey(sourceIdentity, incarnationId)]?.latestBufferedSlot()

    /** Clears a stale expected slot when jitter is empty (conference unmute RX recovery). */
    fun recoverEmptyJitterLiveEdge(
        sourceIdentity: String,
        incarnationId: Long,
    ): Boolean {
        val buf = jitters[jitterKey(sourceIdentity, incarnationId)] ?: return false
        return buf.resetLiveEdgeWhenEmpty()
    }

    private fun releaseJitterBuffersForIdentity(sourceIdentity: String) {
        val removeKeys =
            jitters.entries
                .filter { it.value.sourceIdentity == sourceIdentity }
                .map { it.key }
        for (k in removeKeys) {
            jitters.remove(k)
        }
    }

    /**
     * Conference session wiring teardown after [AuthorityWiringRuntime.revokeSource].
     * Does not alter Profile 03 admit / promotion semantics.
     */
    fun drainSourceAfterSessionRevoke(sourceIdentity: String) {
        releaseJitterBuffersForIdentity(sourceIdentity)
        jitterAllocator.release(sourceIdentity)
        plcByIdentity.remove(sourceIdentity)
    }

    fun drainAllForSessionWiring() {
        val identities = jitters.values.map { it.sourceIdentity }.toSet()
        for (id in identities) {
            drainSourceAfterSessionRevoke(id)
        }
        jitters.clear()
        plcByIdentity.clear()
    }

    fun jitterBufferCount(): Int = jitters.size
}

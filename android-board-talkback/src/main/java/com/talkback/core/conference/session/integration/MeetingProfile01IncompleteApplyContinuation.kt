package com.talkback.core.conference.session.integration

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * P1-E bounded mailbox for verified CREATION apply obligations awaiting media supplement.
 *
 * Stores verified envelopes only — not pre-trust raw retention. One pending per
 * `(conferenceIdHex, mediaKeyEpoch, factDigestHex)`.
 */
class MeetingProfile01IncompleteApplyContinuation(
    private val clock: () -> Long = { System.currentTimeMillis() },
    val observability: MeetingProfile01IncompleteApplyObservability =
        MeetingProfile01IncompleteApplyObservability(),
) {
    data class PendingIncompleteCreationApply(
        val sessionId: String,
        val conferenceIdHex: String,
        val mediaKeyEpoch: Long,
        val factDigestHex: String,
        val signedFactBytes: ByteArray,
        val networkInterfaceName: String,
        val channelId: String,
        val retainedAtMs: Long,
    )

    data class DetachedPendingCreationApply(
        val pending: PendingIncompleteCreationApply,
    )

    private data class RetainedEntry(
        val pending: PendingIncompleteCreationApply,
    )

    private val byConferenceId = ConcurrentHashMap<String, ArrayDeque<RetainedEntry>>()
    private val dedupeKeys = ConcurrentHashMap.newKeySet<String>()

    fun retainPending(pending: PendingIncompleteCreationApply): RetainPendingOutcome {
        purgeExpired(clock())
        val dedupeKey = dedupeKey(pending.conferenceIdHex, pending.mediaKeyEpoch, pending.factDigestHex)
        if (dedupeKey in dedupeKeys) {
            observability.logDeduped(pending.conferenceIdHex, pending.factDigestHex)
            return RetainPendingOutcome.DEDUPED
        }
        if (globalCount() >= MAX_PENDING_GLOBAL) {
            observability.logBoundsReject(pending.conferenceIdHex, "GLOBAL_CAP")
            return RetainPendingOutcome.REJECTED_BOUNDS
        }
        val queue = byConferenceId.computeIfAbsent(pending.conferenceIdHex) { ArrayDeque() }
        synchronized(queue) {
            if (queue.size >= MAX_PENDING_PER_CONFERENCE) {
                observability.logBoundsReject(pending.conferenceIdHex, "PER_CONFERENCE_CAP")
                return RetainPendingOutcome.REJECTED_BOUNDS
            }
            queue.addLast(RetainedEntry(pending))
            dedupeKeys.add(dedupeKey)
        }
        observability.logRetained(pending)
        return RetainPendingOutcome.RETAINED
    }

    /** Consume pending entries whose mediaKeyEpoch now has a supplement in registry. */
    fun detachSatisfiable(
        conferenceIdHex: String,
        hasSupplementAtEpoch: (Long) -> Boolean,
        triggerMediaKeyEpoch: Long,
        nowMs: Long = clock(),
    ): List<DetachedPendingCreationApply> {
        purgeExpired(nowMs)
        val queue = byConferenceId[conferenceIdHex] ?: return emptyList()
        val detached = mutableListOf<DetachedPendingCreationApply>()
        synchronized(queue) {
            val remaining = ArrayDeque<RetainedEntry>()
            while (queue.isNotEmpty()) {
                val entry = queue.removeFirst()
                if (hasSupplementAtEpoch(entry.pending.mediaKeyEpoch)) {
                    dedupeKeys.remove(
                        dedupeKey(
                            entry.pending.conferenceIdHex,
                            entry.pending.mediaKeyEpoch,
                            entry.pending.factDigestHex,
                        ),
                    )
                    detached.add(DetachedPendingCreationApply(entry.pending))
                } else {
                    remaining.addLast(entry)
                }
            }
            if (remaining.isEmpty()) {
                byConferenceId.remove(conferenceIdHex)
            } else {
                byConferenceId[conferenceIdHex] = remaining
            }
        }
        if (detached.isNotEmpty()) {
            observability.logSupplementDrain(conferenceIdHex, triggerMediaKeyEpoch, detached.size)
        }
        return detached
    }

    /** @see detachSatisfiable */
    fun detachForSupplementReady(
        conferenceIdHex: String,
        mediaKeyEpoch: Long,
        nowMs: Long = clock(),
    ): List<DetachedPendingCreationApply> =
        detachSatisfiable(
            conferenceIdHex = conferenceIdHex,
            hasSupplementAtEpoch = { epoch -> epoch == mediaKeyEpoch },
            triggerMediaKeyEpoch = mediaKeyEpoch,
            nowMs = nowMs,
        )

    fun peekNewestPending(conferenceIdHex: String): PendingIncompleteCreationApply? {
        val queue = byConferenceId[conferenceIdHex] ?: return null
        synchronized(queue) {
            return queue.lastOrNull()?.pending
        }
    }

    fun discardBelowMediaKeyEpoch(
        conferenceIdHex: String,
        mediaKeyEpoch: Long,
        reason: IncompleteDiscardReason,
    ) {
        val removed = mutableListOf<PendingIncompleteCreationApply>()
        val queue = byConferenceId[conferenceIdHex] ?: return
        synchronized(queue) {
            val kept = ArrayDeque<RetainedEntry>()
            while (queue.isNotEmpty()) {
                val entry = queue.removeFirst()
                if (entry.pending.mediaKeyEpoch < mediaKeyEpoch) {
                    dedupeKeys.remove(
                        dedupeKey(
                            entry.pending.conferenceIdHex,
                            entry.pending.mediaKeyEpoch,
                            entry.pending.factDigestHex,
                        ),
                    )
                    removed += entry.pending
                } else {
                    kept.addLast(entry)
                }
            }
            if (kept.isEmpty()) {
                byConferenceId.remove(conferenceIdHex)
            } else {
                byConferenceId[conferenceIdHex] = kept
            }
        }
        removed.forEach { observability.logDiscarded(it, reason) }
    }

    fun discardForSession(
        sessionId: String,
        reason: IncompleteDiscardReason,
    ) {
        val removed = mutableListOf<PendingIncompleteCreationApply>()
        byConferenceId.entries.toList().forEach { (conferenceIdHex, queue) ->
            synchronized(queue) {
                val kept = ArrayDeque<RetainedEntry>()
                while (queue.isNotEmpty()) {
                    val entry = queue.removeFirst()
                    if (entry.pending.sessionId == sessionId) {
                        dedupeKeys.remove(
                            dedupeKey(
                                entry.pending.conferenceIdHex,
                                entry.pending.mediaKeyEpoch,
                                entry.pending.factDigestHex,
                            ),
                        )
                        removed += entry.pending
                    } else {
                        kept.addLast(entry)
                    }
                }
                if (kept.isEmpty()) {
                    byConferenceId.remove(conferenceIdHex)
                } else {
                    byConferenceId[conferenceIdHex] = kept
                }
            }
        }
        removed.forEach { observability.logDiscarded(it, reason) }
    }

    fun pendingCount(conferenceIdHex: String): Int =
        byConferenceId[conferenceIdHex]?.let { queue -> synchronized(queue) { queue.size } } ?: 0

    fun clear() {
        byConferenceId.clear()
        dedupeKeys.clear()
    }

    private fun purgeExpired(nowMs: Long) {
        byConferenceId.entries.toList().forEach { (conferenceIdHex, queue) ->
            synchronized(queue) {
                val kept = ArrayDeque<RetainedEntry>()
                while (queue.isNotEmpty()) {
                    val entry = queue.removeFirst()
                    if (nowMs - entry.pending.retainedAtMs <= TTL_MS) {
                        kept.addLast(entry)
                    } else {
                        dedupeKeys.remove(
                            dedupeKey(
                                entry.pending.conferenceIdHex,
                                entry.pending.mediaKeyEpoch,
                                entry.pending.factDigestHex,
                            ),
                        )
                        observability.logExpired(entry.pending)
                    }
                }
                if (kept.isEmpty()) {
                    byConferenceId.remove(conferenceIdHex)
                } else {
                    byConferenceId[conferenceIdHex] = kept
                }
            }
        }
    }

    private fun globalCount(): Int = byConferenceId.values.sumOf { queue -> synchronized(queue) { queue.size } }

    private fun dedupeKey(
        conferenceIdHex: String,
        mediaKeyEpoch: Long,
        factDigestHex: String,
    ): String = "$conferenceIdHex:$mediaKeyEpoch:$factDigestHex"

    companion object {
        const val MAX_PENDING_PER_CONFERENCE = 4
        const val MAX_PENDING_GLOBAL = 32
        const val TTL_MS = 120_000L
    }
}

enum class RetainPendingOutcome {
    RETAINED,
    DEDUPED,
    REJECTED_BOUNDS,
}

enum class IncompleteDiscardReason {
    SESSION_UNREGISTERED,
    WRONG_SESSION,
    WRONG_CONFERENCE,
    GENERATION_FENCE,
}

class MeetingProfile01IncompleteApplyObservability {
    fun logRetained(pending: MeetingProfile01IncompleteApplyContinuation.PendingIncompleteCreationApply) {
        log(
            "INCOMPLETE_RETAIN session=${pending.sessionId} conference=${pending.conferenceIdHex} " +
                "mediaKeyEpoch=${pending.mediaKeyEpoch} digest=${pending.factDigestHex}",
        )
    }

    fun logDeduped(
        conferenceIdHex: String,
        factDigestHex: String,
    ) {
        log("INCOMPLETE_DEDUPED conference=$conferenceIdHex digest=$factDigestHex")
    }

    fun logBoundsReject(
        conferenceIdHex: String,
        reason: String,
    ) {
        log("INCOMPLETE_BOUNDS_REJECT conference=$conferenceIdHex reason=$reason")
    }

    fun logSupplementDrain(
        conferenceIdHex: String,
        mediaKeyEpoch: Long,
        count: Int,
    ) {
        log("SESSION_INCOMPLETE_DRAIN conference=$conferenceIdHex mediaKeyEpoch=$mediaKeyEpoch count=$count")
    }

    fun logExpired(pending: MeetingProfile01IncompleteApplyContinuation.PendingIncompleteCreationApply) {
        log(
            "INCOMPLETE_EXPIRED session=${pending.sessionId} conference=${pending.conferenceIdHex} " +
                "mediaKeyEpoch=${pending.mediaKeyEpoch}",
        )
    }

    fun logDiscarded(
        pending: MeetingProfile01IncompleteApplyContinuation.PendingIncompleteCreationApply,
        reason: IncompleteDiscardReason,
    ) {
        log(
            "INCOMPLETE_DISCARD session=${pending.sessionId} conference=${pending.conferenceIdHex} " +
                "reason=$reason",
        )
    }

    private fun log(message: String) {
        try {
            android.util.Log.i(MeetingProductMediaShadow.LOG_TAG, message)
        } catch (_: Throwable) {
        }
    }
}

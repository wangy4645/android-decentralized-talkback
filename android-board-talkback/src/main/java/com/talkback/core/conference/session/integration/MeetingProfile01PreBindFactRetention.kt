package com.talkback.core.conference.session.integration

import com.talkback.core.model.SignalEnvelope
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded pre-bind mailbox for Profile01 wire facts awaiting Meeting session bridge bind.
 *
 * Retains CREATION + MEMBERSHIP (factType 1/2). Not authoritative registry storage.
 */
class MeetingProfile01PreBindFactRetention(
    private val clock: () -> Long = { System.currentTimeMillis() },
    val observability: MeetingProfile01PreBindRetentionObservability =
        MeetingProfile01PreBindRetentionObservability(),
) {
    data class DetachedRetainedFact(
        val conferenceIdHex: String,
        val factDigestHex: String,
        val signal: SignalEnvelope,
    )

    private data class RetainedEntry(
        val conferenceIdHex: String,
        val factDigestHex: String,
        val signal: SignalEnvelope,
        val retainedAtMs: Long,
    )

    private val byConferenceId = ConcurrentHashMap<String, ArrayDeque<RetainedEntry>>()
    private val dedupeKeys = ConcurrentHashMap.newKeySet<String>()

    fun retain(
        conferenceIdHex: String,
        factDigestHex: String,
        signal: SignalEnvelope,
    ): RetainOutcome {
        purgeExpired(clock())
        val dedupeKey = dedupeKey(conferenceIdHex, factDigestHex)
        if (dedupeKey in dedupeKeys) {
            observability.logDeduped(conferenceIdHex, factDigestHex)
            return RetainOutcome.DEDUPED
        }
        if (globalCount() >= MAX_RETAINED_GLOBAL) {
            observability.logBoundsReject(conferenceIdHex, "GLOBAL_CAP")
            return RetainOutcome.REJECTED_BOUNDS
        }
        val queue = byConferenceId.computeIfAbsent(conferenceIdHex) { ArrayDeque() }
        synchronized(queue) {
            if (queue.size >= MAX_RETAINED_PER_CONFERENCE) {
                observability.logBoundsReject(conferenceIdHex, "PER_CONFERENCE_CAP")
                return RetainOutcome.REJECTED_BOUNDS
            }
            queue.addLast(
                RetainedEntry(
                    conferenceIdHex = conferenceIdHex,
                    factDigestHex = factDigestHex,
                    signal = signal,
                    retainedAtMs = clock(),
                ),
            )
            dedupeKeys.add(dedupeKey)
        }
        return RetainOutcome.RETAINED
    }

    /** Detach (consume) eligible entries for conferenceId; does not apply facts. */
    fun detachForDrain(
        conferenceIdHex: String,
        nowMs: Long = clock(),
    ): List<DetachedRetainedFact> {
        purgeExpired(nowMs)
        val queue = byConferenceId[conferenceIdHex] ?: return emptyList()
        val detached = mutableListOf<DetachedRetainedFact>()
        synchronized(queue) {
            while (queue.isNotEmpty()) {
                val entry = queue.removeFirst()
                dedupeKeys.remove(dedupeKey(entry.conferenceIdHex, entry.factDigestHex))
                detached.add(
                    DetachedRetainedFact(
                        conferenceIdHex = entry.conferenceIdHex,
                        factDigestHex = entry.factDigestHex,
                        signal = entry.signal,
                    ),
                )
            }
            if (queue.isEmpty()) {
                byConferenceId.remove(conferenceIdHex)
            }
        }
        return detached
    }

    fun discardForConference(
        conferenceIdHex: String,
        reason: DiscardReason,
    ) {
        val queue = byConferenceId.remove(conferenceIdHex) ?: return
        synchronized(queue) {
            queue.forEach { entry ->
                dedupeKeys.remove(dedupeKey(entry.conferenceIdHex, entry.factDigestHex))
                observability.logSessionDiscard(
                    sessionId = entry.signal.sessionId,
                    conferenceIdHex = conferenceIdHex,
                    reason = reason,
                )
            }
        }
    }

    fun purgeExpired(nowMs: Long = clock()) {
        val expiryCutoff = nowMs - MAX_RETENTION_MS
        byConferenceId.entries.toList().forEach { (conferenceIdHex, queue) ->
            synchronized(queue) {
                val iterator = queue.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.retainedAtMs < expiryCutoff) {
                        iterator.remove()
                        dedupeKeys.remove(dedupeKey(entry.conferenceIdHex, entry.factDigestHex))
                        observability.logExpiredDiscard(entry.conferenceIdHex, entry.factDigestHex, nowMs - entry.retainedAtMs)
                    }
                }
                if (queue.isEmpty()) {
                    byConferenceId.remove(conferenceIdHex)
                }
            }
        }
    }

    private fun globalCount(): Int = byConferenceId.values.sumOf { queue -> synchronized(queue) { queue.size } }

    private fun dedupeKey(
        conferenceIdHex: String,
        factDigestHex: String,
    ): String = "$conferenceIdHex:$factDigestHex"

    companion object {
        const val MAX_RETAINED_PER_CONFERENCE = 8
        const val MAX_RETAINED_GLOBAL = 32
        const val MAX_RETENTION_MS = 120_000L
    }
}

enum class RetainOutcome {
    RETAINED,
    DEDUPED,
    REJECTED_BOUNDS,
}

enum class DiscardReason {
    SESSION_UNREGISTERED,
    WRONG_CONFERENCE,
    WRONG_SESSION,
    RETENTION_EXPIRED,
}

class MeetingProfile01PreBindRetentionObservability {
    fun logDeduped(
        conferenceIdHex: String,
        factDigestHex: String,
    ) {
        logInfo("RETENTION_DEDUPED conferenceId=$conferenceIdHex digest=$factDigestHex")
    }

    fun logBoundsReject(
        conferenceIdHex: String,
        reason: String,
    ) {
        logInfo("RETENTION_BOUNDS_REJECT conferenceId=$conferenceIdHex reason=$reason")
    }

    fun logExpiredDiscard(
        conferenceIdHex: String,
        factDigestHex: String,
        ageMs: Long,
    ) {
        logInfo("RETENTION_EXPIRED_DISCARD conferenceId=$conferenceIdHex digest=$factDigestHex ageMs=$ageMs")
    }

    fun logSessionDiscard(
        sessionId: String,
        conferenceIdHex: String,
        reason: DiscardReason,
    ) {
        logInfo("RETENTION_SESSION_DISCARD session=$sessionId conferenceId=$conferenceIdHex reason=$reason")
    }

    fun logBindDrain(
        sessionId: String,
        conferenceIdHex: String,
        count: Int,
    ) {
        logInfo("SESSION_BIND_DRAIN session=$sessionId conferenceId=$conferenceIdHex count=$count")
    }

    private fun logInfo(message: String) {
        try {
            android.util.Log.i(MeetingProductMediaShadow.LOG_TAG, message)
        } catch (_: Throwable) {
        }
    }
}

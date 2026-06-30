package com.talkback.core.util

import com.talkback.core.ptt.FloorCommitDiscardReason
import java.util.concurrent.atomic.AtomicLong

/**
 * Correlated floor-lifecycle observability.
 *
 * Grep a single attempt end-to-end:
 *     adb logcat | grep "FloorTrace\[17\]"
 *
 * Phases (in typical order):
 *   REQUEST_SEND -> REQUEST_OBSERVED -> ARBITRATION -> GRANT_BROADCAST
 *   -> GRANT_RECEIVED -> GRANT_APPLIED
 * Branches: REQUEST_DROPPED / GRANT_DROPPED with an explicit reason.
 *
 * [traceId] is allocated at REQUEST_SEND and carried in [FloorPayload.traceId] so all
 * participants log the same id. Zero means unknown (legacy / decode miss).
 */
object FloorTrace {
    private val idGenerator = AtomicLong(0)

    fun nextId(): Long = idGenerator.incrementAndGet()

    fun emit(traceId: Long, phase: String, fields: Map<String, String> = emptyMap()) {
        val label = if (traceId > 0L) traceId.toString() else "?"
        val body = fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
        TalkbackLog.i(
            if (body.isEmpty()) "FloorTrace[$label] $phase"
            else "FloorTrace[$label] $phase $body"
        )
    }

    fun requestSend(
        traceId: Long,
        sid: String,
        requester: String,
        epoch: Long,
        version: Long,
        authority: String?,
        result: String
    ) = emit(
        traceId,
        "REQUEST_SEND",
        mapOf(
            "sid" to sid,
            "requester" to requester,
            "epoch" to epoch.toString(),
            "version" to version.toString(),
            "authority" to (authority ?: "null"),
            "result" to result
        )
    )

    fun requestObserved(
        traceId: Long,
        sid: String,
        from: String,
        authority: String?,
        localIsAuthority: Boolean
    ) = emit(
        traceId,
        "REQUEST_OBSERVED",
        mapOf(
            "sid" to sid,
            "from" to from,
            "authority" to (authority ?: "null"),
            "localIsAuthority" to localIsAuthority.toString()
        )
    )

    fun arbitration(
        traceId: Long,
        sid: String,
        requester: String,
        requestEpoch: Long,
        localEpoch: Long,
        requestVersion: Long,
        localVersion: Long,
        currentOwner: String?,
        memberPresent: Boolean,
        result: String
    ) = emit(
        traceId,
        "ARBITRATION",
        mapOf(
            "sid" to sid,
            "requester" to requester,
            "requestEpoch" to requestEpoch.toString(),
            "localEpoch" to localEpoch.toString(),
            "requestVersion" to requestVersion.toString(),
            "localVersion" to localVersion.toString(),
            "currentOwner" to (currentOwner ?: "null"),
            "memberPresent" to memberPresent.toString(),
            "result" to result
        )
    )

    fun grantBroadcast(traceId: Long, sid: String, targets: String) = emit(
        traceId,
        "GRANT_BROADCAST",
        mapOf("sid" to sid, "targets" to targets)
    )

    fun grantReceived(
        traceId: Long,
        sid: String,
        from: String,
        requester: String,
        grantEpoch: Long,
        localEpoch: Long
    ) = emit(
        traceId,
        "GRANT_RECEIVED",
        mapOf(
            "sid" to sid,
            "from" to from,
            "requester" to requester,
            "grantEpoch" to grantEpoch.toString(),
            "localEpoch" to localEpoch.toString()
        )
    )

    fun grantApplied(
        traceId: Long,
        sid: String,
        owner: String,
        epoch: Long,
        version: Long
    ) = emit(
        traceId,
        "GRANT_APPLIED",
        mapOf(
            "sid" to sid,
            "owner" to owner,
            "epoch" to epoch.toString(),
            "version" to version.toString()
        )
    )

    fun requestDropped(traceId: Long, sid: String, reason: String, extra: Map<String, String> = emptyMap()) =
        emit(
            traceId,
            "REQUEST_DROPPED",
            mapOf("sid" to sid, "reason" to reason) + extra
        )

    fun grantDropped(traceId: Long, sid: String, reason: String, extra: Map<String, String> = emptyMap()) =
        emit(
            traceId,
            "GRANT_DROPPED",
            mapOf("sid" to sid, "reason" to reason) + extra
        )

    fun completionDiscarded(
        traceId: Long,
        sid: String,
        reason: FloorCommitDiscardReason,
        extra: Map<String, String> = emptyMap()
    ) = emit(
        traceId,
        "COMPLETION_DISCARDED",
        mapOf("sid" to sid, "reason" to reason.name) + extra
    )
}

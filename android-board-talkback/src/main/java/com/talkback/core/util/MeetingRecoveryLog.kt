package com.talkback.core.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Post-conference GROUP recovery metrics (Issue #46).
 *
 * T0: [onConferenceReleased] — Conference channel released for GROUP PTT
 * T1: [onMeshOperational] — groupTopologyReadiness becomes OPERATIONAL
 * T2: [onFirstFloorGrant] — first GRANT_APPLIED on the channel after T0
 *
 * Grep: `MEETING_RECOVERY`
 */
object MeetingRecoveryLog {
    private data class Pending(
        val reason: String,
        val t0Ms: Long,
        var meshOperationalMs: Long? = null
    )

    private val pendingByChannel = ConcurrentHashMap<String, Pending>()

    fun onConferenceReleased(channelId: String, reason: String) {
        pendingByChannel[channelId] = Pending(
            reason = reason,
            t0Ms = System.currentTimeMillis()
        )
    }

    fun onMeshOperational(channelId: String) {
        val pending = pendingByChannel[channelId] ?: return
        if (pending.meshOperationalMs != null) return
        pending.meshOperationalMs = System.currentTimeMillis()
        TalkbackLog.i(
            formatLine(
                channelId = channelId,
                reason = pending.reason,
                t0Ms = pending.t0Ms,
                meshMs = pending.meshOperationalMs,
                floorMs = null
            )
        )
    }

    fun onFirstFloorGrant(channelId: String) {
        val pending = pendingByChannel.remove(channelId) ?: return
        val now = System.currentTimeMillis()
        TalkbackLog.i(
            formatLine(
                channelId = channelId,
                reason = pending.reason,
                t0Ms = pending.t0Ms,
                meshMs = pending.meshOperationalMs,
                floorMs = now,
                complete = true
            )
        )
    }

    internal fun resetForTest() {
        pendingByChannel.clear()
    }

    internal fun hasPendingForTest(channelId: String): Boolean =
        pendingByChannel.containsKey(channelId)

    private fun formatLine(
        channelId: String,
        reason: String,
        t0Ms: Long,
        meshMs: Long?,
        floorMs: Long?,
        complete: Boolean = false
    ): String = buildString {
        append("MEETING_RECOVERY ch=").append(channelId)
        append(" reason=").append(reason)
        if (meshMs != null) {
            append(" meshRecovery=").append(formatSeconds(meshMs - t0Ms))
        }
        if (floorMs != null) {
            append(" firstFloor=").append(formatSeconds(floorMs - t0Ms))
        }
        if (complete) {
            append(" complete=true")
        }
    }

    private fun formatSeconds(deltaMs: Long): String =
        "%.1fs".format(deltaMs.coerceAtLeast(0L) / 1000.0)
}

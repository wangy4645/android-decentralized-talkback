package com.talkback.core.runtime

import com.talkback.core.model.EndpointAddress
import com.talkback.core.session.SessionDisposition
import com.talkback.core.session.TalkbackSession

/**
 * Module-level foreground Activity admission (ADR-0001).
 * Replaces scattered prepare/isBusy checks; does not own session lifecycle.
 */
class ForegroundActivityAdmission(
    private val stack: ModuleActivityStack,
    private val maxActiveSessions: () -> Int,
    private val foregroundActiveCount: () -> Int
) {
    sealed class Outcome {
        data object Ready : Outcome()
        data class Blocked(val reason: String) : Outcome()
    }

    fun admitUnicastOutgoing(): Outcome {
        if (foregroundActiveCount() > maxActiveSessions()) {
            return Outcome.Blocked("ACTIVE_SESSION_LIMIT")
        }
        if (foregroundActiveCount() > 0 && !stack.hasForegroundActivity()) {
            return Outcome.Blocked("BUSY_ACTIVE_SESSION")
        }
        return Outcome.Ready
    }

    fun canAdmitMeshInvite(): Boolean =
        !isForegroundBusy() && foregroundActiveCount() < maxActiveSessions()

    fun isForegroundBusy(): Boolean = foregroundActiveCount() > 0

    fun onUnicastStarted(
        sessionId: String,
        acting: EndpointAddress,
        requestedBy: EndpointAddress = acting,
        suspendedSessions: Collection<TalkbackSession>
    ) {
        stack.push(
            ActivityFrame(
                activityType = ActivityType.UNICAST,
                sessionId = sessionId,
                actingEndpointId = acting.key,
                requestedBy = requestedBy.key,
                preemptReason = PreemptReason.UNICAST_PREEMPT,
                autoResume = false
            )
        )
        suspendedSessions
            .filter { it.disposition == SessionDisposition.SUSPENDED }
            .forEach { session ->
                stack.recordPreemption(
                    PreemptionToken(
                        suspendedSessionId = session.id,
                        preemptedBySessionId = sessionId,
                        preemptReason = PreemptReason.UNICAST_PREEMPT,
                        actingEndpointId = acting.key
                    )
                )
            }
    }

    fun onUnicastEnded(endedSessionId: String): String? {
        stack.popIfSession(endedSessionId)
        return endedSessionId
    }
}

package com.talkback.core.runtime

import java.util.ArrayDeque

class ModuleActivityStack {
    private val frames = ArrayDeque<ActivityFrame>()
    private val tokensBySuspendedId = linkedMapOf<String, PreemptionToken>()

    fun push(frame: ActivityFrame) {
        frames.addLast(frame)
    }

    fun pop(): ActivityFrame? = frames.pollLast()

    fun top(): ActivityFrame? = frames.lastOrNull()

    fun topSessionId(): String? = top()?.sessionId

    fun topActingEndpointId(): String? = top()?.actingEndpointId

    fun popIfSession(sessionId: String): ActivityFrame? {
        if (top()?.sessionId != sessionId) return null
        return pop()
    }

    fun recordPreemption(token: PreemptionToken) {
        tokensBySuspendedId[token.suspendedSessionId] = token
    }

    fun canResume(suspendedSessionId: String, endedForegroundSessionId: String?): Boolean {
        val token = tokensBySuspendedId[suspendedSessionId] ?: return true
        if (endedForegroundSessionId == null) return false
        return token.preemptedBySessionId == endedForegroundSessionId
    }

    fun consumeResume(suspendedSessionId: String, endedForegroundSessionId: String?): Boolean {
        if (!canResume(suspendedSessionId, endedForegroundSessionId)) return false
        tokensBySuspendedId.remove(suspendedSessionId)
        return true
    }

    fun clearToken(suspendedSessionId: String) {
        tokensBySuspendedId.remove(suspendedSessionId)
    }

    fun hasForegroundActivity(): Boolean {
        val frame = top() ?: return false
        return frame.activityType != ActivityType.IDLE && frame.sessionId != null
    }

    fun clear() {
        frames.clear()
        tokensBySuspendedId.clear()
    }
}

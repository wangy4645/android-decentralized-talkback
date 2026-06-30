package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.util.TalkbackLog

/**
 * R38: read-only local runtime identity derived from canonical [TalkbackSession.groupMembers] (ADR-0009).
 */
object IdentityResolver {

    @Volatile
    internal var compatDriftLogger: ((String) -> Unit)? = null

    fun local(session: TalkbackSession, localModuleId: String): EndpointAddress {
        if (session.type != SessionType.GROUP) return session.local
        return session.groupMembers.find { it.moduleId.value == localModuleId } ?: session.local
    }

    fun localKey(session: TalkbackSession, localModuleId: String): String =
        local(session, localModuleId).key

    fun isLocal(session: TalkbackSession, endpoint: EndpointAddress, localModuleId: String): Boolean {
        val canonical = local(session, localModuleId)
        if (endpoint.key == canonical.key) return true
        if (endpoint.moduleId.value == localModuleId) {
            if (session.type == SessionType.GROUP && endpoint.key != canonical.key) {
                logCompatDrift(session.id, canonical.key, endpoint.key)
            }
            return true
        }
        return false
    }

    private fun logCompatDrift(sessionId: String, expected: String, actual: String) {
        val message =
            "LOCAL_IDENTITY_DRIFT_COMPAT sessionId=$sessionId expected=$expected actual=$actual"
        compatDriftLogger?.invoke(message) ?: TalkbackLog.w(message)
    }
}

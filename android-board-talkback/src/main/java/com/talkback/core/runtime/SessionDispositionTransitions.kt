package com.talkback.core.runtime

import com.talkback.core.session.SessionDisposition
import com.talkback.core.session.TalkbackSession

/** Session disposition FSM helpers (ADR-0001). Media/UI read [TalkbackSession.disposition] only (R2). */
object SessionDispositionTransitions {

    fun suspend(session: TalkbackSession): Boolean {
        when (session.disposition) {
            SessionDisposition.ACTIVE,
            SessionDisposition.YIELDED_TO_AUTHORITY -> {
                session.disposition = SessionDisposition.SUSPENDED
                return true
            }
            SessionDisposition.SUSPENDED -> return false
            else -> return false
        }
    }

    fun beginResume(session: TalkbackSession): Boolean {
        if (session.disposition != SessionDisposition.SUSPENDED) return false
        session.disposition = SessionDisposition.RESUMING
        return true
    }

    fun markActive(session: TalkbackSession): Boolean {
        if (session.disposition == SessionDisposition.RESUMING) {
            session.disposition = SessionDisposition.ACTIVE
            return true
        }
        return false
    }

    fun beginTerminate(session: TalkbackSession) {
        session.disposition = SessionDisposition.TERMINATING
    }

    fun markTerminated(session: TalkbackSession) {
        session.disposition = SessionDisposition.TERMINATED
    }
}

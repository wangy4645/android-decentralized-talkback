package com.talkback.appprod.ui

import com.talkback.core.util.TalkbackLog

/**
 * Behavior-neutral field OBS for Meeting invite overlay / join harness (PRE-I1…I6).
 * Does not change admission, accept, or R1 paths.
 */
object MeetingInviteUiObservability {
    fun log(sessionId: String, state: String) {
        val sid = sessionId.trim()
        if (sid.isEmpty()) return
        TalkbackLog.i("MEETING_INVITE_UI sessionId=$sid state=$state")
    }
}

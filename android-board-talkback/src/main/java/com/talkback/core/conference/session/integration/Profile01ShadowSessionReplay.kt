package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate

/**
 * Shared shadow lifecycle replay — peer wire ingress and host local projection.
 */
object Profile01ShadowSessionReplay {
    fun replaySessionStarted(
        sessionId: String,
        channelId: String,
        membershipVersionForRead: Long,
    ) {
        ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStarted(
            sessionId = sessionId,
            channelId = channelId,
            rosterEpoch = membershipVersionForRead,
        )
    }
}

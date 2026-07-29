package com.talkback.core.session

/**
 * ICE-connected side-effect routing for conference receive-path bootstrap vs anchor backup standby.
 * Intentionally separate filters — do not merge into one loop.
 */
internal object ConferenceIceConnectedSideEffects {

    fun sessionsForReceivePathBootstrap(sessions: Collection<TalkbackSession>): List<TalkbackSession> =
        sessions.filter { it.type == SessionType.CONFERENCE && it.accepted }

    fun sessionsForBackupStandbyMaintenance(sessions: Collection<TalkbackSession>): List<TalkbackSession> =
        sessions.filter {
            it.mediaTopology == GroupMediaTopology.ANCHOR &&
                it.accepted &&
                (it.type == SessionType.GROUP || it.type == SessionType.CONFERENCE)
        }
}

package com.talkback.core.session

import com.talkback.core.util.TalkbackLog

/**
 * Serializes [ConferenceRuntimeState] and dual-track observability to logcat (RO-M2 PR-2 / ADR-0020).
 */
object ConferenceRuntimeProjectionLogger {

    const val TAG = "CONFERENCE_RUNTIME_PROJECTION"

    fun log(
        sessionId: String,
        channelId: String?,
        runtime: ConferenceRuntimeState,
        channelReadiness: ChannelReadiness?,
        conferenceUiReady: Boolean,
        isConferenceHost: Boolean,
        authorityReachable: Boolean,
        joinedParticipantCount: Int,
        pendingInviteeCount: Int
    ) {
        TalkbackLog.i(
            format(
                sessionId = sessionId,
                channelId = channelId,
                runtime = runtime,
                channelReadiness = channelReadiness,
                conferenceUiReady = conferenceUiReady,
                isConferenceHost = isConferenceHost,
                authorityReachable = authorityReachable,
                joinedParticipantCount = joinedParticipantCount,
                pendingInviteeCount = pendingInviteeCount
            )
        )
    }

    fun format(
        sessionId: String,
        channelId: String?,
        runtime: ConferenceRuntimeState,
        channelReadiness: ChannelReadiness?,
        conferenceUiReady: Boolean,
        isConferenceHost: Boolean = false,
        authorityReachable: Boolean = false,
        joinedParticipantCount: Int = 0,
        pendingInviteeCount: Int = 0
    ): String = buildString {
        append(TAG)
        append(" sessionId=").append(sessionId)
        append(" ch=").append(channelId ?: "null")
        append(" phase=").append(runtime.phase.name)
        append(" host=").append(isConferenceHost)
        append(" authority=").append(authorityReachable)
        append(" joined=").append(joinedParticipantCount)
        append(" pending=").append(pendingInviteeCount)
        append(" recovering=").append(runtime.mediaRecovering)
        append(" edgeRecovering=").append(runtime.edgeRecovering)
        append(" awaiting=").append(runtime.awaitingAdditionalParticipants)
        append(" controlReady=").append(runtime.transitionTerminalReady)
        append(" connected=").append(runtime.connectedRemoteMediaCount)
        append(" channelReadiness=").append(channelReadiness?.name ?: "null")
        append(" conferenceUiReady=").append(conferenceUiReady)
    }
}

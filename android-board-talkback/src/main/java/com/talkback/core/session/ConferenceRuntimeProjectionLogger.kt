package com.talkback.core.session

import com.talkback.core.util.TalkbackLog

/**
 * Serializes [ConferenceRuntimeState] and dual-track observability to logcat (RO-M2 PR-2 / ADR-0020).
 */
object ConferenceRuntimeProjectionLogger {

    const val TAG = "CONFERENCE_RUNTIME_PROJECTION"
    /** Input-fact dump for diagnosing CONNECTING-with-audio (Issue2). */
    const val DECISION_TAG = "CONFERENCE_RUNTIME_DECISION"
    /**
     * Gate-R1-B: mesh ICE recovered but no Conference session is present.
     * Forbidden third state is ICE_CONNECTED with neither DECISION nor MISSING.
     */
    const val MISSING_TAG = "CONFERENCE_RUNTIME_MISSING"

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
        append(" conferenceDegraded=").append(runtime.conferenceDegraded)
        append(" awaiting=").append(runtime.awaitingAdditionalParticipants)
        append(" controlReady=").append(runtime.transitionTerminalReady)
        append(" connected=").append(runtime.connectedRemoteMediaCount)
        append(" channelReadiness=").append(channelReadiness?.name ?: "null")
        append(" conferenceUiReady=").append(conferenceUiReady)
    }

    fun formatDecision(
        sessionId: String,
        channelId: String?,
        phase: ConferenceRuntimePhase,
        isConferenceHost: Boolean,
        sessionAccepted: Boolean,
        localConferenceReady: Boolean,
        transitionTerminalReady: Boolean,
        authorityReachable: Boolean,
        hostModuleId: String?,
        hostIce: String?,
        hostEnginePresent: Boolean,
        hostConferenceEngine: Boolean,
        meshIcePeers: String,
        connectedRemoteMediaCount: Int,
        edgeRecovering: Boolean,
        edgeRecoveryFailed: Boolean = false,
        conferenceDegraded: Boolean = false,
        mediaRecovering: Boolean,
        conferenceUiReady: Boolean,
        conferenceSessionPresent: Boolean = true,
        conferenceGeneration: Long = 0L
    ): String = buildString {
        append(DECISION_TAG)
        append(" conferenceSessionPresent=").append(conferenceSessionPresent)
        append(" conferenceSessionId=").append(sessionId)
        append(" conferenceGeneration=").append(conferenceGeneration)
        append(" session=").append(sessionId)
        append(" ch=").append(channelId ?: "null")
        append(" phase=").append(phase.name)
        append(" host=").append(isConferenceHost)
        append(" accepted=").append(sessionAccepted)
        append(" localReady=").append(localConferenceReady)
        append(" controlReady=").append(transitionTerminalReady)
        append(" authorityReachable=").append(authorityReachable)
        append(" hostModule=").append(hostModuleId ?: "null")
        append(" hostIce=").append(hostIce ?: "null")
        append(" hostEngine=").append(hostEnginePresent)
        append(" hostConfEngine=").append(hostConferenceEngine)
        append(" meshIcePeers=").append(meshIcePeers.ifEmpty { "-" })
        append(" connected=").append(connectedRemoteMediaCount)
        append(" edgeRecovering=").append(edgeRecovering)
        append(" edgeRecoveryFailed=").append(edgeRecoveryFailed)
        append(" conferenceDegraded=").append(conferenceDegraded)
        append(" mediaRecovering=").append(mediaRecovering)
        append(" conferenceUiReady=").append(conferenceUiReady)
    }

    fun formatMissing(
        channelId: String?,
        peerModuleId: String,
        iceState: String,
        reason: String,
        conferenceSessionCount: Int = 0
    ): String = buildString {
        append(MISSING_TAG)
        append(" conferenceSessionPresent=false")
        append(" conferenceSessionId=null")
        append(" conferenceGeneration=-1")
        append(" conferenceSessionCount=").append(conferenceSessionCount)
        append(" ch=").append(channelId ?: "null")
        append(" peer=").append(peerModuleId)
        append(" ice=").append(iceState)
        append(" reason=").append(reason)
    }
}
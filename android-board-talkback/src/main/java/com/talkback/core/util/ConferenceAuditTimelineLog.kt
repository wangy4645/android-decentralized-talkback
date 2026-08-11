package com.talkback.core.util

/**
 * RO-M3 boundary audit: minimal timeline tags for lifecycle / authority writes.
 *
 * Grep:
 * `CONFERENCE_LIFECYCLE_TIMELINE` | `AUTHORITY_TIMELINE` | `ADMISSION_DECISION` |
 * `CHANNEL_SESSION_SNAPSHOT` | `CHANNEL_SESSION_BIND` | `READINESS_BINDING`
 *
 * Edge recovery continues to use `RECOVERY_*` tags from
 * [com.talkback.core.session.ConferenceEdgeRecoveryController].
 */
object ConferenceAuditTimelineLog {
    private var logSink: ((String) -> Unit)? = null

    internal fun resetForTest(sink: ((String) -> Unit)? = null) {
        logSink = sink
    }

    private fun log(message: String) {
        val sink = logSink
        if (sink != null) {
            sink(message)
        } else {
            TalkbackLog.i(message)
        }
    }

    data class ChannelSessionCandidate(
        val sessionId: String,
        val type: String,
        val role: String,
        val initiatorModuleId: String?,
        val state: String,
        val accepted: Boolean,
        val score: Int? = null
    )

    fun lifecycle(
        event: String,
        channelId: String?,
        sessionId: String?,
        writer: String,
        cause: String
    ) {
        log(
            buildString {
                append("CONFERENCE_LIFECYCLE_TIMELINE")
                append(" event=").append(event)
                channelId?.let { append(" ch=").append(it) }
                sessionId?.let { append(" session=").append(it) }
                append(" writer=").append(writer)
                append(" cause=").append(cause)
            }
        )
    }

    fun sessionCreated(
        sessionId: String,
        channelId: String?,
        type: String,
        localModuleId: String,
        initiatorModuleId: String?,
        localRole: String,
        creationSource: String,
        writer: String,
        cause: String
    ) {
        log(
            buildString {
                append("CONFERENCE_LIFECYCLE_TIMELINE")
                append(" event=SESSION_CREATED")
                channelId?.let { append(" ch=").append(it) }
                append(" session=").append(sessionId)
                append(" type=").append(type)
                append(" localModuleId=").append(localModuleId)
                append(" initiatorModuleId=").append(initiatorModuleId ?: "null")
                append(" localRole=").append(localRole)
                append(" creationSource=").append(creationSource)
                append(" writer=").append(writer)
                append(" cause=").append(cause)
            }
        )
    }

    fun admissionDecision(
        action: String,
        channelId: String,
        incomingSessionId: String?,
        existingSessionIds: List<String>,
        decision: String,
        reason: String,
        writer: String,
        localModuleId: String? = null,
        localRole: String? = null,
        initiatorModuleId: String? = null,
        creationSource: String? = null
    ) {
        log(
            buildString {
                append("ADMISSION_DECISION")
                append(" action=").append(action)
                append(" ch=").append(channelId)
                incomingSessionId?.let { append(" incomingSessionId=").append(it) }
                append(" existingSessions=").append(existingSessionIds.joinToString(",", "[", "]"))
                append(" decision=").append(decision)
                append(" reason=").append(reason)
                append(" writer=").append(writer)
                localModuleId?.let { append(" localModuleId=").append(it) }
                localRole?.let { append(" localRole=").append(it) }
                initiatorModuleId?.let { append(" initiatorModuleId=").append(it) }
                creationSource?.let { append(" creationSource=").append(it) }
            }
        )
    }

    fun sessionTerminated(
        sessionId: String,
        channelId: String?,
        role: String,
        reason: String,
        writer: String
    ) {
        log(
            buildString {
                append("CONFERENCE_LIFECYCLE_TIMELINE")
                append(" event=SESSION_TERMINATED")
                channelId?.let { append(" ch=").append(it) }
                append(" session=").append(sessionId)
                append(" role=").append(role)
                append(" reason=").append(reason)
                append(" writer=").append(writer)
            }
        )
    }

    fun mediaRuntimeRelease(
        sessionId: String,
        channelId: String?,
        peerConnectionState: String,
        tracksReleased: Boolean,
        writer: String,
        cause: String
    ) {
        log(
            buildString {
                append("CONFERENCE_LIFECYCLE_TIMELINE")
                append(" event=MEDIA_RUNTIME_RELEASE")
                channelId?.let { append(" ch=").append(it) }
                append(" session=").append(sessionId)
                append(" pcState=").append(peerConnectionState)
                append(" tracksReleased=").append(tracksReleased)
                append(" writer=").append(writer)
                append(" cause=").append(cause)
            }
        )
    }

    fun sessionRemoveBegin(
        sessionId: String,
        channelId: String?,
        remainingSessionIds: List<String>,
        writer: String
    ) {
        log(
            buildString {
                append("CONFERENCE_LIFECYCLE_TIMELINE")
                append(" event=SESSION_REMOVE_BEGIN")
                channelId?.let { append(" ch=").append(it) }
                append(" session=").append(sessionId)
                append(" remainingSessions=")
                    .append(remainingSessionIds.joinToString(",", "[", "]"))
                append(" writer=").append(writer)
            }
        )
    }

    fun sessionRemoveComplete(
        sessionId: String,
        channelId: String?,
        remainingSessionIds: List<String>,
        writer: String
    ) {
        log(
            buildString {
                append("CONFERENCE_LIFECYCLE_TIMELINE")
                append(" event=SESSION_REMOVE_COMPLETE")
                channelId?.let { append(" ch=").append(it) }
                append(" session=").append(sessionId)
                append(" remainingSessions=")
                    .append(remainingSessionIds.joinToString(",", "[", "]"))
                append(" writer=").append(writer)
            }
        )
    }

    fun channelSessionSnapshot(
        channelId: String,
        sessions: List<ChannelSessionCandidate>,
        writer: String,
        trigger: String
    ) {
        if (sessions.size <= 1) return
        log(
            buildString {
                append("CHANNEL_SESSION_SNAPSHOT")
                append(" ch=").append(channelId)
                append(" trigger=").append(trigger)
                append(" writer=").append(writer)
                append(" sessions=").append(formatCandidates(sessions))
            }
        )
    }

    fun channelSessionBind(
        channelId: String,
        candidates: List<ChannelSessionCandidate>,
        selectedSessionId: String?,
        reason: String,
        writer: String
    ) {
        if (candidates.size <= 1 && selectedSessionId != null) return
        log(
            buildString {
                append("CHANNEL_SESSION_BIND")
                append(" ch=").append(channelId)
                append(" candidates=").append(formatCandidates(candidates))
                append(" selected=").append(selectedSessionId ?: "null")
                append(" reason=").append(reason)
                append(" writer=").append(writer)
            }
        )
    }

    fun readinessBinding(
        channelId: String,
        selectedSessionId: String?,
        sessionType: String?,
        localRole: String?,
        initiatorModuleId: String?,
        isHostSession: Boolean,
        uiReady: Boolean,
        readyBlockReason: String,
        hostIce: String?,
        channelReadiness: String,
        writer: String
    ) {
        val shouldLog = !uiReady || readyBlockReason != "NONE"
        if (!shouldLog && selectedSessionId == null) return
        log(
            buildString {
                append("READINESS_BINDING")
                append(" ch=").append(channelId)
                append(" selectedSessionId=").append(selectedSessionId ?: "null")
                sessionType?.let { append(" sessionType=").append(it) }
                localRole?.let { append(" localRole=").append(it) }
                initiatorModuleId?.let { append(" initiatorModuleId=").append(it) }
                append(" isHostSession=").append(isHostSession)
                append(" uiReady=").append(uiReady)
                append(" readyBlockReason=").append(readyBlockReason)
                append(" hostIce=").append(hostIce ?: "n/a")
                append(" channelReadiness=").append(channelReadiness)
                append(" writer=").append(writer)
            }
        )
    }

    fun authority(
        sessionId: String,
        channelId: String?,
        hostModuleId: String,
        reachable: Boolean,
        hostIceState: String?,
        writer: String,
        cause: String
    ) {
        log(
            buildString {
                append("AUTHORITY_TIMELINE")
                append(" session=").append(sessionId)
                channelId?.let { append(" ch=").append(it) }
                append(" host=").append(hostModuleId)
                append(" reachable=").append(reachable)
                append(" hostIce=").append(hostIceState ?: "unknown")
                append(" writer=").append(writer)
                append(" cause=").append(cause)
            }
        )
    }

    private fun formatCandidates(candidates: List<ChannelSessionCandidate>): String =
        candidates.joinToString(",", "[", "]") { candidate ->
            buildString {
                append("{id=").append(candidate.sessionId)
                append(",type=").append(candidate.type)
                append(",role=").append(candidate.role)
                append(",initiator=").append(candidate.initiatorModuleId ?: "null")
                append(",state=").append(candidate.state)
                append(",accepted=").append(candidate.accepted)
                candidate.score?.let { append(",score=").append(it) }
                append("}")
            }
        }
}

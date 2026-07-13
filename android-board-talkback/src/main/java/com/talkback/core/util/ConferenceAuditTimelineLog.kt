package com.talkback.core.util

/**
 * RO-M3 boundary audit: minimal timeline tags for lifecycle / authority writes.
 *
 * Grep: `CONFERENCE_LIFECYCLE_TIMELINE` | `AUTHORITY_TIMELINE`
 *
 * Edge recovery continues to use `RECOVERY_*` tags from [com.talkback.core.session.ConferenceEdgeRecoveryController].
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
}

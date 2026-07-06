package com.talkback.core.util

import com.talkback.core.session.ChannelMode

/**
 * Issue: Conference runtime resurrected after termination — caller / mode authority diagnostics.
 *
 * Grep: `JOIN_MEETING_TRACE` | `CHANNEL_MODE`
 */
object ChannelObservabilityLog {
    private const val STACK_SKIP = 4
    private const val STACK_FRAMES = 3

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

    fun joinMeetingTrace(
        reason: String,
        channelId: String,
        talkTabMode: String,
        meetingPreferred: Boolean?,
        coordinatorChannelMode: String?,
        configChannelMode: String,
        conferenceSessionActive: Boolean
    ) {
        val caller = callerFrame()
        log(
            buildString {
                append("JOIN_MEETING_TRACE")
                append(" reason=").append(reason)
                append(" ch=").append(channelId)
                append(" talkTabMode=").append(talkTabMode)
                append(" meetingPreferred=").append(meetingPreferred)
                append(" coordinatorMode=").append(coordinatorChannelMode ?: "null")
                append(" configChannelMode=").append(configChannelMode)
                append(" conferenceSessionActive=").append(conferenceSessionActive)
                append(" caller=").append(caller)
                append(" stack=").append(shortStack(STACK_SKIP, STACK_FRAMES))
            }
        )
    }

    fun channelModeTransition(
        channelId: String,
        from: ChannelMode,
        to: ChannelMode,
        byModuleId: String?,
        op: String
    ) {
        if (from == to) return
        log(
            "CHANNEL_MODE ch=$channelId $from->$to by=${byModuleId ?: "null"} op=$op stack=" +
                shortStack(STACK_SKIP, STACK_FRAMES)
        )
    }

    internal fun shortStack(skipFrames: Int, frameCount: Int): String {
        val frames = Thread.currentThread().stackTrace
        return frames
            .drop(skipFrames)
            .take(frameCount)
            .joinToString(" <- ") { "${simpleClass(it.className)}.${it.methodName}" }
    }

    private fun callerFrame(): String {
        val frame = Thread.currentThread().stackTrace.drop(STACK_SKIP).firstOrNull() ?: return "unknown"
        return "${simpleClass(frame.className)}.${frame.methodName}"
    }

    private fun simpleClass(className: String): String {
        val simple = className.substringAfterLast('.')
        return simple.substringBefore('$')
    }
}

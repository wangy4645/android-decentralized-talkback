package com.talkback.core.util

import com.talkback.core.session.ChannelMode

/**
 * Issue: Conference runtime resurrected after termination — caller / mode authority diagnostics.
 *
 * Grep: `JOIN_MEETING_TRACE` | `OPEN_MEETING_OPTIONS` | `ADMISSION_INTENT_RECEIVED` | `CHANNEL_MODE`
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

    fun admissionIntentReceived(
        intent: String,
        channelId: String,
        conferenceSessionActive: Boolean,
        localModuleId: String? = null
    ) {
        log(
            buildString {
                append("ADMISSION_INTENT_RECEIVED")
                append(" intent=").append(intent)
                append(" ch=").append(channelId)
                append(" conferenceSessionActive=").append(conferenceSessionActive)
                localModuleId?.let { append(" localModuleId=").append(it) }
                append(" caller=").append(callerFrame())
            }
        )
    }

    fun meetingNavigationTrace(
        target: String,
        channelId: String,
        conferenceSessionActive: Boolean,
        localModuleId: String? = null
    ) {
        val event = when (target) {
            "OPTIONS" -> "OPEN_MEETING_OPTIONS"
            "MEMBERS" -> "OPEN_MEETING_MEMBERS"
            "MAIN" -> "OPEN_MEETING_MAIN"
            "INVITE" -> "OPEN_MEETING_INVITE"
            else -> "NAVIGATE_MEETING_$target"
        }
        log(
            buildString {
                append(event)
                append(" target=").append(target)
                append(" ch=").append(channelId)
                append(" conferenceSessionActive=").append(conferenceSessionActive)
                localModuleId?.let { append(" localModuleId=").append(it) }
                append(" caller=").append(callerFrame())
            }
        )
    }

    fun joinMeetingTrace(
        intent: String,
        channelId: String,
        talkTabMode: String,
        meetingPreferred: Boolean?,
        coordinatorChannelMode: String?,
        configChannelMode: String,
        conferenceSessionActive: Boolean,
        phase: String = "intent",
        localModuleId: String? = null,
        userAction: String = "START_MEETING",
        authorityModuleId: String? = null,
        shouldLocalInitiateConference: Boolean? = null,
        chosenPath: String? = null,
        pathReason: String? = null
    ) {
        val caller = callerFrame()
        log(
            buildString {
                append("JOIN_MEETING_TRACE")
                append(" phase=").append(phase)
                append(" intent=").append(intent)
                append(" ch=").append(channelId)
                append(" userAction=").append(userAction)
                localModuleId?.let { append(" localModuleId=").append(it) }
                append(" authorityModuleId=").append(authorityModuleId ?: "null")
                shouldLocalInitiateConference?.let {
                    append(" shouldLocalInitiateConference=").append(it)
                }
                chosenPath?.let { append(" chosenPath=").append(it) }
                pathReason?.let { append(" pathReason=").append(it) }
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

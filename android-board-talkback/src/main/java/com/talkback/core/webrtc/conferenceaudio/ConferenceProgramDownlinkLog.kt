 package com.talkback.core.webrtc.conferenceaudio

import com.talkback.core.util.TalkbackLog
import com.talkback.core.webrtc.ProgramSenderSnapshot

/**
 * P0 structured field log for Anchor PROGRAM downlink.
 * Observation only.
 */
object ConferenceProgramDownlinkLog {
    private var testSink: ((String) -> Unit)? = null

    internal fun resetForTest(sink: ((String) -> Unit)? = null) {
        testSink = sink
    }

    fun emitInbound(
        conferenceId: String,
        anchorId: String,
        remoteSpoke: String,
        inboundFrames: Long,
        lastInboundTs: Long,
        pcmFormat: ConferencePcmFormat
    ) {
        write(
            "CONFERENCE_PROGRAM_INBOUND " +
                "conferenceId=$conferenceId " +
                "anchorId=$anchorId " +
                "remoteSpoke=$remoteSpoke " +
                "inboundFrames=$inboundFrames " +
                "lastInboundTs=$lastInboundTs " +
                "pcmFormat=${pcmFormat.sampleRateHz}/${pcmFormat.channels}/${pcmFormat.bitsPerSample}"
        )
    }

    fun emitMix(
        conferenceId: String,
        programFramesProduced: Long,
        mixInputCount: Int,
        activeSources: Collection<String>,
        routingGeneration: Long,
        lastProgramTs: Long
    ) {
        val sources = if (activeSources.isEmpty()) "-" else activeSources.sorted().joinToString(",")
        write(
            "CONFERENCE_PROGRAM_MIX " +
                "conferenceId=$conferenceId " +
                "programFramesProduced=$programFramesProduced " +
                "mixInputCount=$mixInputCount " +
                "activeSources=$sources " +
                "routingGeneration=$routingGeneration " +
                "lastProgramTs=$lastProgramTs"
        )
    }

    fun emitSender(
        conferenceId: String,
        spoke: String,
        snapshot: ProgramSenderSnapshot?,
        programFramesSent: Long,
        lastSendTs: Long
    ) {
        val s = snapshot ?: ProgramSenderSnapshot.NONE
        write(
            "CONFERENCE_PROGRAM_SENDER " +
                "conferenceId=$conferenceId " +
                "spoke=$spoke " +
                "senderId=${s.senderId} " +
                "currentTrackId=${s.currentTrackId} " +
                "expectedTrackId=${s.expectedTrackId} " +
                "enabled=${s.enabled} " +
                "readyState=${s.readyState} " +
                "lastReplaceAt=${s.lastReplaceAt} " +
                "programFramesSent=$programFramesSent " +
                "lastSendTs=$lastSendTs"
        )
    }

    private fun write(line: String) {
        val sink = testSink
        if (sink != null) {
            sink(line)
        } else {
            TalkbackLog.i(line)
        }
    }
}

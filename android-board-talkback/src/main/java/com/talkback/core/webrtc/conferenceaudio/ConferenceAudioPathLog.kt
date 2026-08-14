package com.talkback.core.webrtc.conferenceaudio

import com.talkback.core.util.TalkbackLog

/**
 * ADR-0056 IG-2 — structured field observation for conference audio path.
 * Observation only; not recovery/lifecycle/topology authority.
 */
object ConferenceAudioPathLog {
    private var testSink: ((String) -> Unit)? = null

    internal fun resetForTest(sink: ((String) -> Unit)? = null) {
        testSink = sink
    }

    fun emit(fact: ConferenceAudioPathFact) {
        val line = format(fact)
        val sink = testSink
        if (sink != null) {
            sink(line)
        } else {
            TalkbackLog.i(line)
        }
    }

    internal fun format(fact: ConferenceAudioPathFact): String {
        val parts = mutableListOf("CONFERENCE_AUDIO_PATH")
        parts += "conferenceId=${fact.conferenceId}"
        parts += "endpointId=${fact.endpointId}"
        fact.topologyMode?.let { parts += "topologyMode=$it" }
        fact.anchorModuleId?.takeIf { it.isNotBlank() }?.let { parts += "anchorModuleId=$it" }
        parts += "participantMediaMode=${fact.participantMediaMode}"
        parts += "localMicActive=${fact.localMicActive}"
        parts += "muted=${fact.muted}"
        parts += "mixerSourceCount=${fact.mixerSourceCount}"
        parts += "injectionPortState=${if (fact.injectionPortOpen) "OPEN" else "CLOSED"}"
        fact.failureReason?.let { parts += "failureReason=$it" }
        return parts.joinToString(" ")
    }
}

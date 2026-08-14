package com.talkback.core.webrtc.conferenceaudio

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConferenceAudioPathObservabilityLogTest {

    private lateinit var observability: ConferenceAudioPathObservability
    private val logLines = mutableListOf<String>()

    @Before
    fun setUp() {
        logLines.clear()
        ConferenceAudioPathLog.resetForTest { logLines.add(it) }
        observability = ConferenceAudioPathObservability()
    }

    @After
    fun tearDown() {
        ConferenceAudioPathLog.resetForTest()
    }

    @Test
    fun publish_emitsStructuredConferenceAudioPathLog() {
        observability.publish(sampleFact())

        assertTrue(logLines.size == 1)
        val line = logLines.single()
        assertTrue(line.startsWith("CONFERENCE_AUDIO_PATH "))
        assertTrue(line.contains("conferenceId=conf-log"))
        assertTrue(line.contains("endpointId=E01"))
        assertTrue(line.contains("topologyMode=ANCHOR"))
        assertTrue(line.contains("anchorModuleId=M01"))
        assertTrue(line.contains("participantMediaMode=LOCAL_AND_REMOTE"))
        assertTrue(line.contains("localMicActive=true"))
        assertTrue(line.contains("mixerSourceCount=3"))
        assertTrue(line.contains("injectionPortState=OPEN"))
    }

    @Test
    fun publish_includesFailureReasonWhenPresent() {
        observability.publish(
            sampleFact().copy(
                injectionFailure = true,
                failureReason = PcmInjectionFailure.INJECT_FAILED,
                targetModuleId = "M02"
            )
        )

        assertTrue(logLines.size == 2)
        assertTrue(logLines[0].contains("failureReason=INJECT_FAILED"))
        val failureLine = logLines[1]
        assertTrue(failureLine.startsWith("CONFERENCE_AUDIO_PATH_FAILURE "))
        assertTrue(failureLine.contains("targetModuleId=M02"))
        assertTrue(failureLine.contains("reason=INJECT_FAILED"))
    }

    private fun sampleFact(): ConferenceAudioPathFact =
        ConferenceAudioPathFact(
            conferenceId = "conf-log",
            endpointId = "E01",
            participantMediaMode = ParticipantMediaMode.LOCAL_AND_REMOTE,
            localMicActive = true,
            muted = false,
            mixerSourceCount = 3,
            injectionPortOpen = true,
            injectionFailure = false,
            failureReason = null,
            topologyMode = "ANCHOR",
            anchorModuleId = "M01"
        )
}

package com.talkback.core.webrtc.conferenceaudio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConferenceAudioPathObservabilityTest {

    private lateinit var observability: ConferenceAudioPathObservability
    private val observed = mutableListOf<ConferenceAudioPathFact>()

    @Before
    fun setUp() {
        ConferenceAudioPathLog.resetForTest { }
        observability = ConferenceAudioPathObservability()
        observed.clear()
        observability.observe { observed.add(it) }
    }

    @After
    fun tearDown() {
        ConferenceAudioPathLog.resetForTest()
    }

    @Test
    fun publish_deliversStructuredFactToListeners() {
        val fact = sampleFact(localMicActive = true)
        observability.publish(fact)

        assertEquals(1, observed.size)
        assertEquals(fact, observed.single())
        assertEquals(1, observability.recordedFacts().size)
    }

    @Test
    fun injectionFailure_factFieldsAreQueryable() {
        observability.publish(
            sampleFact(
                localMicActive = true,
                injectionFailure = true,
                failureReason = PcmInjectionFailure.NOT_OPEN,
                targetModuleId = "M03"
            )
        )

        val fact = observed.single()
        assertTrue(fact.injectionFailure)
        assertEquals(PcmInjectionFailure.NOT_OPEN, fact.failureReason)
        assertEquals("M03", fact.targetModuleId)
        assertEquals(ParticipantMediaMode.LOCAL_AND_REMOTE, fact.participantMediaMode)
        assertEquals(3, fact.mixerSourceCount)
    }

    private fun sampleFact(
        localMicActive: Boolean,
        injectionFailure: Boolean = false,
        failureReason: PcmInjectionFailure? = null,
        targetModuleId: String? = null
    ): ConferenceAudioPathFact =
        ConferenceAudioPathFact(
            conferenceId = "conf-obs",
            endpointId = "E01",
            participantMediaMode = ParticipantMediaMode.LOCAL_AND_REMOTE,
            localMicActive = localMicActive,
            muted = false,
            mixerSourceCount = 3,
            injectionPortOpen = true,
            injectionFailure = injectionFailure,
            failureReason = failureReason,
            targetModuleId = targetModuleId
        )
}

package com.talkback.core.webrtc.conferenceaudio

import java.util.concurrent.CopyOnWriteArrayList

/**
 * ADR-0056 Phase 1a-5 — conference audio path facts (not recovery/lifecycle timeline).
 */
data class ConferenceAudioPathFact(
    val conferenceId: String,
    val endpointId: String,
    val participantMediaMode: ParticipantMediaMode,
    val localMicActive: Boolean,
    val muted: Boolean,
    val mixerSourceCount: Int,
    val injectionPortOpen: Boolean,
    val injectionFailure: Boolean,
    val failureReason: PcmInjectionFailure?,
    val topologyMode: String? = null,
    val anchorModuleId: String? = null,
    val targetModuleId: String? = null
)

class ConferenceAudioPathObservability {
    private val listeners = CopyOnWriteArrayList<(ConferenceAudioPathFact) -> Unit>()
    private val recordedFacts = CopyOnWriteArrayList<ConferenceAudioPathFact>()

    fun observe(listener: (ConferenceAudioPathFact) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun recordedFacts(): List<ConferenceAudioPathFact> = recordedFacts.toList()

    fun resetForTest() {
        recordedFacts.clear()
        listeners.clear()
        ConferenceAudioPathLog.resetForTest()
    }

    fun publish(fact: ConferenceAudioPathFact) {
        recordedFacts.add(fact)
        ConferenceAudioPathLog.emit(fact)
        listeners.forEach { it.invoke(fact) }
    }
}

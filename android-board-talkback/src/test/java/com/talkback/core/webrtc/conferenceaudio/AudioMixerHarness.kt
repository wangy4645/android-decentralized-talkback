package com.talkback.core.webrtc.conferenceaudio

/**
 * Phase 1a-1 harness: admitted fake sources → [AudioMixerSession] → captured outputs.
 * No WebRTC, Coordinator, topology, or multicast.
 */
class AudioMixerHarness(
    rampFrames: Int = 0,
    silenceThreshold: Int = 64
) {
    val events = mutableListOf<AudioMixerEvent>()
    val outputs = mutableListOf<AudioMixerOutput>()
    private val sources = linkedMapOf<String, FakeRemotePcmSource>()
    val mixerSession = AudioMixerSession(
        mixer = AudioMixer(rampFrames = rampFrames, silenceThreshold = silenceThreshold),
        onEvent = { events.add(it) }
    )

    fun admitSource(sourceId: String, level: Short = 4_000): FakeRemotePcmSource {
        val source = FakeRemotePcmSource(sourceId, level = level)
        source.admit()
        sources[sourceId] = source
        mixerSession.admitSource(sourceId)
        return source
    }

    fun pushSource(sourceId: String): Result<Unit> {
        val source = sources[sourceId]
            ?: return Result.failure(IllegalArgumentException("unknown source: $sourceId"))
        return mixerSession.push(AudioMixerInput(sourceId, source.produceFrame()))
    }

    fun renderTick(): AudioMixerOutput {
        val output = mixerSession.render()
        outputs.add(output)
        return output
    }

    fun pushAndRender(sourceId: String): Result<AudioMixerOutput> =
        pushSource(sourceId).map { renderTick() }

    fun revokeSource(sourceId: String) {
        sources.remove(sourceId)?.revoke()
        mixerSession.revokeSource(sourceId)
    }
}

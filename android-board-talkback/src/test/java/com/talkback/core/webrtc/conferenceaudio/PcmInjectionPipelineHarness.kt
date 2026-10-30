package com.talkback.core.webrtc.conferenceaudio

/**
 * Phase 1a-2 integration harness:
 *
 * ```text
 * FakeRemotePcmSource → AudioMixer → PcmInjectionPortSession → RecordingPcmInjectionPort
 * ```
 *
 * Proves the injection seam is independent of WebRTC peer graph and Coordinator lifecycle.
 */
class PcmInjectionPipelineHarness(
    private val targetModuleId: String,
    private val port: RecordingPcmInjectionPort = RecordingPcmInjectionPort(),
    private val mixer: AudioMixer = AudioMixer(rampFrames = 0),
    private val events: MutableList<PcmInjectionPortEvent> = mutableListOf()
) {
    private val sources = linkedMapOf<String, FakeRemotePcmSource>()
    private val session = PcmInjectionPortSession(
        targetModuleId = targetModuleId,
        port = port,
        onEvent = { events.add(it) }
    )

    val injectionPort: RecordingPcmInjectionPort get() = port
    val portSession: PcmInjectionPortSession get() = session
    val audioMixer: AudioMixer get() = mixer
    val recordedEvents: List<PcmInjectionPortEvent> get() = events

    fun admitTarget(format: ConferencePcmFormat = ConferencePcmFormat.CANONICAL): Result<Unit> =
        session.admit(format)

    fun admitSource(sourceId: String, level: Short = 4_000): FakeRemotePcmSource {
        val source = FakeRemotePcmSource(sourceId, level = level)
        source.admit()
        sources[sourceId] = source
        mixer.addSource(sourceId)
        return source
    }

    fun removeSource(sourceId: String) {
        sources.remove(sourceId)?.revoke()
        mixer.removeSource(sourceId)
    }

    fun renderAndInject(): Result<Unit> {
        val mixed = mixer.renderMixedFrame()
        return session.inject(mixed)
    }

    fun pushAndInject(sourceId: String): Result<Unit> {
        val source = sources[sourceId]
            ?: return Result.failure(IllegalArgumentException("unknown source: $sourceId"))
        if (!mixer.push(sourceId, source.produceFrame())) {
            return Result.failure(IllegalStateException("mixer push rejected: $sourceId"))
        }
        return renderAndInject()
    }

    fun revokeTarget(reason: PcmInjectionPortSession.RevokeReason = PcmInjectionPortSession.RevokeReason.EXPLICIT) {
        sources.values.forEach { it.revoke() }
        sources.clear()
        session.revoke(reason)
    }
}

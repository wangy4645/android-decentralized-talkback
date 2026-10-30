package com.talkback.core.webrtc.conferenceaudio

/**
 * Phase 1a-3 harness: fake sources → [ConferenceMediaBusSession] → per-target ports.
 */
class MediaBusSessionHarness(
    targetIds: List<String>,
    sourceIds: List<String>,
    includeLocalMic: Boolean = false
) {
    val events = mutableListOf<MediaBusEvent>()
    val failures = mutableListOf<MediaBusFailureFact>()
    private val sources = linkedMapOf<MediaEdgeId, FakeRemotePcmSource>()

    val session: ConferenceMediaBusSession = ConferenceMediaBusSession.create(
        spec = MediaBusSessionSpec(
            sessionId = "bus-harness",
            targetEdgeIds = targetIds.map { MediaEdgeId(it) },
            sourceEdgeIds = sourceIds.map { MediaEdgeId(it) },
            includeLocalMic = includeLocalMic
        ),
        onEvent = { events.add(it) },
        onFailure = { failures.add(it) }
    ).getOrThrow()

    fun fakeSource(edgeId: String, level: Short = 4_000): FakeRemotePcmSource {
        val id = MediaEdgeId(edgeId)
        val source = FakeRemotePcmSource(edgeId, level = level)
        source.admit()
        sources[id] = source
        return source
    }

    fun pushAndRender(fromEdgeId: String): Result<List<AudioMixerOutput>> {
        val id = MediaEdgeId(fromEdgeId)
        val source = sources[id]
            ?: return Result.failure(IllegalArgumentException("unknown fake source: $fromEdgeId"))
        return session.pushAndRender(id, source.produceFrame())
    }

    fun portWrittenFrames(targetEdgeId: String): List<PcmFrame> =
        session.targetPort(MediaEdgeId(targetEdgeId))?.writtenFrames.orEmpty()
}

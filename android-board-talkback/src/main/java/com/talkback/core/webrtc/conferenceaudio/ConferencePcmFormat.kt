package com.talkback.core.webrtc.conferenceaudio

/** ADR-0056 Phase 1a canonical PCM contract. */
data class ConferencePcmFormat(
    val sampleRateHz: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val frameDurationMs: Int
) {
    val samplesPerFrame: Int =
        sampleRateHz * frameDurationMs / 1000 * channels

    init {
        require(sampleRateHz > 0 && channels > 0 && bitsPerSample == 16 && frameDurationMs > 0)
        require(samplesPerFrame > 0)
    }

    companion object {
        val CANONICAL = ConferencePcmFormat(
            sampleRateHz = 48_000,
            channels = 1,
            bitsPerSample = 16,
            frameDurationMs = 10
        )
    }
}

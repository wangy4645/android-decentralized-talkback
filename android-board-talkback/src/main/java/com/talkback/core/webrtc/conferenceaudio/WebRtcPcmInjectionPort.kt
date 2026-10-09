package com.talkback.core.webrtc.conferenceaudio

import com.talkback.core.webrtc.ProgramRelayMode
import com.talkback.core.webrtc.WebRtcAudioEngine

/**
 * ADR-0056 Phase 1a-2 production adapter: [PcmInjectionPort] → [WebRtcAudioEngine.feedProgramPcm].
 */
class WebRtcPcmInjectionPort(
    private val engine: WebRtcAudioEngine,
    private val onFailure: ((PcmInjectionFailure) -> Unit)? = null
) : PcmInjectionPort {
    private var openedFormat: ConferencePcmFormat? = null
    private var closed = false

    override val isOpen: Boolean
        get() = openedFormat != null && !closed

    override fun open(format: ConferencePcmFormat): Result<Unit> {
        if (closed) {
            onFailure?.invoke(PcmInjectionFailure.CLOSED)
            return Result.failure(IllegalStateException("port closed"))
        }
        engine.setProgramRelayMode(ProgramRelayMode.PROGRAM)
        openedFormat = format
        return Result.success(Unit)
    }

    override fun write(frame: PcmFrame): Result<Unit> {
        if (closed) {
            onFailure?.invoke(PcmInjectionFailure.CLOSED)
            return Result.failure(IllegalStateException("port closed"))
        }
        val expected = openedFormat
        if (expected == null) {
            onFailure?.invoke(PcmInjectionFailure.NOT_OPEN)
            return Result.failure(IllegalStateException("port not open"))
        }
        if (frame.format != expected) {
            onFailure?.invoke(PcmInjectionFailure.FORMAT_MISMATCH)
            return Result.failure(IllegalArgumentException("format mismatch"))
        }
        return runCatching {
            val buffer = PcmFrameCodec.toByteBuffer(frame)
            engine.feedProgramPcm(
                buffer,
                bitsPerSample = 16,
                sampleRate = expected.sampleRateHz,
                numberOfChannels = expected.channels,
                numberOfFrames = frame.samples.size / expected.channels
            )
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = {
                onFailure?.invoke(PcmInjectionFailure.INJECT_FAILED)
                Result.failure(it)
            }
        )
    }

    override fun close() {
        closed = true
        openedFormat = null
    }
}

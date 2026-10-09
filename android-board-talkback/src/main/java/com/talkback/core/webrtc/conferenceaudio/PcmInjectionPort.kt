package com.talkback.core.webrtc.conferenceaudio

/**
 * ADR-0056 Phase 1a formal PCM injection boundary.
 * Implementations MUST NOT use per-frame reflection into libwebrtc internals.
 */
interface PcmInjectionPort {
    val isOpen: Boolean

    fun open(format: ConferencePcmFormat): Result<Unit>

    fun write(frame: PcmFrame): Result<Unit>

    fun close()
}

enum class PcmInjectionFailure {
    NOT_OPEN,
    CLOSED,
    FORMAT_MISMATCH,
    INJECT_FAILED
}

/**
 * Testable / in-process port that records writes and supports explicit failure injection.
 */
class RecordingPcmInjectionPort : PcmInjectionPort {
    private var openedFormat: ConferencePcmFormat? = null
    private var failNextWrite: PcmInjectionFailure? = null
    private var closed = false

    val writtenFrames = mutableListOf<PcmFrame>()
    val failures = mutableListOf<PcmInjectionFailure>()

    override val isOpen: Boolean
        get() = openedFormat != null && !closed

    override fun open(format: ConferencePcmFormat): Result<Unit> {
        if (closed) {
            recordFailure(PcmInjectionFailure.CLOSED)
            return Result.failure(IllegalStateException("port closed"))
        }
        openedFormat = format
        return Result.success(Unit)
    }

    override fun write(frame: PcmFrame): Result<Unit> {
        if (closed) {
            recordFailure(PcmInjectionFailure.CLOSED)
            return Result.failure(IllegalStateException("port closed"))
        }
        val expected = openedFormat
        if (expected == null) {
            recordFailure(PcmInjectionFailure.NOT_OPEN)
            return Result.failure(IllegalStateException("port not open"))
        }
        if (frame.format != expected) {
            recordFailure(PcmInjectionFailure.FORMAT_MISMATCH)
            return Result.failure(IllegalArgumentException("format mismatch"))
        }
        failNextWrite?.let { reason ->
            failNextWrite = null
            recordFailure(reason)
            return Result.failure(IllegalStateException(reason.name))
        }
        writtenFrames.add(PcmFrame(frame.samples.copyOf(), frame.format))
        return Result.success(Unit)
    }

    override fun close() {
        closed = true
        openedFormat = null
    }

    fun injectNextFailure(reason: PcmInjectionFailure) {
        failNextWrite = reason
    }

    private fun recordFailure(reason: PcmInjectionFailure) {
        failures.add(reason)
    }
}

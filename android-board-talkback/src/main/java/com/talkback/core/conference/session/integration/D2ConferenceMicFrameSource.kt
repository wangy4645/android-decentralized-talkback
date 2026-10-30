package com.talkback.core.conference.session.integration

import com.talkback.core.webrtc.LocalOutboundPcmSink
import com.talkback.core.webrtc.PostApmLocalMicFrameSource
import com.talkback.core.webrtc.ProcessLocalMicFrameSource
import com.talkback.core.webrtc.conferenceaudio.ConferencePcmFormat
import java.nio.ByteBuffer

/**
 * Debug-only mux: Post-APM when valid and fresh; else Pre-APM. All delivery serialized on [deliveryLock].
 */
object D2ConferenceMicFrameSource {
    private const val POST_APM_STALE_MS = 150L
    private val canonical = ConferencePcmFormat.CANONICAL

    fun acquireForSession(
        sessionId: String,
        sink: LocalOutboundPcmSink,
    ): () -> Unit {
        val deliveryLock = Any()
        val mux = MuxState()
        val downstream =
            LocalOutboundPcmSink { buffer, bits, rate, channels, frames ->
                synchronized(deliveryLock) {
                    sink.onPcm(buffer, bits, rate, channels, frames)
                }
            }
        val postRelease =
            PostApmLocalMicFrameSource.acquire(
                LocalOutboundPcmSink { buffer, bits, rate, channels, frames ->
                    synchronized(deliveryLock) {
                        if (!isCanonicalChunk(bits, rate, channels, frames)) {
                            D2TxPathTelemetry.record(sessionId, D2TxPathTelemetry.Event.POST_APM_FORMAT_REJECT)
                            return@synchronized
                        }
                        mux.noteValidPostApm()
                        if (mux.shouldPreferPostApm()) {
                            D2TxPathTelemetry.record(sessionId, D2TxPathTelemetry.Event.POST_APM_FORWARD)
                            downstream.onPcm(buffer, bits, rate, channels, frames)
                        }
                    }
                },
            )
        val preRelease =
            ProcessLocalMicFrameSource.acquire(
                LocalOutboundPcmSink { buffer, bits, rate, channels, frames ->
                    synchronized(deliveryLock) {
                        if (!isCanonicalChunk(bits, rate, channels, frames)) {
                            D2TxPathTelemetry.record(sessionId, D2TxPathTelemetry.Event.PRE_APM_FORMAT_REJECT)
                            return@synchronized
                        }
                        if (mux.shouldPreferPostApm()) {
                            D2TxPathTelemetry.record(sessionId, D2TxPathTelemetry.Event.SUPPRESSED_PRE_WHILE_POST_ACTIVE)
                            return@synchronized
                        }
                        D2TxPathTelemetry.record(sessionId, D2TxPathTelemetry.Event.PRE_APM_FORWARD)
                        downstream.onPcm(buffer, bits, rate, channels, frames)
                    }
                },
            )
        return {
            postRelease.invoke()
            preRelease.invoke()
            D2TxPathTelemetry.clearSession(sessionId)
        }
    }

    internal fun isCanonicalChunk(
        bitsPerSample: Int,
        sampleRate: Int,
        channels: Int,
        frames: Int,
    ): Boolean =
        bitsPerSample == canonical.bitsPerSample &&
            sampleRate == canonical.sampleRateHz &&
            channels == canonical.channels &&
            frames > 0

    private class MuxState {
        @Volatile
        private var lastValidPostApmMs: Long = 0L

        fun noteValidPostApm() {
            lastValidPostApmMs = System.currentTimeMillis()
        }

        fun shouldPreferPostApm(): Boolean {
            val last = lastValidPostApmMs
            if (last == 0L) return false
            return System.currentTimeMillis() - last <= POST_APM_STALE_MS
        }
    }
}

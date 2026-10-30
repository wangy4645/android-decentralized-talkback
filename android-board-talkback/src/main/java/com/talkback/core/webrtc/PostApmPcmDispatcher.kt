package com.talkback.core.webrtc

import android.media.AudioFormat
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.webrtc.TalkbackApmPcmProduction

/**
 * POST_SOFTWARE_APM_PRE_ENCODER PCM delivery to [LocalOutboundPcmSink] consumers.
 */
internal object PostApmPcmDispatcher {
    private val sinks = CopyOnWriteArrayList<LocalOutboundPcmSink>()
    private val sinkRefCount = AtomicInteger(0)
    private val observeRefCount = AtomicInteger(0)

    fun acquire(sink: LocalOutboundPcmSink): () -> Unit {
        sinks.add(sink)
        if (sinkRefCount.incrementAndGet() == 1) {
            updateNativeDeliveryEnabled()
        }
        return {
            sinks.remove(sink)
            if (sinkRefCount.decrementAndGet() == 0) {
                updateNativeDeliveryEnabled()
            }
        }
    }

    /** Observe-only: JNI delivery for APM_OUT window stats without a production sink. */
    fun enableObserveApmOutTap() {
        if (observeRefCount.incrementAndGet() == 1) {
            updateNativeDeliveryEnabled()
        }
    }

    fun disableObserveApmOutTap() {
        if (observeRefCount.get() <= 0) return
        if (observeRefCount.decrementAndGet() == 0) {
            updateNativeDeliveryEnabled()
        }
    }

    private fun updateNativeDeliveryEnabled() {
        val on = sinkRefCount.get() > 0 || observeRefCount.get() > 0
        TalkbackApmPcmProduction.setDeliveryEnabled(on)
    }

    @JvmStatic
    fun onNativePcm16Frame(
        audioData: ByteArray,
        sampleRateHz: Int,
        channels: Int,
        frames: Int,
    ) {
        onNativePcm16FrameAt(audioData, sampleRateHz, channels, frames, SystemClock.elapsedRealtime())
    }

    internal fun onNativePcm16FrameAt(
        audioData: ByteArray,
        sampleRateHz: Int,
        channels: Int,
        frames: Int,
        nowMs: Long,
    ) {
        if (sampleRateHz <= 0 || channels <= 0 || frames <= 0) return
        val bytesPerFrame = 2 * channels
        if (audioData.size < frames * bytesPerFrame) return
        val buffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN)
        if (CapturePcmLevelDiagnostic.enabled) {
            CapturePcmLevelDiagnostic.onPostApmPcm16Samples(
                buffer = buffer.duplicate(),
                bitsPerSample = 16,
                sampleRateHz = sampleRateHz,
                channels = channels,
                frames = frames,
                nowMs = nowMs,
            )
        }
        if (sinks.isEmpty()) return
        sinks.forEach { sink ->
            sink.onPcm(buffer.duplicate(), 16, sampleRateHz, channels, frames)
        }
    }

    /** Desk/harness injection when native delivery is unavailable (unit tests). */
    internal fun dispatchHarnessPcm(
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRateHz: Int,
        channels: Int,
        frames: Int,
    ) {
        if (bitsPerSample != AudioFormat.ENCODING_PCM_16BIT) return
        if (CapturePcmLevelDiagnostic.enabled) {
            CapturePcmLevelDiagnostic.onPostApmPcm16Samples(
                buffer = audioData.duplicate(),
                bitsPerSample = bitsPerSample,
                sampleRateHz = sampleRateHz,
                channels = channels,
                frames = frames,
            )
        }
        if (sinks.isEmpty()) return
        sinks.forEach { sink ->
            sink.onPcm(audioData.duplicate(), bitsPerSample, sampleRateHz, channels, frames)
        }
    }
}

package com.talkback.core.webrtc

import android.media.AudioFormat
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Knife 2 A/B coordinator: APM_IN (Java ADM callback) + APM_OUT (native post-ProcessStream tap).
 * Default off; does not alter capture, APM, or encoder behavior.
 * B-14: PRE_HW second AudioRecord is not started (B-13 observation side-effect INVALID).
 */
object CapturePcmAbDiagnostic {
    @Volatile
    var enabled: Boolean = false
        set(value) {
            field = value
            CapturePcmLevelDiagnostic.enabled = value
            runCatching { org.webrtc.TalkbackApmPcmDiagnostic.setEnabled(value) }
            if (value) {
                PostApmPcmDispatcher.enableObserveApmOutTap()
            } else {
                PostApmPcmDispatcher.disableObserveApmOutTap()
            }
            PreHwEffectPcmObservation.stop()
        }

    init {
        enabled = false
    }

    /** ADM SamplesReadyCallback hook; independent of [LocalOutboundPcmSink] consumers. */
    fun onAdmSamplesReady(samples: JavaAudioDeviceModule.AudioSamples) {
        onAdmSamplesReadyAt(samples, SystemClock.elapsedRealtime())
    }

    internal fun onAdmSamplesReadyAt(
        samples: JavaAudioDeviceModule.AudioSamples,
        nowMs: Long,
    ) {
        if (!enabled) return
        if (samples.audioFormat != AudioFormat.ENCODING_PCM_16BIT) return
        val channels = samples.channelCount
        if (channels <= 0) return
        val bytesPerFrame = 2 * channels
        if (bytesPerFrame <= 0 || samples.data.size < bytesPerFrame) return
        val frames = samples.data.size / bytesPerFrame
        val buffer = ByteBuffer.wrap(samples.data).order(ByteOrder.LITTLE_ENDIAN)
        CapturePcmLevelDiagnostic.onAdmPcm16Samples(
            buffer = buffer,
            bitsPerSample = 16,
            sampleRateHz = samples.sampleRate,
            channels = channels,
            frames = frames,
            nowMs = nowMs,
        )
    }

    fun reset() {
        CapturePcmLevelDiagnostic.reset()
        if (enabled) {
            runCatching { org.webrtc.TalkbackApmPcmDiagnostic.setEnabled(false) }
            runCatching { org.webrtc.TalkbackApmPcmDiagnostic.setEnabled(true) }
        }
    }
}

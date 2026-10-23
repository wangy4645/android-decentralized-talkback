package com.talkback.core.webrtc

import android.os.SystemClock
import com.talkback.core.util.TalkbackLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * A11-1 desk diagnostic: windowed PCM level stats at [CapturePcmMeasurementPoint.POST_HW_PRE_WEBRTC_APM].
 * Default off; does not alter capture, APM, or encoder behavior.
 */
object CapturePcmLevelDiagnostic {
    @Volatile
    var enabled: Boolean = false

    @Volatile
    var testLogSink: ((String) -> Unit)? = null

    /** D2 observe: mirror APM window lines to field log sink (e.g. MEETING_MEDIA_SHADOW). */
    @Volatile
    var fieldObserveLineSink: ((String) -> Unit)? = null

    private const val TAG = "A12_APM_AB"
    private const val WINDOW_MS = 500L
    private const val IN_POINT = "APM_IN"
    private const val OUT_POINT = "APM_OUT"
    private const val PRE_POINT = "PRE_HW"

    private val inAccumulatorRef = AtomicReference<Pcm16LevelWindowAccumulator?>()
    private val outAccumulatorRef = AtomicReference<Pcm16LevelWindowAccumulator?>()
    private val preAccumulatorRef = AtomicReference<Pcm16LevelWindowAccumulator?>()

    fun reset() {
        inAccumulatorRef.set(null)
        outAccumulatorRef.set(null)
        preAccumulatorRef.set(null)
        testLogSink = null
    }

    fun onAdmPcm16Samples(
        buffer: ByteBuffer,
        bitsPerSample: Int,
        sampleRateHz: Int,
        channels: Int,
        frames: Int,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ) {
        if (!enabled) return
        if (bitsPerSample != 16 || channels <= 0 || frames <= 0) return
        val interleavedSamples = frames * channels
        val bytesNeeded = interleavedSamples * 2
        if (buffer.remaining() < bytesNeeded) return

        val accumulator = inAccumulatorRef.updateAndGet { existing ->
            if (existing == null || existing.expectedSamplesPerWindow(channels) <= 0) {
                Pcm16LevelWindowAccumulator(WINDOW_MS, sampleRateHz)
            } else {
                existing
            }
        } ?: return

        val duplicate = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val pcm = ShortArray(interleavedSamples)
        duplicate.asShortBuffer().get(pcm)

        val snapshot = accumulator.onFrame(nowMs, pcm, 0, interleavedSamples) ?: return
        logWindow(IN_POINT, CapturePcmMeasurementPoint.POST_HW_PRE_WEBRTC_APM, sampleRateHz, channels, snapshot)
    }

    /** B-10 — same windowing as APM_IN, on POST_SOFTWARE_APM_PRE_ENCODER delivery. */
    fun onPostApmPcm16Samples(
        buffer: ByteBuffer,
        bitsPerSample: Int,
        sampleRateHz: Int,
        channels: Int,
        frames: Int,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ) {
        if (!enabled) return
        if (bitsPerSample != 16 || channels <= 0 || frames <= 0) return
        val interleavedSamples = frames * channels
        val bytesNeeded = interleavedSamples * 2
        if (buffer.remaining() < bytesNeeded) return

        val accumulator = outAccumulatorRef.updateAndGet { existing ->
            if (existing == null || existing.expectedSamplesPerWindow(channels) <= 0) {
                Pcm16LevelWindowAccumulator(WINDOW_MS, sampleRateHz)
            } else {
                existing
            }
        } ?: return

        val duplicate = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val pcm = ShortArray(interleavedSamples)
        duplicate.asShortBuffer().get(pcm)

        val snapshot = accumulator.onFrame(nowMs, pcm, 0, interleavedSamples) ?: return
        logWindow(OUT_POINT, CapturePcmMeasurementPoint.POST_SOFTWARE_APM_PRE_ENCODER, sampleRateHz, channels, snapshot)
    }

    /** B-13 — parallel capture without Java HW AEC/NS; does not feed TX. */
    fun onPreHwPcm16Samples(
        buffer: ByteBuffer,
        bitsPerSample: Int,
        sampleRateHz: Int,
        channels: Int,
        frames: Int,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ) {
        if (!enabled) return
        if (bitsPerSample != 16 || channels <= 0 || frames <= 0) return
        val interleavedSamples = frames * channels
        val bytesNeeded = interleavedSamples * 2
        if (buffer.remaining() < bytesNeeded) return

        val accumulator = preAccumulatorRef.updateAndGet { existing ->
            if (existing == null || existing.expectedSamplesPerWindow(channels) <= 0) {
                Pcm16LevelWindowAccumulator(WINDOW_MS, sampleRateHz)
            } else {
                existing
            }
        } ?: return

        val duplicate = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val pcm = ShortArray(interleavedSamples)
        duplicate.asShortBuffer().get(pcm)

        val snapshot = accumulator.onFrame(nowMs, pcm, 0, interleavedSamples) ?: return
        logWindow(PRE_POINT, CapturePcmMeasurementPoint.PRE_JAVA_HW_EFFECT, sampleRateHz, channels, snapshot)
    }

    private fun logWindow(
        point: String,
        measurement: CapturePcmMeasurementPoint,
        sampleRateHz: Int,
        channels: Int,
        snapshot: Pcm16LevelWindowSnapshot,
    ) {
        val line =
            "$TAG point=$point measurement=$measurement " +
                "windowMs=$WINDOW_MS windowEndMs=${snapshot.windowEndMs} sampleRate=$sampleRateHz channels=$channels " +
                "samples=${snapshot.samples} " +
                "rms=${formatLinear(snapshot.rmsLinear)} rmsDbFS=${formatDbFs(snapshot.rmsDbFs)} " +
                "peak=${snapshot.peakLinear} peakDbFS=${formatDbFs(snapshot.peakDbFs)}"
        val sink = testLogSink
        if (sink != null) {
            sink.invoke(line)
            return
        }
        val fieldSink = fieldObserveLineSink
        if (fieldSink != null) {
            fieldSink.invoke(line)
            return
        }
        TalkbackLog.i(line)
    }

    private fun formatLinear(value: Double): String = "%.6f".format(value)

    private fun formatDbFs(value: Double): String =
        if (value.isInfinite() && value < 0) "-inf" else "%.2f".format(value)
}

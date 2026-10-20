package com.talkback.core.webrtc

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import com.talkback.core.util.TalkbackLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * B-13 observe-only: second AudioRecord on VOICE_COMMUNICATION **without** Java AEC/NS.
 * Does not feed TX / VAD / encode. Production ADM path is unchanged.
 */
object PreHwEffectPcmObservation {
    private const val SAMPLE_RATE_HZ = 48_000
    private const val CHANNELS = 1
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private const val FRAME_SAMPLES = 480

    private val running = AtomicBoolean(false)

    @Volatile
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val t =
            Thread(
                {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    runLoop()
                },
                "b13-pre-hw",
            )
        worker = t
        t.start()
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
    }

    private fun runLoop() {
        var record: AudioRecord? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, ENCODING)
            if (minBuf <= 0) {
                TalkbackLog.w("A12_APM_AB point=PRE_HW fail=minBuffer minBuf=$minBuf")
                return
            }
            val bytesPerFrame = FRAME_SAMPLES * CHANNELS * 2
            val bufSize = maxOf(minBuf, bytesPerFrame * 4)
            record =
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE_HZ,
                    CHANNEL_CONFIG,
                    ENCODING,
                    bufSize,
                )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                TalkbackLog.w("A12_APM_AB point=PRE_HW fail=not_initialized")
                return
            }
            record.startRecording()
            TalkbackLog.i(
                "A12_APM_AB point=PRE_HW started source=VOICE_COMMUNICATION javaFx=none " +
                    "session=${record.audioSessionId} sampleRate=$SAMPLE_RATE_HZ",
            )
            val byteBuf = ByteArray(bytesPerFrame)
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val n = record.read(byteBuf, 0, byteBuf.size)
                if (n != byteBuf.size) continue
                CapturePcmLevelDiagnostic.onPreHwPcm16Samples(
                    buffer = ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN),
                    bitsPerSample = 16,
                    sampleRateHz = SAMPLE_RATE_HZ,
                    channels = CHANNELS,
                    frames = FRAME_SAMPLES,
                    nowMs = SystemClock.elapsedRealtime(),
                )
            }
        } catch (t: Throwable) {
            TalkbackLog.w("A12_APM_AB point=PRE_HW fail=${t.javaClass.simpleName} ${t.message}")
        } finally {
            running.set(false)
            runCatching { record?.stop() }
            runCatching { record?.release() }
            TalkbackLog.i("A12_APM_AB point=PRE_HW stopped")
        }
    }
}
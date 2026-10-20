package com.talkback.core.conference.runtime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.talkback.core.conference.session.D1EmptyTickPlayoutFill
import com.talkback.core.conference.session.integration.Profile01ShadowRuntimeObservability
import com.talkback.core.conference.session.integration.cutover.ReplacementCutoverObservability

/**
 * Product C-IG-01 AudioTrack playout seam (Phase 1 Slice 3).
 *
 * Mono PCM s16 @ 48 kHz. Does not wire Meeting UI / ADR-0056.
 *
 * Usage is VOICE_COMMUNICATION so the track follows STREAM_VOICE_CALL volume (same as the
 * WebRTC ADM playout) and is not attenuated by OEM policy as a background "media" track
 * while the app is in MODE_IN_COMMUNICATION.
 */
class AndroidAudioTrackPlayoutSeam(
    context: Context,
    private val streamType: Int = AudioAttributes.USAGE_VOICE_COMMUNICATION,
) : AudioTrackPlayoutSeam,
    PlayoutMetricsSeam {
    private val appContext = context.applicationContext
    private var track: AudioTrack? = null

    override var successfulWrites: Long = 0
        private set
    override var failedWrites: Long = 0
        private set
    override var underrunCount: Long = 0
        private set
    override var lastWriteDurationUs: Long = 0
        private set

    private var lastReportedUnderrunCount: Int = 0
    private val emptyTickFillState = D1EmptyTickPlayoutFill.State()
    private val digitalSilenceFrame = ShortArray(D1EmptyTickPlayoutFill.silenceFrameSamples)

    fun start() {
        if (track != null) return
        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufferBytes =
            AudioTrack.getMinBufferSize(
                OpusCodecConstants.SAMPLE_RATE_HZ,
                channelMask,
                encoding,
            ).coerceAtLeast(OpusCodecConstants.FRAME_SAMPLES_20MS * 2 * 4)
        val format =
            AudioFormat.Builder()
                .setSampleRate(OpusCodecConstants.SAMPLE_RATE_HZ)
                .setEncoding(encoding)
                .setChannelMask(channelMask)
                .build()
        val attributes =
            AudioAttributes.Builder()
                .setUsage(streamType)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        track =
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBufferBytes)
                .build()
        track?.play()
        refreshUnderrunCount()
        Profile01ShadowRuntimeObservability.logProductionAudioTrackFence("PRODUCTION_ACTIVE")
        ReplacementCutoverObservability.logMulticastAudibleProbe(
            sessionId = "-",
            active = true,
            successfulWrites = successfulWrites,
        )
    }

    fun stop() {
        val current = track ?: return
        runCatching {
            if (current.playState == AudioTrack.PLAYSTATE_PLAYING) {
                current.stop()
            }
            current.flush()
            current.release()
        }
        track = null
        D1EmptyTickPlayoutFill.reset(emptyTickFillState)
        ReplacementCutoverObservability.logMulticastAudibleProbe(
            sessionId = "-",
            active = false,
            successfulWrites = successfulWrites,
        )
    }

    override fun write(block: MixedBlock, nowMs: Long): Boolean {
        val current = track ?: return false
        val action = D1EmptyTickPlayoutFill.decide(block, emptyTickFillState)
        if (action == D1EmptyTickPlayoutFill.PlayoutWriteAction.SKIP) {
            if (block.samples.isEmpty()) {
                failedWrites += 1
            }
            return false
        }
        val pcm =
            when (action) {
                D1EmptyTickPlayoutFill.PlayoutWriteAction.WRITE_MIXED_BLOCK -> block.samples
                D1EmptyTickPlayoutFill.PlayoutWriteAction.WRITE_DIGITAL_SILENCE -> digitalSilenceFrame
                D1EmptyTickPlayoutFill.PlayoutWriteAction.SKIP -> return false
            }
        val startNs = System.nanoTime()
        val written =
            current.write(
                pcm,
                0,
                pcm.size,
                AudioTrack.WRITE_BLOCKING,
            )
        lastWriteDurationUs = (System.nanoTime() - startNs) / 1_000L
        refreshUnderrunCount()
        return if (written > 0) {
            D1EmptyTickPlayoutFill.onSuccessfulWrite(action, emptyTickFillState)
            successfulWrites += 1
            true
        } else {
            failedWrites += 1
            false
        }
    }

    private fun refreshUnderrunCount() {
        val current = track ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val reported = current.underrunCount
        if (reported > lastReportedUnderrunCount) {
            underrunCount += (reported - lastReportedUnderrunCount).toLong()
            lastReportedUnderrunCount = reported
        }
    }

    companion object {
        fun from(context: Context): AndroidAudioTrackPlayoutSeam =
            AndroidAudioTrackPlayoutSeam(context)
    }
}

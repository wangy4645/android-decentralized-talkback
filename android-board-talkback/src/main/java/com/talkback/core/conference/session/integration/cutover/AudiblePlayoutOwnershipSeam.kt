package com.talkback.core.conference.session.integration.cutover

import android.content.Context
import com.talkback.core.conference.runtime.AndroidAudioTrackPlayoutSeam
import com.talkback.core.conference.runtime.AudioTrackPlayoutSeam
import com.talkback.core.conference.runtime.MixedBlock
import com.talkback.core.conference.runtime.PlayoutMetricsSeam
import com.talkback.core.conference.transport.RecordingPlayoutMetricsSeam

/**
 * Resource-level playout ownership for RC1.
 * Shadow metrics and production AudioTrack are mutually exclusive audible outputs.
 */
class AudiblePlayoutOwnershipSeam(
    context: Context,
    private val sessionId: String,
) : AudioTrackPlayoutSeam,
    PlayoutMetricsSeam {
    private val appContext = context.applicationContext
    private val metricsSeam = RecordingPlayoutMetricsSeam()
    private var productionSeam: AndroidAudioTrackPlayoutSeam? = null

    enum class PlayoutMode {
        SHADOW_METRICS_ONLY,
        FENCED,
        MULTICAST_PRODUCTION,
    }

    @Volatile
    var mode: PlayoutMode = PlayoutMode.SHADOW_METRICS_ONLY
        private set

    fun fenceProductionPlayout() {
        productionSeam?.stop()
        productionSeam = null
        mode = PlayoutMode.FENCED
        ReplacementCutoverObservability.logHandoff(
            sessionId = sessionId,
            phase = "FENCE_MULTICAST_PRODUCTION",
            outcome = CutoverOutcome.APPLIED,
        )
        ReplacementCutoverObservability.logMulticastAudibleProbe(
            sessionId = sessionId,
            active = false,
            successfulWrites = successfulWrites,
        )
    }

    fun acquireProductionAudioTrack(): Boolean {
        if (productionSeam != null) {
            return mode == PlayoutMode.MULTICAST_PRODUCTION
        }
        val seam = AndroidAudioTrackPlayoutSeam.from(appContext)
        return try {
            seam.start()
            productionSeam = seam
            mode = PlayoutMode.MULTICAST_PRODUCTION
            ReplacementCutoverObservability.logMulticastAudibleProbe(
                sessionId = sessionId,
                active = true,
                successfulWrites = successfulWrites,
            )
            true
        } catch (t: Throwable) {
            seam.stop()
            false
        }
    }

    fun releaseProductionAudioTrack() {
        productionSeam?.stop()
        productionSeam = null
        mode = PlayoutMode.SHADOW_METRICS_ONLY
        ReplacementCutoverObservability.logMulticastAudibleProbe(
            sessionId = sessionId,
            active = false,
            successfulWrites = successfulWrites,
        )
    }

    fun isProductionAudioTrackActive(): Boolean =
        mode == PlayoutMode.MULTICAST_PRODUCTION && productionSeam != null

    override var successfulWrites: Long
        get() =
            when (mode) {
                PlayoutMode.MULTICAST_PRODUCTION -> productionSeam?.successfulWrites ?: 0L
                else -> metricsSeam.successfulWrites
            }
        private set(_) = Unit

    override var failedWrites: Long
        get() =
            when (mode) {
                PlayoutMode.MULTICAST_PRODUCTION -> productionSeam?.failedWrites ?: 0L
                else -> metricsSeam.failedWrites
            }
        private set(_) = Unit

    override var underrunCount: Long
        get() =
            when (mode) {
                PlayoutMode.MULTICAST_PRODUCTION -> productionSeam?.underrunCount ?: 0L
                else -> metricsSeam.underrunCount
            }
        private set(_) = Unit

    override var lastWriteDurationUs: Long
        get() =
            when (mode) {
                PlayoutMode.MULTICAST_PRODUCTION -> productionSeam?.lastWriteDurationUs ?: 0L
                else -> metricsSeam.lastWriteDurationUs
            }
        private set(_) = Unit

    override fun write(
        block: MixedBlock,
        nowMs: Long,
    ): Boolean =
        when (mode) {
            PlayoutMode.SHADOW_METRICS_ONLY -> metricsSeam.write(block, nowMs)
            PlayoutMode.FENCED -> {
                metricsSeam.write(block, nowMs)
                false
            }
            PlayoutMode.MULTICAST_PRODUCTION ->
                productionSeam?.write(block, nowMs) ?: false
        }
}

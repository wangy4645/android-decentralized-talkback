package com.talkback.core.conference.transport

import android.content.Context
import com.talkback.core.conference.runtime.AndroidAudioTrackPlayoutSeam
import com.talkback.core.conference.runtime.AndroidRuntimeResources
import com.talkback.core.conference.runtime.FakeMulticastLockSeam
import com.talkback.core.conference.runtime.MulticastSocketTransportRebindSeam
import com.talkback.core.conference.runtime.AudioTrackPlayoutSeam
import com.talkback.core.conference.runtime.ConcentusOpusDecoderSeam
import com.talkback.core.conference.runtime.ConferenceMediaExecutionOrchestrator
import com.talkback.core.conference.runtime.OpusPayloadStore
import com.talkback.core.conference.runtime.PlayoutMetricsSeam
import com.talkback.core.conference.runtime.RecordingAudioTrackSeam
import com.talkback.core.conference.session.integration.Profile01ShadowRuntimeObservability
import com.talkback.core.conference.session.integration.cutover.AudiblePlayoutOwnershipSeam

/**
 * Phase 1 Slice 3 product assembly: real Opus decode + real AudioTrack playout.
 */
data class ConferenceMulticastRealMediaAssembly(
    val pipeline: ConferenceMulticastMediaPipeline,
    val opusPayloadStore: OpusPayloadStore,
    val decoderSeam: ConcentusOpusDecoderSeam,
    val playoutSeam: AudioTrackPlayoutSeam,
    val playoutMetrics: PlayoutMetricsSeam,
    val orchestrator: ConferenceMediaExecutionOrchestrator,
) {
    fun startPlayout() {
        when (val seam = playoutSeam) {
            is AndroidAudioTrackPlayoutSeam -> seam.start()
            is AudiblePlayoutOwnershipSeam -> Unit // RC1: production AudioTrack only via ownership handoff
        }
    }

    fun stopPlayout() {
        when (val seam = playoutSeam) {
            is AndroidAudioTrackPlayoutSeam -> seam.stop()
            is AudiblePlayoutOwnershipSeam -> seam.releaseProductionAudioTrack()
        }
    }

    companion object {
        fun create(context: Context): ConferenceMulticastRealMediaAssembly {
            val opusPayloadStore = OpusPayloadStore()
            val decoderSeam = ConcentusOpusDecoderSeam(opusPayloadStore)
            val playoutSeam = AndroidAudioTrackPlayoutSeam.from(context)
            return build(
                opusPayloadStore = opusPayloadStore,
                decoderSeam = decoderSeam,
                playoutSeam = playoutSeam,
                playoutMetrics = playoutSeam,
                resources = AndroidRuntimeResources.forAndroidProduct(context),
            )
        }

        fun createHarness(): ConferenceMulticastRealMediaAssembly {
            val opusPayloadStore = OpusPayloadStore()
            val decoderSeam = ConcentusOpusDecoderSeam(opusPayloadStore)
            val playoutSeam = RecordingPlayoutMetricsSeam()
            return build(
                opusPayloadStore = opusPayloadStore,
                decoderSeam = decoderSeam,
                playoutSeam = playoutSeam,
                playoutMetrics = playoutSeam,
                resources =
                    AndroidRuntimeResources(
                        multicastLock = FakeMulticastLockSeam(),
                        rebindSeam = MulticastSocketTransportRebindSeam(),
                    ),
            )
        }

        /**
         * Phase A shadow — real product multicast resources, metrics-only playout (no AudioTrack).
         * Does not compete with ADR-0056 user-hearable output.
         */
        fun createShadow(context: Context, sessionId: String): ConferenceMulticastRealMediaAssembly {
            val opusPayloadStore = OpusPayloadStore()
            val decoderSeam = ConcentusOpusDecoderSeam(opusPayloadStore)
            val playoutSeam = AudiblePlayoutOwnershipSeam(context, sessionId)
            return build(
                opusPayloadStore = opusPayloadStore,
                decoderSeam = decoderSeam,
                playoutSeam = playoutSeam,
                playoutMetrics = playoutSeam,
                resources = AndroidRuntimeResources.forAndroidProduct(context),
            )
        }

        private fun build(
            opusPayloadStore: OpusPayloadStore,
            decoderSeam: ConcentusOpusDecoderSeam,
            playoutSeam: AudioTrackPlayoutSeam,
            playoutMetrics: PlayoutMetricsSeam,
            resources: AndroidRuntimeResources,
        ): ConferenceMulticastRealMediaAssembly {
            val orchestrator = ConferenceMediaExecutionOrchestrator(playoutSeam = playoutSeam)
            orchestrator.setDecodeSeam(decoderSeam)
            val transport =
                ConferenceMulticastRtpSrtpTransport(
                    resources = resources,
                    wiring = null,
                )
            val pipeline =
                ConferenceMulticastMediaPipeline(
                    orchestrator = orchestrator,
                    transport = transport,
                    opusPayloadStore = opusPayloadStore,
                    playoutMetrics = playoutMetrics,
                )
            return ConferenceMulticastRealMediaAssembly(
                pipeline = pipeline,
                opusPayloadStore = opusPayloadStore,
                decoderSeam = decoderSeam,
                playoutSeam = playoutSeam,
                playoutMetrics = playoutMetrics,
                orchestrator = orchestrator,
            )
        }
    }
}

/** JVM harness playout stand-in that still exposes [PlayoutMetricsSeam]. */
class RecordingPlayoutMetricsSeam :
    AudioTrackPlayoutSeam,
    PlayoutMetricsSeam {
    private val delegate = RecordingAudioTrackSeam()

    override var successfulWrites: Long = 0
        private set
    override var failedWrites: Long = 0
        private set
    override var underrunCount: Long = 0
        private set
    override var lastWriteDurationUs: Long = 0
        private set

    var failKind: com.talkback.core.conference.runtime.RuntimeDegradationKind?
        get() = delegate.failKind
        set(value) {
            delegate.failKind = value
        }

    override fun write(
        block: com.talkback.core.conference.runtime.MixedBlock,
        nowMs: Long,
    ): Boolean {
        val startNs = System.nanoTime()
        val ok = delegate.write(block, nowMs)
        lastWriteDurationUs = (System.nanoTime() - startNs) / 1_000L
        if (ok) {
            successfulWrites += 1
            Profile01ShadowRuntimeObservability.maybeLogPlayoutActivity(successfulWrites)
        } else {
            failedWrites += 1
        }
        return ok
    }
}

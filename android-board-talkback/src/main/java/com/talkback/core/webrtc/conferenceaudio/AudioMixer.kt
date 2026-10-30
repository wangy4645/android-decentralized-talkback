package com.talkback.core.webrtc.conferenceaudio

/**
 * ADR-0056 Phase 1a MCU-lite mixer.
 * Owns 10 ms render clock; [push] is ingress-only.
 */
class AudioMixer(
    private val format: ConferencePcmFormat = ConferencePcmFormat.CANONICAL,
    private val rampFrames: Int = 3,
    private val silenceThreshold: Int = 64,
    private val mixHeadroomDb: Float = 6f
) {
    enum class SourceState { ADDING, ACTIVE, REMOVING, REMOVED }

    data class MixerSourceConfig(val gain: Float = 1f)

    data class Stats(
        val mixTicks: Long = 0,
        val underruns: Long = 0,
        val clipSamples: Long = 0,
        val limiterActiveTicks: Long = 0,
        val silentSourceSkips: Long = 0
    )

    private data class SourceSlot(
        var state: SourceState,
        var rampStep: Int,
        val config: MixerSourceConfig,
        var pendingFrame: ShortArray? = null,
        var frameReady: Boolean = false
    )

    private val sources = LinkedHashMap<String, SourceSlot>()
    private var stats = Stats()

    val currentStats: Stats get() = stats

    /** Observation only: sources that contributed on the last [renderMixedFrame]. */
    @Volatile
    var lastContributingSourceIds: Set<String> = emptySet()
        private set

    fun configuredSourceCount(): Int =
        sources.count { it.value.state != SourceState.REMOVED }

    fun addSource(sourceId: String, config: MixerSourceConfig = MixerSourceConfig()) {
        sources[sourceId] = SourceSlot(SourceState.ADDING, rampStep = 0, config = config)
    }

    fun removeSource(sourceId: String) {
        val slot = sources[sourceId] ?: return
        if (slot.state == SourceState.REMOVED) return
        slot.state = SourceState.REMOVING
        slot.rampStep = 0
    }

    fun sourceState(sourceId: String): SourceState? = sources[sourceId]?.state

    /** Non-blocking ingress. Returns false if source unknown or wrong frame size. */
    fun push(sourceId: String, frame: PcmFrame): Boolean {
        if (frame.format != format) return false
        val slot = sources[sourceId] ?: return false
        if (slot.state == SourceState.REMOVED) return false
        slot.pendingFrame = frame.samples.copyOf()
        slot.frameReady = true
        return true
    }

    /** Mixer-owned render tick — output period independent of which source pushed first. */
    fun renderMixedFrame(): PcmFrame {
        val mixing = sources.values.filter {
            it.state == SourceState.ADDING ||
                it.state == SourceState.ACTIVE ||
                it.state == SourceState.REMOVING
        }
        val activeContributors = mixing.count { slot ->
            slot.frameReady && !isSilent(slot.pendingFrame)
        }
        val headroomScale = headroomScale(activeContributors)
        val out = FloatArray(format.samplesPerFrame)
        var limiterActive = false
        var clips = 0L

        val contributing = linkedSetOf<String>()
        for ((sourceId, slot) in sources) {
            when (slot.state) {
                SourceState.REMOVING -> {
                    advanceRamp(slot)
                    if (slot.rampStep >= rampFrames) {
                        slot.state = SourceState.REMOVED
                        continue
                    }
                }
                SourceState.ADDING -> {
                    advanceRamp(slot)
                    if (slot.rampStep >= rampFrames) {
                        slot.state = SourceState.ACTIVE
                    }
                }
                SourceState.REMOVED -> continue
                SourceState.ACTIVE -> Unit
            }
            if (slot.state == SourceState.REMOVED) continue

            val rampGain = rampGain(slot)
            if (rampGain <= 0f) {
                slot.frameReady = false
                continue
            }

            val samples = if (slot.frameReady) {
                slot.pendingFrame
            } else {
                stats = stats.copy(underruns = stats.underruns + 1)
                null
            }
            slot.frameReady = false

            if (samples == null) {
                continue
            }
            if (isSilent(samples)) {
                stats = stats.copy(silentSourceSkips = stats.silentSourceSkips + 1)
                continue
            }

            val effectiveGain = slot.config.gain * rampGain * headroomScale
            for (i in out.indices) {
                out[i] += samples[i].toFloat() * effectiveGain
            }
            contributing.add(sourceId)
        }

        lastContributingSourceIds = contributing
        sources.entries.removeAll { it.value.state == SourceState.REMOVED }

        val pcm = ShortArray(format.samplesPerFrame)
        for (i in pcm.indices) {
            val limited = softLimit(out[i])
            if (limited.wasLimited) {
                limiterActive = true
                clips++
            }
            pcm[i] = limited.value
        }

        stats = stats.copy(
            mixTicks = stats.mixTicks + 1,
            clipSamples = stats.clipSamples + clips,
            limiterActiveTicks = stats.limiterActiveTicks + if (limiterActive) 1 else 0
        )
        return PcmFrame(pcm, format)
    }

    private fun advanceRamp(slot: SourceSlot) {
        if (rampFrames <= 0) return
        slot.rampStep = (slot.rampStep + 1).coerceAtMost(rampFrames)
    }

    private fun rampGain(slot: SourceSlot): Float {
        if (rampFrames <= 0) return 1f
        val t = slot.rampStep.toFloat() / rampFrames.toFloat()
        return when (slot.state) {
            SourceState.ADDING -> t.coerceIn(0f, 1f)
            SourceState.ACTIVE -> 1f
            SourceState.REMOVING -> (1f - t).coerceIn(0f, 1f)
            SourceState.REMOVED -> 0f
        }
    }

    private fun isSilent(samples: ShortArray?): Boolean {
        if (samples == null) return true
        for (s in samples) {
            if (kotlin.math.abs(s.toInt()) > silenceThreshold) return false
        }
        return true
    }

    private fun headroomScale(activeContributors: Int): Float {
        if (activeContributors <= 0) return 1f
        val linear = Math.pow(10.0, -(mixHeadroomDb / 20.0)).toFloat()
        return linear / activeContributors.coerceAtLeast(1)
    }

    private data class LimitedSample(val value: Short, val wasLimited: Boolean)

    private fun softLimit(sample: Float): LimitedSample {
        val ceiling = 32_640f
        if (sample > ceiling) return LimitedSample(32_767, wasLimited = true)
        if (sample < -ceiling) return LimitedSample(-32_768, wasLimited = true)
        return LimitedSample(sample.toInt().toShort(), wasLimited = false)
    }
}

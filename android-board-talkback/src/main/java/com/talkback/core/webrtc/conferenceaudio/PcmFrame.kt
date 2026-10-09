package com.talkback.core.webrtc.conferenceaudio

/** One mixed or per-source PCM16 frame. */
data class PcmFrame(
    val samples: ShortArray,
    val format: ConferencePcmFormat = ConferencePcmFormat.CANONICAL
) {
    init {
        require(samples.size == format.samplesPerFrame) {
            "expected ${format.samplesPerFrame} samples, got ${samples.size}"
        }
    }

    fun peakAbs(): Int = samples.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0

    fun rms(): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) {
            val v = s.toDouble()
            sum += v * v
        }
        return kotlin.math.sqrt(sum / samples.size)
    }

    companion object {
        fun silence(format: ConferencePcmFormat = ConferencePcmFormat.CANONICAL): PcmFrame =
            PcmFrame(ShortArray(format.samplesPerFrame), format)

        /** Test helper: constant-amplitude sine-free square-ish tone for determinism. */
        fun constantLevel(level: Short, format: ConferencePcmFormat = ConferencePcmFormat.CANONICAL): PcmFrame =
            PcmFrame(ShortArray(format.samplesPerFrame) { level }, format)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcmFrame) return false
        return format == other.format && samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int = 31 * format.hashCode() + samples.contentHashCode()
}

package com.talkback.core.conference.runtime

/**
 * Profile 03 Q4/Q6/Q7 constants (E2b-04).
 */
object MediaMixConstants {
    const val DECODER_WARMUP_MS: Long = 40L
    const val MAX_LIVE_DECODERS: Int = 5
    const val MIX_MAX_SOURCES: Int = 4
    const val SAMPLE_RATE_HZ: Int = 48_000
    const val OUTPUT_PEAK_ABS_FS: Double = 0.89
    const val S16_FULL_SCALE: Int = 32767
}

/** Contract-faithful PCM frame (mono s16 @ 48 kHz). Not an Opus quality golden. */
data class PcmFrame(
    val samples: ShortArray,
    /** When false, warm-up transition may continue until DecoderWarmupMs. */
    val usableForMix: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcmFrame) return false
        return usableForMix == other.usableForMix && samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int = 31 * samples.contentHashCode() + usableForMix.hashCode()
}

data class MixedBlock(
    val samples: ShortArray,
    val mixParticipantCount: Int,
    val peakAbsFs: Double,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MixedBlock) return false
        return mixParticipantCount == other.mixParticipantCount &&
            peakAbsFs == other.peakAbsFs &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int =
        31 * (31 * samples.contentHashCode() + mixParticipantCount) + peakAbsFs.hashCode()
}

/**
 * C-E2B04-05: decoder execution boundary. Harness may inject deterministic PCM
 * but MUST NOT fake Top-K / allocation / fence / eligibility.
 */
fun interface OpusDecodeSeam {
    fun decode(
        sourceIdentity: String,
        incarnationId: Long,
        mediaSlot: Long,
        nowMs: Long,
    ): PcmFrame?
}

enum class DecoderRole {
    /** Actively serving a mix-eligible Top-K Source. */
    ACTIVE_MIX,

    /** Bounded warm-up / warm-down transition only (may be decoder #5). */
    TRANSITION,
}

data class LiveDecoderSlot(
    val sourceIdentity: String,
    val incarnationId: Long,
    val role: DecoderRole,
    val allocatedAtMs: Long,
    val warmupDeadlineMs: Long,
)

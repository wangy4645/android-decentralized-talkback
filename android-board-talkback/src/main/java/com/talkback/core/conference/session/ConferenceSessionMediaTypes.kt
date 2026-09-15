package com.talkback.core.conference.session

import com.talkback.core.conference.runtime.MediaGroupEndpointBinding

/**
 * Authoritative session-scoped media facts (control plane → wiring).
 * Cryptographic verification is upstream; wiring consumes verified facts only.
 */
data class ConferenceSessionMediaFact(
    val sessionId: String,
    /** Monotonic session media generation — [ConferenceSessionMediaWiring.stopSession] must match. */
    val generation: Long,
    val mediaKeyEpoch: Long,
    val endpoint: MediaGroupEndpointBinding,
    val networkInterfaceName: String,
    val masterKey: ByteArray,
    val masterSalt: ByteArray,
    val keyContextHint64: ByteArray,
    val startedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Per-member authoritative wire binding. SSRC and admission key are never derived from [moduleId].
 */
data class MemberBindingFact(
    val moduleId: String,
    val incarnationId: Long,
    val ssrc: Int,
    val sourceAdmissionKey48: ByteArray,
    val mediaKeyEpoch: Long,
) {
    val sourceIdentity: String
        get() = moduleId

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemberBindingFact) return false
        return moduleId == other.moduleId &&
            incarnationId == other.incarnationId &&
            ssrc == other.ssrc &&
            mediaKeyEpoch == other.mediaKeyEpoch &&
            sourceAdmissionKey48.contentEquals(other.sourceAdmissionKey48)
    }

    override fun hashCode(): Int {
        var r = moduleId.hashCode()
        r = 31 * r + incarnationId.hashCode()
        r = 31 * r + ssrc
        r = 31 * r + mediaKeyEpoch.hashCode()
        r = 31 * r + sourceAdmissionKey48.contentHashCode()
        return r
    }
}

/** RCA5-B2 narrow funnel — per-source jitter slot domain (read-only, no algorithm change). */
data class JitterSlotDomainSnapshot(
    val sourceIdentity: String,
    val incarnationId: Long,
    val nextExpectedSlot: Long?,
    val bySlotSize: Int,
    val earliestBufferedSlot: Long?,
    val latestBufferedSlot: Long?,
    val executable: Boolean,
)

/** RCA5-B2 narrow funnel — ingress → jitter → resolve → playout boundary snapshot. */
data class PlayoutFunnelSnapshot(
    val tickMediaTimeMs: Long,
    val playoutAnchorMs: Long?,
    val playoutTargetSlot: Long?,
    val resolvedMixSlot: Long?,
    val selectedTopK: List<String>,
    val activeJitterSources: Int,
    val admittedCount: Int,
    val perSource: List<JitterSlotDomainSnapshot>,
)

/** Post-stop / harness observability snapshot. */
data class SessionMediaRuntimeSnapshot(
    val sessionId: String?,
    val generation: Long?,
    val ingressBlocked: Boolean,
    val transportScopeActive: Boolean,
    val catalogEntries: Int,
    val admittedCount: Int,
    val activeJitterSources: Int,
    val liveDecoders: Int,
    val jitterBufferCount: Int,
)

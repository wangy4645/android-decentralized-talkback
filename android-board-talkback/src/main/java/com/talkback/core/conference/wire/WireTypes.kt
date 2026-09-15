package com.talkback.core.conference.wire

/**
 * ADR-0058 Profile 02 frozen wire constants (E2b-01).
 * Must not invent umbrella failure classes or reopen Profiles.
 */
object ConferenceWireConstants {
    const val SAFE_UDP_PAYLOAD_BYTES: Int = 120
    const val REPLAY_WINDOW_PACKETS: Int = 64
    const val RTP_PAYLOAD_TYPE: Int = 111
    const val HEADER_PLUS_HE_OCTETS: Int = 32
    const val AEAD_TAG_OCTETS: Int = 16
    const val MAX_OPUS_PAYLOAD_OCTETS: Int = 72
    const val RTP_WIRE_PROFILE_ID: Int = 1
}

enum class WireOwningSeam {
    Q8,
    Q7,
    Q2,
    Q5,
    Q6,
    Q3,
    FULL_PIPELINE,
}

/**
 * Frozen / seam-owned outcomes only. No WIRE_INGRESS_REJECTED umbrella.
 */
sealed class WireIngressResult {
    data class Accepted(
        val plaintextPayload: ByteArray,
        val packetIndex: Long,
        val ssrc: Int,
        val sequence: Int,
        val roc: Int,
        val headerAndHe: ByteArray,
    ) : WireIngressResult()

    data class Rejected(
        val owningSeam: WireOwningSeam,
        val frozenClass: String?,
        val reason: String,
    ) : WireIngressResult()
}

data class WireKeyContext(
    val masterKey: ByteArray,
    val masterSalt: ByteArray,
    val keyContextHint64: ByteArray,
)

data class WireSourceBinding(
    val ssrc: Int,
    val sourceAdmissionKey48: ByteArray,
)

data class WireReplayState(
    val highestAuthenticatedPacketIndex: Long,
    val seenPacketIndices: Set<Long> = emptySet(),
    val windowPackets: Int = ConferenceWireConstants.REPLAY_WINDOW_PACKETS,
)

data class WireIngressContext(
    val key: WireKeyContext,
    val installedBinding: WireSourceBinding?,
    val replay: WireReplayState?,
    /** When set, AEAD uses this key instead of [key.masterKey] (N8a). */
    val trialMasterKey: ByteArray? = null,
    /** Expected ROC for this datagram (fixture / local ROC estimate). */
    val roc: Int = 0,
)

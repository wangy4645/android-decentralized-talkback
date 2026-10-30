package com.talkback.core.conference.capacity

import com.talkback.core.conference.wire.ConferenceWireConstants
import com.talkback.core.conference.wire.ConferenceWireEgress

/**
 * Pre-generated legal 120B protected artifacts for measurement hot path (protect-once, ring reuse).
 */
class ProtectedArtifactRing(
    val capacity: Int,
    private val artifacts: Array<ByteArray>,
) {
    init {
        require(capacity == artifacts.size) { "capacity mismatch" }
        artifacts.forEach { artifact ->
            require(artifact.size == ConferenceWireConstants.SAFE_UDP_PAYLOAD_BYTES) {
                "artifact must be ${ConferenceWireConstants.SAFE_UDP_PAYLOAD_BYTES}B"
            }
        }
    }

    fun artifactAt(ringIndex: Int): ByteArray = artifacts[ringIndex % capacity]

    companion object {
        const val DEFAULT_RING_SIZE: Int = 1024

        fun build(
            ringSize: Int = DEFAULT_RING_SIZE,
            opusPayloadOctets: Int = ConferenceWireConstants.MAX_OPUS_PAYLOAD_OCTETS,
        ): ProtectedArtifactRing {
            require(ringSize > 0)
            val opus = ByteArray(opusPayloadOctets) { (it and 0xff).toByte() }
            val artifacts = Array(ringSize) { ringIndex ->
                val seq = Gres3HarnessTestKeys.BASE_SEQ + ringIndex
                val header = buildRtpHeaderHe(seq)
                when (
                    val result =
                        ConferenceWireEgress.protect(
                            headerAndHe = header,
                            plaintextPayload = opus,
                            masterKey = Gres3HarnessTestKeys.masterKey,
                            masterSalt = Gres3HarnessTestKeys.masterSalt,
                            ssrc = Gres3HarnessTestKeys.SSRC,
                            roc = Gres3HarnessTestKeys.ROC,
                            seq = seq,
                        )
                ) {
                    is ConferenceWireEgress.EgressResult.Protected -> result.udpPayload.copyOf()
                    is ConferenceWireEgress.EgressResult.Rejected ->
                        error("artifact ring build rejected: ${result.frozenClass} ${result.reason}")
                }
            }
            return ProtectedArtifactRing(ringSize, artifacts)
        }

        private fun buildRtpHeaderHe(seq: Int): ByteArray {
            val base =
                hex(
                    "906f10012000000011223344bede00041ebb49f2142c4c194a3a6cb214fe3e94",
                )
            base[2] = ((seq ushr 8) and 0xff).toByte()
            base[3] = (seq and 0xff).toByte()
            return base
        }

        private fun hex(s: String): ByteArray =
            ByteArray(s.length / 2) { i ->
                s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
    }
}

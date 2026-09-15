package com.talkback.core.conference.wire

/**
 * Profile 02 egress: authorized Source → RTP/SRTP protect → UDP body <= 120.
 * Does not create Source authority; caller supplies binding + key facts.
 */
object ConferenceWireEgress {
    sealed class EgressResult {
        data class Protected(val udpPayload: ByteArray) : EgressResult()

        data class Rejected(
            val frozenClass: String,
            val reason: String,
        ) : EgressResult()
    }

    fun protect(
        headerAndHe: ByteArray,
        plaintextPayload: ByteArray,
        masterKey: ByteArray,
        masterSalt: ByteArray,
        ssrc: Int,
        roc: Int,
        seq: Int,
    ): EgressResult {
        if (headerAndHe.size != ConferenceWireConstants.HEADER_PLUS_HE_OCTETS) {
            return EgressResult.Rejected(
                "HE_FORMAT_INVALID",
                "header+HE must be exactly ${ConferenceWireConstants.HEADER_PLUS_HE_OCTETS} octets",
            )
        }
        if (plaintextPayload.size > ConferenceWireConstants.MAX_OPUS_PAYLOAD_OCTETS) {
            return EgressResult.Rejected(
                "WIRE_ENVELOPE_OVERSIZE",
                "Opus payload exceeds MaxOpusPayloadOctets",
            )
        }
        val udp =
            SrtpAeadAes128Gcm.protect(
                headerAndHe = headerAndHe,
                plaintextPayload = plaintextPayload,
                masterKey = masterKey,
                masterSalt = masterSalt,
                ssrc = ssrc,
                roc = roc,
                seq = seq,
            )
        if (udp.size > ConferenceWireConstants.SAFE_UDP_PAYLOAD_BYTES) {
            return EgressResult.Rejected(
                "WIRE_ENVELOPE_OVERSIZE",
                "protected UDP body > SafeUdpPayloadBytes",
            )
        }
        return EgressResult.Protected(udp)
    }
}

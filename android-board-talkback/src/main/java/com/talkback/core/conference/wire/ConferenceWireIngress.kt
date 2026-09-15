package com.talkback.core.conference.wire

/**
 * Profile 02 shared-ingress admission order (E2b-01):
 * Q8 oversize → Q7 RTCP discard → Q2 RTP structure → Q5 SRTP → Q6 replay → Q3 binding.
 */
object ConferenceWireIngress {
    fun admit(
        datagram: ByteArray,
        context: WireIngressContext,
    ): WireIngressResult {
        if (datagram.size > ConferenceWireConstants.SAFE_UDP_PAYLOAD_BYTES) {
            return WireIngressResult.Rejected(
                WireOwningSeam.Q8,
                "WIRE_ENVELOPE_OVERSIZE",
                "UDP payload length > SafeUdpPayloadBytes",
            )
        }

        if (WireDemux.isRtcpShaped(datagram)) {
            return WireIngressResult.Rejected(
                WireOwningSeam.Q7,
                "RTCP_PACKET_DISCARDED",
                "RTCP-shaped datagram on shared media ingress",
            )
        }

        val parsed =
            when (val parse = RtpStructuralGate.parse(datagram)) {
                is RtpStructuralGate.ParseResult.Reject -> return parse.result
                is RtpStructuralGate.ParseResult.Ok -> parse.parsed
            }

        val masterKey = context.trialMasterKey ?: context.key.masterKey
        val plaintext =
            SrtpAeadAes128Gcm.unprotect(
                headerAndHe = parsed.headerAndHe,
                ciphertextAndTag = parsed.protectedPayloadAndTag,
                masterKey = masterKey,
                masterSalt = context.key.masterSalt,
                ssrc = parsed.ssrc,
                roc = context.roc,
                seq = parsed.sequence,
            )
        if (plaintext == null) {
            return WireIngressResult.Rejected(
                WireOwningSeam.Q5,
                "SRTP_AUTH_FAILED",
                "AEAD authentication or decryption failed",
            )
        }

        val packetIndex =
            ((context.roc.toLong() and 0xffffffffL) shl 16) or
                (parsed.sequence.toLong() and 0xffffL)

        val replayState =
            context.replay
                ?: WireReplayState(highestAuthenticatedPacketIndex = -1L)
        SrtpReplayAdmission.evaluate(packetIndex, replayState)?.let { return it }

        SourceBindingGate.evaluate(
            ssrc = parsed.ssrc,
            senderDiscriminator16 = parsed.senderDiscriminator16,
            sourceDiscriminator32 = parsed.sourceDiscriminator32,
            installed = context.installedBinding,
        )?.let { return it }

        return WireIngressResult.Accepted(
            plaintextPayload = plaintext,
            packetIndex = packetIndex,
            ssrc = parsed.ssrc,
            sequence = parsed.sequence,
            roc = context.roc,
            headerAndHe = parsed.headerAndHe,
        )
    }
}

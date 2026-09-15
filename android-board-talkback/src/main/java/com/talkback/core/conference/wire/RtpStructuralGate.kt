package com.talkback.core.conference.wire

/**
 * Profile 02 Q2 RTP fixed header + TalkbackMediaContextV1 HE structural gate.
 */
object RtpStructuralGate {
    data class ParsedRtp(
        val sequence: Int,
        val timestamp: Int,
        val ssrc: Int,
        val marker: Boolean,
        val headerAndHe: ByteArray,
        val keyContextHint64: ByteArray,
        val sourceDiscriminator32: ByteArray,
        val senderDiscriminator16: ByteArray,
        val voiceActiveAudioLevel: Int,
        val protectedPayloadAndTag: ByteArray,
    )

    sealed class ParseResult {
        data class Ok(val parsed: ParsedRtp) : ParseResult()
        data class Reject(val result: WireIngressResult.Rejected) : ParseResult()
    }

    fun parse(datagram: ByteArray): ParseResult {
        if (datagram.size < 12) {
            return ParseResult.Reject(
                WireIngressResult.Rejected(
                    WireOwningSeam.Q2,
                    "HE_FORMAT_INVALID",
                    "truncated RTP fixed header",
                ),
            )
        }
        val b0 = datagram[0].toInt() and 0xFF
        val version = (b0 ushr 6) and 0x3
        if (version != 2) {
            return ParseResult.Reject(
                WireIngressResult.Rejected(
                    WireOwningSeam.Q2,
                    "HE_FORMAT_INVALID",
                    "RTP version != 2",
                ),
            )
        }
        val padding = ((b0 ushr 5) and 0x1) == 1
        val extension = ((b0 ushr 4) and 0x1) == 1
        val cc = b0 and 0x0F
        if (cc != 0) {
            return ParseResult.Reject(
                WireIngressResult.Rejected(
                    WireOwningSeam.Q2,
                    "HE_FORMAT_INVALID",
                    "CSRC count must be 0 in V1; structural length inconsistency",
                ),
            )
        }
        if (padding) {
            return ParseResult.Reject(
                WireIngressResult.Rejected(
                    WireOwningSeam.Q2,
                    "HE_FORMAT_INVALID",
                    "RTP padding forbidden in V1",
                ),
            )
        }
        if (!extension) {
            return ParseResult.Reject(
                WireIngressResult.Rejected(
                    WireOwningSeam.Q2,
                    "HE_FORMAT_INVALID",
                    "RTP X bit must be 1",
                ),
            )
        }
        val b1 = datagram[1].toInt() and 0xFF
        val marker = ((b1 ushr 7) and 0x1) == 1
        val pt = b1 and 0x7F
        if (pt != ConferenceWireConstants.RTP_PAYLOAD_TYPE) {
            return ParseResult.Reject(
                WireIngressResult.Rejected(
                    WireOwningSeam.Q2,
                    "HE_FORMAT_INVALID",
                    "unexpected payload type",
                ),
            )
        }
        val sequence = ((datagram[2].toInt() and 0xFF) shl 8) or (datagram[3].toInt() and 0xFF)
        val timestamp =
            ((datagram[4].toInt() and 0xFF) shl 24) or
                ((datagram[5].toInt() and 0xFF) shl 16) or
                ((datagram[6].toInt() and 0xFF) shl 8) or
                (datagram[7].toInt() and 0xFF)
        val ssrc =
            ((datagram[8].toInt() and 0xFF) shl 24) or
                ((datagram[9].toInt() and 0xFF) shl 16) or
                ((datagram[10].toInt() and 0xFF) shl 8) or
                (datagram[11].toInt() and 0xFF)

        if (datagram.size < ConferenceWireConstants.HEADER_PLUS_HE_OCTETS +
            ConferenceWireConstants.AEAD_TAG_OCTETS
        ) {
            return ParseResult.Reject(
                WireIngressResult.Rejected(
                    WireOwningSeam.Q2,
                    "HE_FORMAT_INVALID",
                    "datagram shorter than header+HE+tag",
                ),
            )
        }
        if (datagram[12] != 0xBE.toByte() || datagram[13] != 0xDE.toByte()) {
            return ParseResult.Reject(
                WireIngressResult.Rejected(
                    WireOwningSeam.Q2,
                    "HE_FORMAT_INVALID",
                    "HE profile must be 0xBEDE",
                ),
            )
        }
        val heWords =
            ((datagram[14].toInt() and 0xFF) shl 8) or (datagram[15].toInt() and 0xFF)
        if (heWords != 4) {
            return ParseResult.Reject(
                WireIngressResult.Rejected(
                    WireOwningSeam.Q2,
                    "HE_FORMAT_INVALID",
                    "HE length must be 4 words",
                ),
            )
        }
        val elementHdr = datagram[16].toInt() and 0xFF
        val elementId = (elementHdr ushr 4) and 0x0F
        val elementLenField = elementHdr and 0x0F
        if (elementId != 1 || elementLenField != 14) {
            return ParseResult.Reject(
                WireIngressResult.Rejected(
                    WireOwningSeam.Q2,
                    "HE_FORMAT_INVALID",
                    "HE element must be id=1 len=14 (15 data octets)",
                ),
            )
        }

        val headerAndHe = datagram.copyOfRange(0, ConferenceWireConstants.HEADER_PLUS_HE_OCTETS)
        return ParseResult.Ok(
            ParsedRtp(
                sequence = sequence,
                timestamp = timestamp,
                ssrc = ssrc,
                marker = marker,
                headerAndHe = headerAndHe,
                keyContextHint64 = datagram.copyOfRange(17, 25),
                sourceDiscriminator32 = datagram.copyOfRange(25, 29),
                senderDiscriminator16 = datagram.copyOfRange(29, 31),
                voiceActiveAudioLevel = datagram[31].toInt() and 0xFF,
                protectedPayloadAndTag =
                    datagram.copyOfRange(ConferenceWireConstants.HEADER_PLUS_HE_OCTETS, datagram.size),
            ),
        )
    }
}

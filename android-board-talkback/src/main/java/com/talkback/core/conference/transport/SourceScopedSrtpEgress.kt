package com.talkback.core.conference.transport

import com.talkback.core.conference.wire.ConferenceWireConstants
import com.talkback.core.conference.wire.ConferenceWireEgress

/**
 * Source-scoped SRTP egress timeline (Phase 1 constraint).
 *
 * seq / ROC / key context are owned per sending Source — NOT per receiver/destination.
 * Future unicast fallback reuses the same protected artifact without re-keying.
 */
class SourceScopedSrtpEgress(
    val sourceIdentity: String,
    private val masterKey: ByteArray,
    private val masterSalt: ByteArray,
    val ssrc: Int,
    var roc: Int,
    initialSeq: Int,
    private val headerHeTemplate: ByteArray,
) {
    private var nextSeq: Int = initialSeq

    val currentSeq: Int
        get() = nextSeq

    fun setVoiceActiveAudioLevel(voiceActiveAudioLevel: Int) {
        headerHeTemplate[31] = voiceActiveAudioLevel.toByte()
    }

    fun voiceActiveAudioLevel(): Int = headerHeTemplate[31].toInt() and 0xFF

    fun protectNext(plaintextPayload: ByteArray): ConferenceWireEgress.EgressResult {
        require(plaintextPayload.size <= ConferenceWireConstants.MAX_OPUS_PAYLOAD_OCTETS) {
            "opus payload oversize"
        }
        val header = headerHeTemplate.copyOf()
        header[2] = ((nextSeq ushr 8) and 0xff).toByte()
        header[3] = (nextSeq and 0xff).toByte()
        val result =
            ConferenceWireEgress.protect(
                headerAndHe = header,
                plaintextPayload = plaintextPayload,
                masterKey = masterKey,
                masterSalt = masterSalt,
                ssrc = ssrc,
                roc = roc,
                seq = nextSeq,
            )
        if (result is ConferenceWireEgress.EgressResult.Protected) {
            nextSeq += 1
        }
        return result
    }

    companion object {
        fun buildHeaderHeTemplate(ssrc: Int): ByteArray {
            val base =
                hex(
                    "906f10012000000011223344bede00041ebb49f2142c4c194a3a6cb214fe3e94",
                )
            base[8] = ((ssrc ushr 24) and 0xff).toByte()
            base[9] = ((ssrc ushr 16) and 0xff).toByte()
            base[10] = ((ssrc ushr 8) and 0xff).toByte()
            base[11] = (ssrc and 0xff).toByte()
            return base
        }

        private fun hex(s: String): ByteArray =
            ByteArray(s.length / 2) { i ->
                s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
    }
}

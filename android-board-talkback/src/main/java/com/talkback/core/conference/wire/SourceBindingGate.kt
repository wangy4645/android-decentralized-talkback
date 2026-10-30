package com.talkback.core.conference.wire

/**
 * Profile 02 Q3 Source/SSRC binding gate after successful AEAD.
 */
object SourceBindingGate {
    fun evaluate(
        ssrc: Int,
        senderDiscriminator16: ByteArray,
        sourceDiscriminator32: ByteArray,
        installed: WireSourceBinding?,
    ): WireIngressResult.Rejected? {
        if (installed == null) {
            return WireIngressResult.Rejected(
                WireOwningSeam.Q3,
                "SOURCE_PACKET_IDENTITY_MISMATCH",
                "no installed Source binding",
            )
        }
        if (ssrc != installed.ssrc) {
            return WireIngressResult.Rejected(
                WireOwningSeam.Q3,
                "SOURCE_PACKET_IDENTITY_MISMATCH",
                "SSRC mismatch",
            )
        }
        val packetKey = senderDiscriminator16 + sourceDiscriminator32
        if (!packetKey.contentEquals(installed.sourceAdmissionKey48)) {
            return WireIngressResult.Rejected(
                WireOwningSeam.Q3,
                "SOURCE_PACKET_IDENTITY_MISMATCH",
                "sourceAdmissionKey48 mismatch",
            )
        }
        return null
    }
}

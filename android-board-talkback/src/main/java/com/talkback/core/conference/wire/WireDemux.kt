package com.talkback.core.conference.wire

/**
 * Profile 02 Q7/Q8 demux helpers.
 */
object WireDemux {
    fun isRtcpShaped(datagram: ByteArray): Boolean {
        if (datagram.size < 2) return false
        val version = (datagram[0].toInt() ushr 6) and 0x3
        if (version != 2) return false
        val pt = datagram[1].toInt() and 0xFF
        // RFC 3550 RTCP payload types are in 72..76 historically mapped; modern
        // SR/RR/SDES/BYE/APP use 200..204. Profile 02 discards RTCP-shaped
        // shared-ingress before Q2/Q5/Q6.
        return pt in 200..211
    }
}

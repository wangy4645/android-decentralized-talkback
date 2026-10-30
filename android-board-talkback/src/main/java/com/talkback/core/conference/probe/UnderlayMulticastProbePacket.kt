package com.talkback.core.conference.probe

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fixed 120-byte underlay probe datagram (Profile 02 payload size).
 *
 * Layout: magic(4) + runIdHash(4) + seq(8) + sendWallMs(8) + reserved(96)
 */
data class UnderlayMulticastProbePacket(
    val runIdHash: Int,
    val seq: Long,
    val sendWallMs: Long,
) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(UnderlayMulticastProbeConstants.PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
        buf.putInt(UnderlayMulticastProbeConstants.PACKET_MAGIC)
        buf.putInt(runIdHash)
        buf.putLong(seq)
        buf.putLong(sendWallMs)
        while (buf.hasRemaining()) {
            buf.put(0)
        }
        return buf.array()
    }

    companion object {
        fun decode(payload: ByteArray): UnderlayMulticastProbePacket? {
            if (payload.size != UnderlayMulticastProbeConstants.PAYLOAD_BYTES) return null
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            if (buf.int != UnderlayMulticastProbeConstants.PACKET_MAGIC) return null
            val runIdHash = buf.int
            val seq = buf.long
            val sendWallMs = buf.long
            return UnderlayMulticastProbePacket(runIdHash, seq, sendWallMs)
        }

        fun runIdHash(runId: String): Int = runId.hashCode()
    }
}

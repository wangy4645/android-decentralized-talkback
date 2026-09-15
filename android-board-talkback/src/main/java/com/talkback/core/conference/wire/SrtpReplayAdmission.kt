package com.talkback.core.conference.wire

/**
 * Profile 02 Q6 SRTP replay admission (RFC 3711 window semantics).
 * Width frozen at [ConferenceWireConstants.REPLAY_WINDOW_PACKETS].
 */
object SrtpReplayAdmission {
    fun evaluate(
        packetIndex: Long,
        state: WireReplayState,
    ): WireIngressResult.Rejected? {
        val window = state.windowPackets
        require(window == ConferenceWireConstants.REPLAY_WINDOW_PACKETS) {
            "replayWindowPackets must be frozen 64"
        }
        if (packetIndex in state.seenPacketIndices) {
            return WireIngressResult.Rejected(
                WireOwningSeam.Q6,
                "SRTP_REPLAY_DUPLICATE",
                "packet index already authenticated",
            )
        }
        val highest = state.highestAuthenticatedPacketIndex
        if (highest < 0L || packetIndex > highest) {
            return null
        }
        val oldestAcceptable = highest - window + 1
        if (packetIndex < oldestAcceptable) {
            return WireIngressResult.Rejected(
                WireOwningSeam.Q6,
                "SRTP_REPLAY_TOO_OLD",
                "packet index below replay window",
            )
        }
        return null
    }

    fun advance(state: WireReplayState, packetIndex: Long): WireReplayState {
        val newHighest = maxOf(state.highestAuthenticatedPacketIndex, packetIndex)
        return state.copy(
            highestAuthenticatedPacketIndex = newHighest,
            seenPacketIndices = state.seenPacketIndices + packetIndex,
        )
    }
}

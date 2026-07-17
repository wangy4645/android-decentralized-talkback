package com.talkback.appprod.ui

/**
 * Media-layer fact: this node is receiving decodable audio from [remoteModuleId].
 * Phase 4 wires the real observer; Phase 1–3 use [NoOpReceivePathLivenessProvider] or playbackReady stub.
 */
interface ReceivePathLivenessProvider {
    fun receivePathLive(
        sessionId: String,
        remoteModuleId: String
    ): Boolean
}

/** Phase 1–3 placeholder until media receive-path observer is wired (Phase 4). */
object NoOpReceivePathLivenessProvider : ReceivePathLivenessProvider {
    override fun receivePathLive(sessionId: String, remoteModuleId: String): Boolean = false
}

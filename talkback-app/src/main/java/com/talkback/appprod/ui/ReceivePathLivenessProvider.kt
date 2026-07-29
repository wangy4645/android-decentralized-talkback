package com.talkback.appprod.ui

/**
 * Media-layer fact: this node is receiving decodable audio from [remoteModuleId].
 * Production wiring: [RuntimeReceivePathLivenessProvider] + [ReceivePathLivenessObserver].
 */
interface ReceivePathLivenessProvider {
    fun receivePathLive(
        sessionId: String,
        remoteModuleId: String
    ): Boolean

    /** ADR-0030 Ch.4: receivePathLive was true at least once this session for this peer. */
    fun mediaEverLive(
        sessionId: String,
        remoteModuleId: String
    ): Boolean
}

/** Test / fallback when runtime is not wired. */
object NoOpReceivePathLivenessProvider : ReceivePathLivenessProvider {
    override fun receivePathLive(sessionId: String, remoteModuleId: String): Boolean = false
    override fun mediaEverLive(sessionId: String, remoteModuleId: String): Boolean = false
}

package com.talkback.appprod.ui

import com.talkback.app.TalkbackRuntime

class RuntimeReceivePathLivenessProvider(
    private val runtimeProvider: () -> TalkbackRuntime?
) : ReceivePathLivenessProvider {
    override fun receivePathLive(sessionId: String, remoteModuleId: String): Boolean =
        runtimeProvider()?.receivePathLive(sessionId, remoteModuleId) ?: false
}

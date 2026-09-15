package com.talkback.core.conference.session.integration.cutover

interface MulticastAudiblePort {
    fun fenceProductionPlayout(sessionId: String)

    fun acquireProductionAudioTrack(sessionId: String): Boolean

    fun releaseProductionAudioTrack(sessionId: String)

    fun isProductionAudioTrackActive(sessionId: String): Boolean
}

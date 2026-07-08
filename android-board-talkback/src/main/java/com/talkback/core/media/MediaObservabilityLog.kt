package com.talkback.core.media

import com.talkback.core.util.TalkbackLog
import com.talkback.core.webrtc.MediaBearerScope

object MediaObservabilityLog {
    fun mediaLifecycle(
        moduleId: String,
        scope: MediaBearerScope,
        lifecycle: MediaLifecycle,
        generation: Long,
        iceState: String
    ) {
        TalkbackLog.i(
            "MEDIA_LIFECYCLE module=$moduleId scope=$scope lifecycle=$lifecycle " +
                "generation=$generation ice=$iceState"
        )
    }

    fun mediaSessionReuse(
        moduleId: String,
        previousScope: MediaBearerScope,
        requestedScope: MediaBearerScope,
        generation: Long
    ) {
        TalkbackLog.i(
            "MEDIA_SESSION_REUSE=1 module=$moduleId previousScope=$previousScope " +
                "requestedScope=$requestedScope generation=$generation"
        )
    }

    fun mediaBarrierComplete(moduleCount: Int, unresolvedCount: Int, channelId: String? = null) {
        val channelSuffix = channelId?.let { " ch=$it" }.orEmpty()
        TalkbackLog.i(
            "MEDIA_BARRIER_COMPLETE$channelSuffix modules=$moduleCount unresolved=$unresolvedCount " +
                "MEDIA_SESSION_REUSE=0"
        )
    }
}

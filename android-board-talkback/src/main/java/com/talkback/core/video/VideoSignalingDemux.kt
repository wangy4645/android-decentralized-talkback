package com.talkback.core.video

import com.talkback.core.model.SignalType

/**
 * Video control/media signaling must not enter audio offer/ICE recovery.
 */
object VideoSignalingDemux {

    fun isVideoSignal(type: SignalType): Boolean = when (type) {
        SignalType.VIDEO_PUBLISH,
        SignalType.VIDEO_PUBLISH_DENY,
        SignalType.VIDEO_STOP,
        SignalType.VIDEO_OFFER,
        SignalType.VIDEO_ANSWER,
        SignalType.VIDEO_ICE -> true
        else -> false
    }

    fun isAudioWebrtcSignal(type: SignalType): Boolean = when (type) {
        SignalType.WEBRTC_OFFER,
        SignalType.WEBRTC_ANSWER,
        SignalType.WEBRTC_ICE -> true
        else -> false
    }
}

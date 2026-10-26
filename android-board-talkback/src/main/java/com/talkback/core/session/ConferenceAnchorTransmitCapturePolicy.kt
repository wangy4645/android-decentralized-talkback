package com.talkback.core.session

import com.talkback.core.model.ModuleId

/**
 * ANCHOR host (main listen device): uplink uses [com.talkback.core.webrtc.ConferenceAudioBus]
 * program injection + shadow multicast TX tap — not per-peer WebRTC mic capture.
 *
 * Starting [com.talkback.core.webrtc.WebRtcAudioEngine.startCapture] on every mesh edge while
 * multicast production playout is active contends on the shared ADM/AEC path and correlates with
 * degraded main-listen audio when unmuted; mute stops those captures and listen recovers.
 */
object ConferenceAnchorTransmitCapturePolicy {
    fun deferPeerWebRtcCapture(
        session: TalkbackSession,
        localModuleId: ModuleId,
    ): Boolean {
        if (session.type != SessionType.CONFERENCE || !session.accepted) return false
        if (session.mediaTopology != GroupMediaTopology.ANCHOR) return false
        if (session.muted) return false
        val anchor = session.anchorModuleId ?: return false
        return anchor == localModuleId
    }
}

package com.talkback.core.webrtc

/**
 * Identifies which logical session owns a WebRTC peer connection.
 * Group/conference mesh links use one PC per remote module; unicast uses one PC per call session.
 */
enum class MediaBearerScope {
    GROUP,
    UNICAST,
    CONFERENCE
}

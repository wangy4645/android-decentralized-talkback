package com.talkback.core.session

/**
 * Conference receive (RX) playback ownership.
 *
 * User/capture mute ([TalkbackSession.muted]) gates transmit only — see [ConferenceMediaTransmitGate].
 * Future "I do not want to hear others" must use a separate receiveMuted flag.
 */
object ConferenceReceivePlaybackPolicy {

    data class Input(
        val accepted: Boolean,
        val foregroundSuspended: Boolean
    )

    fun shouldEnableReceivePlayback(input: Input): Boolean {
        if (input.foregroundSuspended) return false
        return input.accepted
    }
}

package com.talkback.core.session

/**
 * Policy for CONFERENCE vs GROUP PTT on one channel.
 *
 * [ChannelModeFsm] is the authority for mode; this type separates:
 * - blocking **new** GROUP mesh while conference owns the channel
 * - gating **receive playback** on an existing GROUP session (floor-held audio only)
 *
 * Teardown timing uses explicit IDLE transition + mesh reconcile, not post-meeting cooldown timers.
 */
object ChannelLifecyclePolicy {

    data class ChannelGateState(
        val channelMode: ChannelMode,
        val pendingConferenceInvite: Boolean,
        val meetingPreferredOnChannel: Boolean
    )

    /** Block creating or accepting GROUP mesh while conference still owns the channel. */
    fun blocksNewGroupMesh(state: ChannelGateState): Boolean =
        state.channelMode == ChannelMode.CONFERENCE ||
            state.pendingConferenceInvite ||
            state.meetingPreferredOnChannel

    /** Existing GROUP PTT playback: suppress only while channel mode is still CONFERENCE. */
    fun blocksGroupReceivePlayback(state: ChannelGateState): Boolean =
        state.channelMode == ChannelMode.CONFERENCE

    /** Post-meeting GROUP mesh recovery — event-driven, never from UI poll. */
    fun shouldScheduleGroupRecovery(event: ChannelLifecycleEvent): Boolean =
        when (event) {
            ChannelLifecycleEvent.ConferenceEnded,
            ChannelLifecycleEvent.MeetingTabReleased,
            ChannelLifecycleEvent.PttRecoveryRequested -> true
            ChannelLifecycleEvent.PeersDiscovered -> false
        }
}

package com.talkback.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelLifecyclePolicyTest {

    private fun state(
        mode: ChannelMode = ChannelMode.IDLE,
        pending: Boolean = false,
        meetingPreferred: Boolean = false
    ) = ChannelLifecyclePolicy.ChannelGateState(mode, pending, meetingPreferred)

    @Test
    fun newGroupMesh_blockedOnlyDuringConferenceOwnership() {
        assertFalse(ChannelLifecyclePolicy.blocksNewGroupMesh(state(ChannelMode.IDLE)))
        assertFalse(ChannelLifecyclePolicy.blocksNewGroupMesh(state(ChannelMode.GROUP_PTT)))
        assertTrue(ChannelLifecyclePolicy.blocksNewGroupMesh(state(ChannelMode.CONFERENCE)))
        assertTrue(ChannelLifecyclePolicy.blocksNewGroupMesh(state(pending = true)))
        assertFalse(ChannelLifecyclePolicy.blocksNewGroupMesh(state(meetingPreferred = true)))
    }

    @Test
    fun meetingPreferred_doesNotBlockNewGroupMesh() {
        assertFalse(
            ChannelLifecyclePolicy.blocksNewGroupMesh(
                state(ChannelMode.IDLE, meetingPreferred = true)
            )
        )
        assertFalse(
            ChannelLifecyclePolicy.blocksNewGroupMesh(
                state(ChannelMode.GROUP_PTT, meetingPreferred = true)
            )
        )
    }

    @Test
    fun groupPlayback_notBlockedAfterIdleEvenWithPendingUiFlags() {
        assertTrue(
            ChannelLifecyclePolicy.blocksGroupReceivePlayback(state(ChannelMode.CONFERENCE))
        )
        assertFalse(
            ChannelLifecyclePolicy.blocksGroupReceivePlayback(
                state(ChannelMode.GROUP_PTT, pending = true, meetingPreferred = true)
            )
        )
        assertFalse(ChannelLifecyclePolicy.blocksGroupReceivePlayback(state(ChannelMode.IDLE)))
    }

    @Test
    fun groupRecovery_onlyOnExplicitLifecycleEvents() {
        assertTrue(ChannelLifecyclePolicy.shouldScheduleGroupRecovery(ChannelLifecycleEvent.ConferenceEnded))
        assertTrue(ChannelLifecyclePolicy.shouldScheduleGroupRecovery(ChannelLifecycleEvent.MeetingTabReleased))
        assertTrue(ChannelLifecyclePolicy.shouldScheduleGroupRecovery(ChannelLifecycleEvent.PttRecoveryRequested))
        assertFalse(ChannelLifecyclePolicy.shouldScheduleGroupRecovery(ChannelLifecycleEvent.PeersDiscovered))
    }
}

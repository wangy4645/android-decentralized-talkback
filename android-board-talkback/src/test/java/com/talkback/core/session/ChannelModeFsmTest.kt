package com.talkback.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelModeFsmTest {

    @Test
    fun idle_allowsGroupAndConference() {
        val fsm = ChannelModeFsm("CH-01")
        assertTrue(fsm.requestMode(ChannelMode.GROUP_PTT, "M01"))
        assertTrue(fsm.allowsIncomingGroup())
    }

    @Test
    fun conference_blocksGroup() {
        val fsm = ChannelModeFsm("CH-01")
        assertTrue(fsm.requestMode(ChannelMode.CONFERENCE, "M01"))
        assertFalse(fsm.allowsIncomingGroup())
        assertFalse(fsm.requestMode(ChannelMode.GROUP_PTT, "M02"))
    }

    @Test
    fun conference_toIdle_allowsGroupAgain() {
        val fsm = ChannelModeFsm("CH-01")
        assertTrue(fsm.requestMode(ChannelMode.CONFERENCE, "M01"))
        assertTrue(fsm.requestMode(ChannelMode.IDLE, "M01"))
        assertTrue(fsm.requestMode(ChannelMode.GROUP_PTT, "M02"))
    }

    @Test
    fun groupPtt_canSwitchToConference() {
        val fsm = ChannelModeFsm("CH-01")
        assertTrue(fsm.requestMode(ChannelMode.GROUP_PTT, "M01"))
        assertTrue(fsm.requestMode(ChannelMode.CONFERENCE, "M01"))
        assertFalse(fsm.allowsIncomingGroup())
    }
}

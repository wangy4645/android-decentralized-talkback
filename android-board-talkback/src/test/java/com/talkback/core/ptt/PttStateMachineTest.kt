package com.talkback.core.ptt

import org.junit.Assert.assertEquals
import org.junit.Test

class PttStateMachineTest {
    @Test
    fun fullPttCycle() {
        val ptt = PttStateMachine()
        assertEquals(PttState.IDLE, ptt.state)
        assertEquals(PttState.REQUEST_FLOOR, ptt.onEvent(PttEvent.Press))
        assertEquals(PttState.TALK, ptt.onEvent(PttEvent.Granted))
        assertEquals(PttState.RELEASE_FLOOR, ptt.onEvent(PttEvent.Release))
        assertEquals(PttState.IDLE, ptt.onEvent(PttEvent.Timeout))
    }

    @Test
    fun rejectFromRequestFloor() {
        val ptt = PttStateMachine()
        ptt.onEvent(PttEvent.Press)
        assertEquals(PttState.IDLE, ptt.onEvent(PttEvent.Rejected))
    }

    @Test
    fun repressWhileReleasingFloor() {
        val ptt = PttStateMachine()
        ptt.onEvent(PttEvent.Press)
        ptt.onEvent(PttEvent.Granted)
        assertEquals(PttState.RELEASE_FLOOR, ptt.onEvent(PttEvent.Release))
        assertEquals(PttState.REQUEST_FLOOR, ptt.onEvent(PttEvent.Press))
    }

    @Test
    fun remoteHangupFromTalk() {
        val ptt = PttStateMachine()
        ptt.onEvent(PttEvent.Press)
        ptt.onEvent(PttEvent.Granted)
        assertEquals(PttState.IDLE, ptt.onEvent(PttEvent.RemoteHangup))
    }
}

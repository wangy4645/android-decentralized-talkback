package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConferenceAdmissionTrackerTest {

    private val logs = mutableListOf<String>()
    private lateinit var tracker: ConferenceAdmissionTracker
    private val key = ConferenceAdmissionKey(sessionId = "sess-1", peerId = "M01")

    @Before
    fun setUp() {
        logs.clear()
        tracker = ConferenceAdmissionTracker(logSink = { logs.add(it) })
    }

    @Test
    fun happyPath_inviteToReady() {
        tracker.transition(key, ConferenceAdmissionPhase.INVITED, ConferenceAdmissionTransitionReason.INVITE_RECEIVED)
        tracker.transition(key, ConferenceAdmissionPhase.ACCEPTING, ConferenceAdmissionTransitionReason.USER_ACCEPT)
        tracker.transition(key, ConferenceAdmissionPhase.NEGOTIATING, ConferenceAdmissionTransitionReason.APPLY_REMOTE_OFFER)
        tracker.transition(key, ConferenceAdmissionPhase.READY, ConferenceAdmissionTransitionReason.ANSWER_COMMITTED)

        assertEquals(ConferenceAdmissionPhase.READY, tracker.phase(key))
        assertTrue(tracker.allowsRecovery(key))
        assertTrue(logs.all { it.contains("scope=CONFERENCE") })
        assertTrue(logs.any { it.contains("phase=READY reason=ANSWER_COMMITTED") })
    }

    @Test
    fun failurePath_negotiatingToFailed() {
        tracker.transition(key, ConferenceAdmissionPhase.ACCEPTING, ConferenceAdmissionTransitionReason.USER_ACCEPT)
        tracker.transition(key, ConferenceAdmissionPhase.NEGOTIATING, ConferenceAdmissionTransitionReason.APPLY_REMOTE_OFFER)
        tracker.transition(key, ConferenceAdmissionPhase.FAILED, ConferenceAdmissionTransitionReason.ACCEPT_FAILED)

        assertEquals(ConferenceAdmissionPhase.FAILED, tracker.phase(key))
        assertFalse(tracker.allowsRecovery(key))
    }

    @Test
    fun terminateSession_marksTerminatedAndBlocksRecovery() {
        tracker.transition(key, ConferenceAdmissionPhase.READY, ConferenceAdmissionTransitionReason.ANSWER_COMMITTED)
        tracker.terminateSession(key.sessionId)

        assertEquals(ConferenceAdmissionPhase.TERMINATED, tracker.phase(key))
        assertFalse(tracker.allowsRecovery(key))
    }

    @Test
    fun keysAreScopedBySessionAndPeer() {
        val otherSession = ConferenceAdmissionKey(sessionId = "sess-2", peerId = "M01")
        tracker.transition(key, ConferenceAdmissionPhase.READY, ConferenceAdmissionTransitionReason.ANSWER_COMMITTED)
        tracker.transition(otherSession, ConferenceAdmissionPhase.NEGOTIATING, ConferenceAdmissionTransitionReason.APPLY_REMOTE_OFFER)

        assertTrue(tracker.allowsRecovery(key))
        assertFalse(tracker.allowsRecovery(otherSession))
    }
}

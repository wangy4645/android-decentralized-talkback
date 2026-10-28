package com.talkback.core.webrtc.conferenceaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 1a-2 — ownership / admission / teardown contract. */
class PcmInjectionPortSessionContractTest {

    @Test
    fun admit_inject_revoke_lifecycle() {
        val port = RecordingPcmInjectionPort()
        val events = mutableListOf<PcmInjectionPortEvent>()
        val session = PcmInjectionPortSession("M02", port) { events.add(it) }

        assertEquals(PcmInjectionPortSession.State.IDLE, session.lifecycleState)
        assertTrue(session.admit().isSuccess)
        assertEquals(PcmInjectionPortSession.State.ADMITTED, session.lifecycleState)
        assertTrue(session.inject(PcmFrame.constantLevel(1_000)).isSuccess)
        assertEquals(1, port.writtenFrames.size)

        session.revoke(PcmInjectionPortSession.RevokeReason.TARGET_REMOVED)
        assertEquals(PcmInjectionPortSession.State.REVOKED, session.lifecycleState)
        assertFalse(port.isOpen)

        val afterRevoke = session.inject(PcmFrame.constantLevel(500))
        assertTrue(afterRevoke.isFailure)
        assertTrue(events.any { it is PcmInjectionPortEvent.InjectRejected })
    }

    @Test
    fun injectBeforeAdmit_rejectedObservably() {
        val port = RecordingPcmInjectionPort()
        val events = mutableListOf<PcmInjectionPortEvent>()
        val session = PcmInjectionPortSession("M03", port) { events.add(it) }

        val result = session.inject(PcmFrame.constantLevel(500))
        assertTrue(result.isFailure)
        assertEquals(0, port.writtenFrames.size)
        assertEquals(PcmInjectionPortSession.State.IDLE, session.lifecycleState)
        assertTrue(events.any { it is PcmInjectionPortEvent.InjectRejected })
    }

    @Test
    fun injectFailure_marksSessionFailedAndClosesPort() {
        val port = RecordingPcmInjectionPort()
        val session = PcmInjectionPortSession("M02", port)
        session.admit()
        port.injectNextFailure(PcmInjectionFailure.INJECT_FAILED)

        val result = session.inject(PcmFrame.constantLevel(500))
        assertTrue(result.isFailure)
        assertEquals(PcmInjectionPortSession.State.FAILED, session.lifecycleState)
        assertFalse(port.isOpen)
    }

    @Test
    fun revoke_isIdempotent() {
        val port = RecordingPcmInjectionPort()
        val events = mutableListOf<PcmInjectionPortEvent>()
        val session = PcmInjectionPortSession("M02", port) { events.add(it) }
        session.admit()
        session.revoke()
        session.revoke()
        assertEquals(1, events.filterIsInstance<PcmInjectionPortEvent.Revoked>().size)
    }

    @Test
    fun readmitAfterRevoke_rejected() {
        val port = RecordingPcmInjectionPort()
        val session = PcmInjectionPortSession("M02", port)
        session.admit()
        session.revoke()
        assertTrue(session.admit().isFailure)
        assertEquals(PcmInjectionPortSession.State.REVOKED, session.lifecycleState)
    }
}

package com.talkback.core.conference.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0′-EPOCH-CONVERGENCE — membership bump must atomically rotate wiring epoch.
 */
class ConferenceSessionMediaWiringMediaKeyEpochRotationTest {
    @Test
    fun membershipEpochBump_staleBindingRejected_untilRotate_thenAdmits() {
        val wiring = ConferenceSessionMediaWiring.forHarness()
        val sessionId = "sess-epoch-rotate"
        val factEpoch1 = SessionMediaWiringHarness.sessionFact(sessionId, generation = 1L, mediaKeyEpoch = 1L)
        val bindingEpoch1 =
            SessionMediaWiringHarness.memberBinding(
                "M-HOST",
                mediaKeyEpoch = 1L,
                incarnationId = 10L,
                ssrc = 0x22000101,
            )
        val bindingEpoch2 =
            bindingEpoch1.copy(
                mediaKeyEpoch = 2L,
                incarnationId = 11L,
                ssrc = 0x22000102,
                sourceAdmissionKey48 =
                    bindingEpoch1.sourceAdmissionKey48.copyOf().also { it[5] = 0x42 },
            )

        assertTrue(wiring.startSession(factEpoch1))
        assertEquals(1L, wiring.currentMediaKeyEpoch(sessionId))
        assertTrue(wiring.installMember(sessionId, bindingEpoch1))

        assertFalse(wiring.installMember(sessionId, bindingEpoch2))

        val factEpoch2 =
            factEpoch1.copy(
                generation = 2L,
                mediaKeyEpoch = 2L,
            )
        assertTrue(wiring.startSession(factEpoch2))
        assertEquals(2L, wiring.currentMediaKeyEpoch(sessionId))

        assertTrue(wiring.installMember(sessionId, bindingEpoch2))
        val snap = wiring.runtimeSnapshot(sessionId)!!
        assertEquals(1, snap.catalogEntries)
        assertEquals(1, snap.admittedCount)

        SessionMediaWiringHarness.assertAccepted(
            wiring.admitDatagram(sessionId, SessionMediaWiringHarness.protectedPacket(bindingEpoch2)),
        )

        assertTrue(wiring.stopSession(sessionId, factEpoch2.generation))
    }

    @Test
    fun rotateMediaKeyEpoch_sameEpoch_isIdempotent() {
        val wiring = ConferenceSessionMediaWiring.forHarness()
        val sessionId = "sess-epoch-idempotent"
        val fact = SessionMediaWiringHarness.sessionFact(sessionId, generation = 1L, mediaKeyEpoch = 1L)
        val binding =
            SessionMediaWiringHarness.memberBinding(
                "M-A",
                mediaKeyEpoch = 1L,
                incarnationId = 1L,
            )

        assertTrue(wiring.startSession(fact))
        assertTrue(wiring.installMember(sessionId, binding))
        assertTrue(wiring.startSession(fact))
        assertTrue(wiring.rotateMediaKeyEpoch(sessionId, fact))
        assertEquals(1, wiring.runtimeSnapshot(sessionId)!!.catalogEntries)

        assertTrue(wiring.stopSession(sessionId, fact.generation))
    }

    @Test
    fun rotateMediaKeyEpoch_rejectsEpochDowngrade() {
        val wiring = ConferenceSessionMediaWiring.forHarness()
        val sessionId = "sess-epoch-fence"
        val factEpoch1 = SessionMediaWiringHarness.sessionFact(sessionId, generation = 1L, mediaKeyEpoch = 1L)
        val factEpoch2 =
            factEpoch1.copy(
                generation = 2L,
                mediaKeyEpoch = 2L,
            )

        assertTrue(wiring.startSession(factEpoch1))
        assertTrue(wiring.rotateMediaKeyEpoch(sessionId, factEpoch2))
        assertEquals(2L, wiring.currentMediaKeyEpoch(sessionId))

        assertFalse(wiring.startSession(factEpoch1))
        assertFalse(wiring.rotateMediaKeyEpoch(sessionId, factEpoch1))
        assertEquals(2L, wiring.currentMediaKeyEpoch(sessionId))

        assertTrue(wiring.stopSession(sessionId, factEpoch2.generation))
    }
}

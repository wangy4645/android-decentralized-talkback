package com.talkback.core.conference.session

import com.talkback.core.conference.wire.WireIngressResult
import com.talkback.core.conference.wire.WireOwningSeam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 Slice 2 — session lifecycle correctness (no capacity / field).
 */
class ConferenceSessionMediaWiringLifecycleTest {
    @Test
    fun lifecycle_joinLeaveRejoin_stalePacketRejected_stopDrainsRuntime() {
        val wiring = ConferenceSessionMediaWiring.forHarness()
        val sessionId = "sess-lifecycle-1"
        val fact = SessionMediaWiringHarness.sessionFact(sessionId, generation = 1L)
        val bindingA = SessionMediaWiringHarness.memberBinding("M-A", incarnationId = 10L, ssrc = 0x22000001)
        val bindingB =
            SessionMediaWiringHarness.memberBinding(
                "M-B",
                incarnationId = 20L,
                ssrc = 0x22000002,
                admissionKeySuffix = 0x21,
            )
        val bindingC =
            SessionMediaWiringHarness.memberBinding(
                "M-C",
                incarnationId = 30L,
                ssrc = 0x22000003,
                admissionKeySuffix = 0x22,
            )

        assertTrue(wiring.startSession(fact))
        assertTrue(wiring.installMember(sessionId, bindingA))
        assertTrue(wiring.installMember(sessionId, bindingB))
        assertTrue(wiring.installMember(sessionId, bindingC))

        var snap = wiring.runtimeSnapshot(sessionId)!!
        assertEquals(3, snap.catalogEntries)
        assertEquals(3, snap.admittedCount)
        assertTrue(snap.transportScopeActive)

        assertTrue(wiring.removeMember(sessionId, bindingB.moduleId, bindingB.incarnationId))
        snap = wiring.runtimeSnapshot(sessionId)!!
        assertEquals(2, snap.catalogEntries)
        assertEquals(2, snap.admittedCount)
        assertNull(wiring.catalog(sessionId)!!.get(bindingB.moduleId))

        val stalePacket = SessionMediaWiringHarness.protectedPacket(bindingB)
        val staleResult = wiring.admitDatagram(sessionId, stalePacket)
        SessionMediaWiringHarness.assertRejected(staleResult)

        val bindingB2 =
            bindingB.copy(
                incarnationId = 21L,
                ssrc = 0x22000012,
                sourceAdmissionKey48 = bindingB.sourceAdmissionKey48.copyOf().also { it[5] = 0x2A },
            )
        assertTrue(wiring.installMember(sessionId, bindingB2))

        val newGenPacket = SessionMediaWiringHarness.protectedPacket(bindingB2)
        SessionMediaWiringHarness.assertAccepted(wiring.admitDatagram(sessionId, newGenPacket))
        SessionMediaWiringHarness.assertRejected(wiring.admitDatagram(sessionId, stalePacket))

        assertTrue(wiring.stopSession(sessionId, fact.generation))
        SessionMediaWiringHarness.assertStopped(wiring, sessionId)
    }

    @Test
    fun replaceMember_hardFencesOldIncarnation_beforeInstall() {
        val wiring = ConferenceSessionMediaWiring.forHarness()
        val sessionId = "sess-replace-1"
        val fact = SessionMediaWiringHarness.sessionFact(sessionId)
        val oldBinding = SessionMediaWiringHarness.memberBinding("M-B", incarnationId = 100L, ssrc = 0x22000020)
        assertTrue(wiring.startSession(fact))
        assertTrue(wiring.installMember(sessionId, oldBinding))

        val orch = wiring.orchestrator(sessionId)!!
        assertTrue(
            orch.selection.registry.isInstalledExecutable(
                oldBinding.moduleId,
                oldBinding.incarnationId,
            ),
        )

        val newBinding =
            oldBinding.copy(
                incarnationId = 101L,
                ssrc = 0x22000021,
                sourceAdmissionKey48 = oldBinding.sourceAdmissionKey48.copyOf().also { it[5] = 0x33 },
            )
        assertTrue(wiring.replaceMember(sessionId, oldBinding, newBinding))
        assertFalse(
            orch.selection.registry.isInstalledExecutable(
                oldBinding.moduleId,
                oldBinding.incarnationId,
            ),
        )
        assertTrue(
            orch.selection.registry.isInstalledExecutable(
                newBinding.moduleId,
                newBinding.incarnationId,
            ),
        )

        val oldPacket = SessionMediaWiringHarness.protectedPacket(oldBinding)
        val reject = wiring.admitDatagram(sessionId, oldPacket)
        assertTrue(reject is WireIngressResult.Rejected)
        assertEquals(WireOwningSeam.Q3, (reject as WireIngressResult.Rejected).owningSeam)

        assertTrue(wiring.stopSession(sessionId, fact.generation))
    }

    @Test
    fun multiCycle_startJoinLeaveStop_doesNotAccumulateSessions() {
        val wiring = ConferenceSessionMediaWiring.forHarness()
        repeat(5) { cycle ->
            val sessionId = "sess-cycle-$cycle"
            val fact = SessionMediaWiringHarness.sessionFact(sessionId, generation = cycle.toLong() + 1L)
            val a = SessionMediaWiringHarness.memberBinding("M-A-$cycle", incarnationId = 1000L + cycle)
            val b = SessionMediaWiringHarness.memberBinding("M-B-$cycle", incarnationId = 2000L + cycle)
            assertTrue(wiring.startSession(fact))
            assertTrue(wiring.installMember(sessionId, a))
            assertTrue(wiring.installMember(sessionId, b))
            SessionMediaWiringHarness.assertAccepted(
                wiring.admitDatagram(sessionId, SessionMediaWiringHarness.protectedPacket(a)),
            )
            assertTrue(wiring.removeMember(sessionId, b.moduleId, b.incarnationId))
            assertTrue(wiring.stopSession(sessionId, fact.generation))
            SessionMediaWiringHarness.assertStopped(wiring, sessionId)
        }
        assertFalse(wiring.hasSession("sess-cycle-0"))
    }

    @Test
    fun stopSession_wrongGeneration_isIdempotentSafe() {
        val wiring = ConferenceSessionMediaWiring.forHarness()
        val sessionId = "sess-gen-guard"
        val fact = SessionMediaWiringHarness.sessionFact(sessionId, generation = 7L)
        val binding = SessionMediaWiringHarness.memberBinding("M-X", incarnationId = 1L)
        assertTrue(wiring.startSession(fact))
        assertTrue(wiring.installMember(sessionId, binding))
        assertFalse(wiring.stopSession(sessionId, generation = 6L))
        assertTrue(wiring.hasSession(sessionId))
        assertTrue(wiring.stopSession(sessionId, generation = 7L))
        SessionMediaWiringHarness.assertStopped(wiring, sessionId)
    }

    @Test
    fun sourceIdentity_equalsModuleId_enforcedByCatalog() {
        val wiring = ConferenceSessionMediaWiring.forHarness()
        val sessionId = "sess-identity"
        val fact = SessionMediaWiringHarness.sessionFact(sessionId)
        val binding = SessionMediaWiringHarness.memberBinding("HTUBB21B09220661", incarnationId = 5L, ssrc = 0x22000099)
        assertTrue(wiring.startSession(fact))
        assertTrue(wiring.installMember(sessionId, binding))
        val entry = wiring.catalog(sessionId)!!.get("HTUBB21B09220661")
        assertNotNull(entry)
        assertEquals(binding.moduleId, entry!!.sourceIdentity)
        assertEquals(binding.ssrc, entry.ssrc)
        assertTrue(wiring.stopSession(sessionId, fact.generation))
    }
}

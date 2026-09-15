package com.talkback.core.session.failure

import com.talkback.core.session.ConferenceParticipantDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceFailureRuntimeWiringTest {

    @Test
    fun leaseBusy_wiresScenarioD_domainBlockedOnImpact() {
        val lines = mutableListOf<String>()
        val wiring = ConferenceFailureRuntimeWiring(logLine = { lines += it })
        wiring.onLeaseBusy(
            sessionId = "sess-1",
            impactModuleId = "M04",
            impactEdgeKey = "sess-1|M04",
            holderEdgeKey = "sess-1|M03",
            meshGeneration = 1L,
            pcGeneration = 2L,
        )
        val terminals = wiring.terminalsForSession("sess-1")
        assertTrue(terminals["M04"] is ConferenceFailureTerminal.DomainBlocked)
        val attr = (terminals["M04"] as ConferenceFailureTerminal.DomainBlocked).attribution
        assertEquals("sess-1|M03", attr.causeEdgeKey)
        assertEquals(ConferenceFailureCauseFact.LEASE_HELD_BY_CAUSE, attr.causeFact)
        assertTrue(lines.any { it.contains("CONFERENCE_FAILURE_L2_CLASSIFIED") })
        assertTrue(lines.any { it.contains("CONFERENCE_FAILURE_CHAIN_VALIDATED scenario=D") })
    }

    @Test
    fun srdTimeout_wiresScenarioE_edgeFailedOnCause() {
        val lines = mutableListOf<String>()
        val wiring = ConferenceFailureRuntimeWiring(logLine = { lines += it })
        wiring.onSelfAttributedHang(
            sessionId = "sess-1",
            moduleId = "M03",
            edgeKey = "sess-1|M03",
            meshGeneration = 1L,
            srdTimeout = true,
            hangingObserved = true,
        )
        val terminal = wiring.terminalsForSession("sess-1")["M03"]
        assertTrue(terminal is ConferenceFailureTerminal.EdgeFailed)
        val projection = ConferenceFailureParticipantProjector.project(terminal!!)
        assertEquals(ConferenceParticipantDisplayState.VISIBLE_EDGE_FAILED, projection.displayState)
        assertTrue(lines.any { it.contains("CONFERENCE_FAILURE_L1_CLASSIFIED") })
    }

    @Test
    fun srdTimeout_createsB1RecoveryIntent() {
        val domain = com.talkback.core.session.ConferenceNativeExecutionDomain()
        domain.requestLease("sess-1|M03")
        val lines = mutableListOf<String>()
        val wiring = ConferenceFailureRuntimeWiring(
            domainSnapshotProvider = { domain.currentSnapshot() },
            logLine = { lines += it },
        )
        wiring.onSelfAttributedHang(
            sessionId = "sess-1",
            moduleId = "M03",
            edgeKey = "sess-1|M03",
            meshGeneration = 1L,
            pcGeneration = 4L,
            conferenceGeneration = 1L,
            offerLineageId = "CR4",
            realizationAttemptId = "RA4",
            srdTimeout = true,
            hangingObserved = true,
        )
        val intents = wiring.recoveryIntentsForSession("sess-1")
        assertEquals(1, intents.size)
        assertEquals(EdgeSrdRecoveryState.WAITING_FOR_SAFE_ADMISSION, intents.single().state)
        assertTrue(lines.any { it.startsWith("RECOVERY_REQUESTED") })
        assertTrue(lines.any { it.startsWith("RECOVERY_WAITING_FOR_DOMAIN") })
    }

    @Test
    fun leaseBusy_doesNotCreateRecoveryIntent() {
        val lines = mutableListOf<String>()
        val wiring = ConferenceFailureRuntimeWiring(logLine = { lines += it })
        wiring.onLeaseBusy(
            sessionId = "sess-1",
            impactModuleId = "M04",
            impactEdgeKey = "sess-1|M04",
            holderEdgeKey = "sess-1|M02",
            meshGeneration = 1L,
        )
        assertTrue(wiring.recoveryIntentsForSession("sess-1").isEmpty())
        assertTrue(lines.none { it.startsWith("RECOVERY_REQUESTED") })
    }

    @Test
    fun clearSession_terminalsRecoveryIntent() {
        val lines = mutableListOf<String>()
        val wiring = ConferenceFailureRuntimeWiring(logLine = { lines += it })
        wiring.onSelfAttributedHang(
            sessionId = "sess-1",
            moduleId = "M03",
            edgeKey = "sess-1|M03",
            meshGeneration = 1L,
            srdTimeout = true,
        )
        wiring.clearSession("sess-1")
        assertTrue(wiring.recoveryIntentsForSession("sess-1").isEmpty())
        assertTrue(lines.any { it.startsWith("RECOVERY_TERMINAL") })
    }

    @Test
    fun leaseBusyAloneWithoutHolder_doesNotStore() {
        val wiring = ConferenceFailureRuntimeWiring()
        // Invalid holder encoding — adapter must no-op rather than invent cause
        wiring.onLeaseBusy(
            sessionId = "sess-1",
            impactModuleId = "M04",
            impactEdgeKey = "sess-1|M04",
            holderEdgeKey = "invalid",
            meshGeneration = 1L,
        )
        assertTrue(wiring.terminalsForSession("sess-1").isEmpty())
    }
}

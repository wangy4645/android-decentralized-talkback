package com.talkback.core.session

import com.talkback.core.session.failure.ConferenceFailureCauseFact
import com.talkback.core.session.failure.ConferenceFailureRuntimeWiring
import com.talkback.core.session.failure.ConferenceFailureTerminal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUTH-Q4: classification/log splice only. Does not exercise JNI, PENDING queues, or lease vacate.
 */
class ConferenceQ4LeaseBusyClassificationTest {

    @Test
    fun leaseBusy_mediaEdgeFailedReasonIsDomainBlocked_notEdgeLocalFailure() {
        val line = ConferenceSrdNativeDomainObservability.formatMediaEdgeDomainBlocked(
            sessionId = SESSION,
            peer = "M02",
            detail = "NATIVE_DOMAIN_BUSY holder=$HOLDER",
        )
        assertTrue(line.contains("reason=DOMAIN_BLOCKED"))
        assertTrue(line.contains("detail=NATIVE_DOMAIN_BUSY holder=$HOLDER"))
        assertFalse(line.contains("EDGE_LOCAL_FAILURE"))
        assertFalse(line.contains("PENDING"))
        assertFalse(line.contains("queuePendingEngineRequest"))
        assertFalse(line.contains("requestEngine"))
    }

    @Test
    fun onLeaseBusy_domainBlockedKeepsHolderCauseAndRuntimeDomain_andDoesNotEnqueuePending() {
        val lines = mutableListOf<String>()
        val wiring = ConferenceFailureRuntimeWiring(logLine = { lines += it })
        wiring.onLeaseBusy(
            sessionId = SESSION,
            impactModuleId = "M02",
            impactEdgeKey = IMPACT,
            holderEdgeKey = HOLDER,
            meshGeneration = 1L,
            pcGeneration = 578L,
        )
        val blocked = wiring.terminalsForSession(SESSION)["M02"]
        assertTrue(blocked is ConferenceFailureTerminal.DomainBlocked)
        val attr = (blocked as ConferenceFailureTerminal.DomainBlocked).attribution
        assertEquals(HOLDER, attr.causeEdgeKey)
        assertEquals(IMPACT, attr.impactEdgeKey)
        assertEquals(ConferenceSrdNativeDomainObservability.DOMAIN_ID, attr.runtimeDomainRef)
        assertEquals(ConferenceFailureCauseFact.LEASE_HELD_BY_CAUSE, attr.causeFact)
        assertTrue(lines.any { it.contains("CONFERENCE_FAILURE_CHAIN_VALIDATED scenario=D") })
        assertTrue(lines.any { it.contains("runtimeDomainRef=${ConferenceSrdNativeDomainObservability.DOMAIN_ID}") })
        assertTrue(lines.any { it.contains("holderEdgeKey=$HOLDER") })
        assertTrue(lines.any { it.contains("causeEdgeKey=$HOLDER") })
        assertTrue(lines.any { it.contains("terminal=DOMAIN_BLOCKED") })
        val joined = lines.joinToString("\n")
        assertFalse(joined.contains("EDGE_LOCAL_FAILURE"))
        assertFalse(joined.contains("requestEngine"))
        assertFalse(joined.contains("queuePendingEngineRequest"))
        assertFalse(joined.contains("PENDING"))
    }

    @Test
    fun srdTimeout_edgeFailedDoesNotReleaseLease() {
        val domain = ConferenceNativeExecutionDomain()
        assertEquals(
            ConferenceNativeExecutionDomain.RequestResult.Granted,
            domain.requestLease(HOLDER),
        )
        val lines = mutableListOf<String>()
        val wiring = ConferenceFailureRuntimeWiring(logLine = { lines += it })
        wiring.onSelfAttributedHang(
            sessionId = SESSION,
            moduleId = "M03",
            edgeKey = HOLDER,
            meshGeneration = 1L,
            srdTimeout = true,
            hangingObserved = true,
        )
        assertTrue(wiring.terminalsForSession(SESSION)["M03"] is ConferenceFailureTerminal.EdgeFailed)
        assertTrue(lines.any { it.contains("terminal=EDGE_FAILED") })
        assertEquals(HOLDER, domain.currentHolder())
        assertEquals(
            ConferenceNativeExecutionDomain.LeaseState.ACTIVE,
            domain.currentSnapshot()?.state,
        )
        val busy = domain.requestLease(IMPACT)
        assertTrue(busy is ConferenceNativeExecutionDomain.RequestResult.Busy)
        assertEquals(HOLDER, (busy as ConferenceNativeExecutionDomain.RequestResult.Busy).holderEdgeKey)
    }

    @Test
    fun leaseBusyAdmission_doesNotInvokeSrd_andHolderRemains() {
        val domain = ConferenceNativeExecutionDomain()
        domain.requestLease(HOLDER)
        var srdEntered = false
        val outcome = ConferenceSrdNativeDomainAdmission.runWithLease(domain, IMPACT) {
            srdEntered = true
        }
        assertTrue(outcome is ConferenceSrdNativeDomainAdmission.Outcome.Busy)
        assertFalse(srdEntered)
        assertEquals(HOLDER, domain.currentHolder())
    }

    companion object {
        private const val SESSION = "e55028df-a68f-4bec-81a9-42a3b73be8e1"
        private const val HOLDER = "e55028df-a68f-4bec-81a9-42a3b73be8e1|M03"
        private const val IMPACT = "e55028df-a68f-4bec-81a9-42a3b73be8e1|M02"
    }
}

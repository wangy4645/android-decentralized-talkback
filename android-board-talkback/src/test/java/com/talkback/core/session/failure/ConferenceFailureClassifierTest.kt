package com.talkback.core.session.failure

import com.talkback.core.session.ConferenceSrdNativeDomainObservability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceFailureClassifierTest {

    private val scope = ConferenceFailureGenerationScope(
        conferenceSessionId = "sess-1",
        meshGeneration = 1L,
        pcGeneration = 2L,
    )

    private fun edgeKey(remote: String) = "sess-1|$remote"

    @Test
    fun edgeLocalFailureInput_mapsThroughClassifier_toDistinctEdgeFailedTerminal() {
        val obs = ConferenceFailureObservation(
            edgeKey = edgeKey("M03"),
            generationScope = scope,
            edgeLocalFailureObserved = true,
        )
        val terminal = ConferenceFailureClassifier.classifyEdgeFailure(obs)
        assertTrue(obs.edgeLocalFailureObserved)
        assertTrue(terminal is ConferenceFailureTerminal.EdgeFailed)
        assertNotEquals(obs::class, terminal!!::class)
    }

    @Test
    fun hangingObserved_classifiesToEdgeFailed_onCauseEdge() {
        val obs = ConferenceFailureObservation(
            edgeKey = edgeKey("M03"),
            generationScope = scope,
            hangingObserved = true,
        )
        val terminal = ConferenceFailureClassifier.classifyEdgeFailure(obs)
        assertTrue(terminal is ConferenceFailureTerminal.EdgeFailed)
        assertEquals(edgeKey("M03"), terminal!!.edgeKey)
        assertEquals(ConferenceSrdNativeDomainObservability.DOMAIN_ID, terminal.runtimeDomainRef)
    }

    @Test
    fun leaseBusyAlone_doesNotProduceDomainBlocked() {
        val impact = ConferenceFailureObservation(
            edgeKey = edgeKey("M04"),
            generationScope = scope,
        )
        assertNull(ConferenceFailureClassifier.classifyDomainContention(impact))
    }

    @Test
    fun impactEdge_domainBlocked_withLeaseHeldByCause_attributionComplete() {
        val cause = ConferenceFailureObservation(
            edgeKey = edgeKey("M03"),
            generationScope = scope,
            hangingObserved = true,
            activeLeaseHolderEdgeKey = edgeKey("M03"),
        )
        val impact = ConferenceFailureObservation(
            edgeKey = edgeKey("M04"),
            generationScope = scope,
            leaseWaitHolderEdgeKey = edgeKey("M03"),
        )

        val causeTerminal = ConferenceFailureClassifier.classifyEdgeFailure(cause)
        assertTrue(causeTerminal is ConferenceFailureTerminal.EdgeFailed)

        val impactTerminal = ConferenceFailureClassifier.classifyDomainContention(impact, cause)
        assertTrue(impactTerminal is ConferenceFailureTerminal.DomainBlocked)
        val attr = (impactTerminal as ConferenceFailureTerminal.DomainBlocked).attribution
        assertEquals(edgeKey("M04"), attr.impactEdgeKey)
        assertEquals(edgeKey("M03"), attr.causeEdgeKey)
        assertEquals(ConferenceFailureCauseFact.LEASE_HELD_BY_CAUSE, attr.causeFact)
        assertEquals(ConferenceFailureCausePhase.EDGE_FAILED, attr.causePhase)
        assertEquals(ConferenceSrdNativeDomainObservability.DOMAIN_ID, attr.runtimeDomainRef)
        assertEquals(scope, attr.generationScope)
    }

    @Test
    fun impactEdge_notClassifiedAsEdgeFailed_whenPeerCausedBlock() {
        val impact = ConferenceFailureObservation(
            edgeKey = edgeKey("M04"),
            generationScope = scope,
            leaseWaitHolderEdgeKey = edgeKey("M03"),
            srdTimeoutObserved = true,
        )
        assertNull(ConferenceFailureClassifier.classifyEdgeFailure(impact))
        assertTrue(
            ConferenceFailureClassifier.classifyDomainContention(impact) is ConferenceFailureTerminal.DomainBlocked,
        )
    }

    @Test
    fun domainBlocked_notEdgeFailed_typeSeparation() {
        val terminal = ConferenceFailureClassifier.classifyDomainContention(
            ConferenceFailureObservation(
                edgeKey = edgeKey("M04"),
                generationScope = scope,
                leaseWaitHolderEdgeKey = edgeKey("M03"),
            ),
        )!!
        assertNotEquals(
            ConferenceFailureTerminal.EdgeFailed::class,
            terminal::class,
        )
    }

    @Test
    fun hangingOnCause_allowsDomainBlocked_beforeCauseEdgeFailedTerminal() {
        val cause = ConferenceFailureObservation(
            edgeKey = edgeKey("M03"),
            generationScope = scope,
            hangingObserved = true,
            activeLeaseHolderEdgeKey = edgeKey("M03"),
        )
        val impact = ConferenceFailureObservation(
            edgeKey = edgeKey("M04"),
            generationScope = scope,
            leaseWaitHolderEdgeKey = edgeKey("M03"),
        )
        val blocked = ConferenceFailureClassifier.classifyDomainContention(
            impact,
            cause,
            knownCausePhase = ConferenceFailureCausePhase.HANGING_OBSERVED,
        )!!
        assertEquals(ConferenceFailureCausePhase.HANGING_OBSERVED, blocked.attribution.causePhase)
    }
}

package com.talkback.core.conference.capacity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gres3H1cAdjudicatorTest {
    @Test
    fun formalTopologyEligible_whenPreflightAndRuntimePass() {
        val output =
            Gres3H1cAdjudicator.adjudicate(
                Gres3H1cAdjudicatorInput(
                    topologyPreflight = passTopologyPreflight(),
                    topologyClass = Gres3TopologyClass.WLAN_CAPACITY_SINK,
                    runClass = Gres3RunClass.QUALIFICATION,
                    harnessComplete = true,
                    rawFlushed = true,
                    sendFailCount = 0,
                    thermalPreflightPass = true,
                    fanoutP99Ns = 4_000_000L,
                    fanoutMaxNs = 9_000_000L,
                ),
            )
        assertEquals(Gres3TopologyValidityVerdict.PASS, output.topologyValidity)
        assertEquals(Gres3HarnessRuntimeValidityVerdict.PASS, output.harnessRuntimeValidity)
        assertEquals(Gres3FormalEvidenceTopologyVerdict.ELIGIBLE, output.formalEvidenceTopology)
        assertEquals(Gres3C3ProjectionVerdict.LIKELY_PASS, output.c3Projection)
    }

    @Test
    fun loopbackTopology_notEligible_evenWhenRuntimePass() {
        val output =
            Gres3H1cAdjudicator.adjudicate(
                Gres3H1cAdjudicatorInput(
                    topologyPreflight = passTopologyPreflight(),
                    topologyClass = Gres3TopologyClass.LOOPBACK,
                    runClass = Gres3RunClass.QUALIFICATION,
                    harnessComplete = true,
                    rawFlushed = true,
                    sendFailCount = 0,
                    thermalPreflightPass = true,
                    fanoutP99Ns = 4_000_000L,
                    fanoutMaxNs = 9_000_000L,
                ),
            )
        assertEquals(Gres3FormalEvidenceTopologyVerdict.NOT_ELIGIBLE, output.formalEvidenceTopology)
        assertTrue(output.reasons.contains(Gres3H1cReason.LOOPBACK_TOPOLOGY))
    }

    @Test
    fun highFanout_stillEligible_butC3ProjectionLikelyFail() {
        val output =
            Gres3H1cAdjudicator.adjudicate(
                Gres3H1cAdjudicatorInput(
                    topologyPreflight = passTopologyPreflight(),
                    topologyClass = Gres3TopologyClass.WLAN_CAPACITY_SINK,
                    runClass = Gres3RunClass.QUALIFICATION,
                    harnessComplete = true,
                    rawFlushed = true,
                    sendFailCount = 0,
                    thermalPreflightPass = true,
                    fanoutP99Ns = 8_634_000L,
                    fanoutMaxNs = 16_111_458L,
                ),
            )
        assertEquals(Gres3FormalEvidenceTopologyVerdict.ELIGIBLE, output.formalEvidenceTopology)
        assertEquals(Gres3C3ProjectionVerdict.LIKELY_FAIL, output.c3Projection)
        assertTrue(output.reasons.contains(Gres3H1cReason.FANOUT_EXCEEDS_C3_PROJECTION))
    }

    private fun passTopologyPreflight(): Gres3TopologyPreflightResult =
        Gres3TopologyPreflightResult(
            passes = true,
            targetCountValid = true,
            moduleIdsDistinct = true,
            endpointTuplesDistinct = true,
            noLoopbackTargets = true,
            noMulticastTargets = true,
            senderNotLoopbackOnly = true,
            sinkBindingsVerified = true,
            routeHints = emptyList(),
            blockerReasons = emptyList(),
        )
}

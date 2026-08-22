package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding B + per-edge media fact: VIA_ANCHOR only with that star edge's usable fact.
 */
class ConferencePresenceAnchorViaAnchorTest {

    private val members4 = listOf("M01", "M02", "M03", "M04")
    private val now = 50_000L

    private fun star() = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = "c1",
            hostModuleId = "M01",
            anchorId = "M01",
            members = members4,
            meshGeneration = 1L,
            anchorEpoch = 10L
        )
    )

    private fun fact(spoke: String, usable: Boolean, producer: String = "M01") =
        PerEdgeMediaUsabilityFact(
            conferenceId = "c1",
            anchorEpoch = 10L,
            meshGeneration = 1L,
            producedAtMs = now,
            producerModuleId = producer,
            edge = MediaEdge("M01", spoke),
            usable = usable
        )

    private fun compose(
        local: String,
        ice: Set<String>,
        extraFacts: List<PerEdgeMediaUsabilityFact> = emptyList()
    ): ParticipantPresenceProjection {
        val topology = star()
        val observed = ConferencePresenceFactsAdapter.projectFromObservation(
            conferenceId = topology.conferenceId,
            producerModuleId = topology.anchorId!!,
            rosterEpoch = topology.rosterEpoch,
            anchorEpoch = topology.anchorEpoch,
            meshGeneration = topology.meshGeneration,
            producedAtMs = now,
            localModuleId = local,
            iceConnectedRemoteIds = ice,
            topology = topology,
            perEdgeFacts = extraFacts,
            nowMs = now
        )
        return ConferencePresenceProjector.compose(
            conferenceId = topology.conferenceId,
            canonicalRoster = topology.members,
            currentAnchorEpoch = topology.anchorEpoch,
            snapshot = observed.snapshot,
            nowMs = now,
            recoveringModuleIds = emptySet()
        )
    }

    @Test
    fun m01_anchor_m04DirectWhenIce() {
        val p = compose("M01", setOf("M02", "M03", "M04"))
        assertEquals(CppMediaRelation.DIRECT, p.participants.single { it.moduleId == "M04" }.mediaRelation)
        assertEquals(0, p.joiningCount)
        assertEquals(3, star().actualMediaEdges.size)
    }

    @Test
    fun m02_spoke_m04ViaAnchorWhenStarFactUsable() {
        val p = compose(
            "M02",
            setOf("M01"),
            extraFacts = listOf(fact("M03", true), fact("M04", true))
        )
        val m04 = p.participants.single { it.moduleId == "M04" }
        assertEquals(CppMediaRelation.VIA_ANCHOR, m04.mediaRelation)
        assertTrue(m04.mediaConnected)
        assertEquals(CppMediaRelation.DIRECT, p.participants.single { it.moduleId == "M01" }.mediaRelation)
        assertEquals(0, p.joiningCount)
        assertEquals(3, star().actualMediaEdges.size)
    }

    @Test
    fun m02_spoke_m04JoiningWhenStarFactUnusable_iceToAnchorUp() {
        val p = compose(
            "M02",
            setOf("M01"),
            extraFacts = listOf(fact("M04", usable = false), fact("M03", true))
        )
        val m04 = p.participants.single { it.moduleId == "M04" }
        assertEquals(CppMediaRelation.NONE, m04.mediaRelation)
        assertFalse("M04" in p.recoveringPeers)
        val row = ConferencePresenceAvatarBind.resolveAvailability(
            record = m04,
            isLocal = false,
            localCaptureBlocked = false,
            recovering = false
        )
        assertEquals(CppAvatarAvailability.JOINING, row)
        assertEquals(CppMediaRelation.DIRECT, p.participants.single { it.moduleId == "M01" }.mediaRelation)
        assertEquals(3, star().actualMediaEdges.size)
    }

    @Test
    fun m03_spoke_missingM04Fact_failClosedJoining() {
        val p = compose("M03", setOf("M01"))
        val m04 = p.participants.single { it.moduleId == "M04" }
        assertEquals(CppMediaRelation.NONE, m04.mediaRelation)
        assertFalse("M04" in p.recoveringPeers)
        assertFalse(m04.mediaConnected)
        assertEquals(3, star().actualMediaEdges.size)
    }

    @Test
    fun m03_leftoverPeerIceNotDirect() {
        val p = compose(
            "M03",
            setOf("M01", "M02"),
            extraFacts = listOf(fact("M02", true), fact("M04", true))
        )
        assertEquals(CppMediaRelation.VIA_ANCHOR, p.participants.single { it.moduleId == "M04" }.mediaRelation)
        assertEquals(CppMediaRelation.VIA_ANCHOR, p.participants.single { it.moduleId == "M02" }.mediaRelation)
    }

    @Test
    fun m04_self_otherSpokesViaAnchorWhenFactsUsable() {
        val p = compose(
            "M04",
            setOf("M01"),
            extraFacts = listOf(fact("M02", true), fact("M03", true))
        )
        assertEquals(CppMediaRelation.DIRECT, p.participants.single { it.moduleId == "M01" }.mediaRelation)
        assertEquals(CppMediaRelation.VIA_ANCHOR, p.participants.single { it.moduleId == "M02" }.mediaRelation)
        assertEquals(CppMediaRelation.VIA_ANCHOR, p.participants.single { it.moduleId == "M03" }.mediaRelation)
    }

    @Test
    fun spoke_withoutAnchorIce_incidentUnusable() {
        val p = compose("M02", ice = emptySet())
        assertEquals(CppMediaRelation.NONE, p.participants.single { it.moduleId == "M01" }.mediaRelation)
        assertFalse("M01" in p.recoveringPeers)
    }

    @Test
    fun topologyUnchanged_noPeerPairEdges() {
        val before = star().actualMediaEdges.toSet()
        compose("M02", setOf("M01"), extraFacts = listOf(fact("M04", false)))
        assertEquals(before, star().actualMediaEdges)
        assertFalse(before.any { it == MediaEdge("M02", "M04") })
        val targets = RecoveryEdgeProvider.project(star()).targets
        assertTrue(targets.all { it.mediaEdge in before })
    }
}

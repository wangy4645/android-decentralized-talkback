package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1b-4 contract fixtures M1–M8.
 * Media edge admission only — no Coordinator / ICE wiring / Recovery.
 *
 * Exit: M1–M8 PASS → 1b-4 contract PASS → 1b-4 implementation AUTHORIZED.
 */
class ConferenceMediaEdgeAdmissionPhase1b4FixturesTest {

    private val roster4 = listOf("M01", "M02", "M03", "M04")

    private fun anchorSnapshot(
        host: String = "M01",
        anchor: String = "M01",
        meshGeneration: Long = 1L,
        anchorEpoch: Long = 100L
    ): ConferenceTopologySnapshot {
        val result = ConferenceMediaEdgeAdmissionContract.admitFromTopologyProjection(
            AnchorAdmissionInput(
                conferenceId = "c1",
                hostModuleId = host,
                anchorId = anchor,
                members = roster4,
                meshGeneration = meshGeneration,
                anchorEpoch = anchorEpoch
            )
        )
        assertTrue(result is MediaEdgeAdmissionResult.Admitted)
        return (result as MediaEdgeAdmissionResult.Admitted).snapshot
    }

    @Test
    fun m1_topologyProjection_admitsAnchorStarWithoutIce() {
        val snapshot = anchorSnapshot(host = "M02", anchor = "M01")
        assertEquals(
            ConferenceTopologyContract.anchorStarEdges("M01", roster4),
            snapshot.actualMediaEdges
        )
        assertTrue(ConferenceTopologyContract.validateSnapshot(snapshot) is SnapshotValidity.Valid)
        assertFalse(MediaEdgeAdmissionCause.entries.any { it.name.contains("ICE", ignoreCase = true) })
    }

    @Test
    fun m2_icePeerPair_notTopologyEdge_notAdmitted() {
        val snapshot = anchorSnapshot()
        val obs = IceEdgeObservation(
            localModuleId = "M02",
            remoteModuleId = "M03",
            iceConnected = true
        )
        assertEquals(
            IceObservationEvaluation.NotTopologyEdge,
            ConferenceMediaEdgeAdmissionContract.evaluateIceObservation(snapshot, obs)
        )
        assertFalse(ConferenceTopologyContract.isAdmittedByTopology(obs, snapshot))
    }

    @Test
    fun m3_transientIceReconnecting_stillAdmitted_noGenerationBump() {
        val snapshot = anchorSnapshot(meshGeneration = 7L)
        val obs = IceEdgeObservation(
            localModuleId = "M01",
            remoteModuleId = "M02",
            iceConnected = false,
            iceReconnecting = true
        )
        val evaluation = ConferenceMediaEdgeAdmissionContract.evaluateIceObservation(snapshot, obs)
        assertTrue(evaluation is IceObservationEvaluation.StillAdmitted)
        assertTrue(ConferenceMediaEdgeAdmissionContract.topologyUnchangedByIceTransient(snapshot, obs))
        assertEquals(
            7L,
            ConferenceTopologyContract.requiredMeshGenerationAfterEdgeChange(
                snapshot.actualMediaEdges,
                snapshot.actualMediaEdges,
                snapshot.meshGeneration
            )
        )
    }

    @Test
    fun m4_iceConnected_starEdgeNotInSnapshot_observedNotAdmitted() {
        val base = anchorSnapshot()
        val trimmed = base.copy(
            members = listOf("M01", "M02", "M03"),
            actualMediaEdges = ConferenceTopologyContract.anchorStarEdges("M01", listOf("M01", "M02", "M03"))
        )
        val obs = IceEdgeObservation(
            localModuleId = "M01",
            remoteModuleId = "M04",
            iceConnected = true
        )
        val evaluation = ConferenceMediaEdgeAdmissionContract.evaluateIceObservation(trimmed, obs)
        assertTrue(evaluation is IceObservationEvaluation.ConnectedButNotAdmitted)
        assertFalse(ConferenceTopologyContract.isAdmittedByTopology(obs, trimmed))
    }

    @Test
    fun m5_rosterAddMember_authorizedEdgeTransaction_bumpsGeneration() {
        val base = anchorSnapshot(meshGeneration = 5L)
        val result = ConferenceMediaEdgeAdmissionContract.admitFromRosterChange(
            base,
            roster4 + "M05"
        )
        assertTrue(result is MediaEdgeAdmissionResult.Admitted)
        val next = (result as MediaEdgeAdmissionResult.Admitted).snapshot
        assertEquals(6L, next.meshGeneration)
        assertTrue(MediaEdge("M01", "M05") in next.actualMediaEdges)
        assertTrue(ConferenceTopologyContract.validateSnapshot(next) is SnapshotValidity.Valid)
    }

    @Test
    fun m6_rosterRemoveMember_authorizedEdgeTransaction() {
        val base = anchorSnapshot(meshGeneration = 3L)
        val result = ConferenceMediaEdgeAdmissionContract.admitFromRosterChange(
            base,
            listOf("M01", "M02", "M03")
        )
        assertTrue(result is MediaEdgeAdmissionResult.Admitted)
        val next = (result as MediaEdgeAdmissionResult.Admitted).snapshot
        assertEquals(4L, next.meshGeneration)
        assertFalse(next.actualMediaEdges.any { it.remoteModuleId == "M04" })
    }

    @Test
    fun m7_iceDrivenAdmission_rejected() {
        val snapshot = anchorSnapshot()
        val obs = IceEdgeObservation(
            localModuleId = "M01",
            remoteModuleId = "M02",
            iceConnected = true
        )
        val rejected = ConferenceMediaEdgeAdmissionContract.rejectIceDrivenAdmission(snapshot, obs)
        assertTrue(rejected.reason.contains("cannot mutate ActualMediaEdgeSet"))
    }

    @Test
    fun m8_anchorFailover_authorizedEdgeReplacement_notIce() {
        val old = anchorSnapshot(meshGeneration = 31L, anchorEpoch = 10L)
        val result = ConferenceMediaEdgeAdmissionContract.admitFromAnchorFailover(old, "M02")
        assertTrue(result is MediaEdgeAdmissionResult.Admitted)
        val next = (result as MediaEdgeAdmissionResult.Admitted).snapshot
        assertEquals("M02", next.anchorId)
        assertEquals(32L, next.meshGeneration)
        assertEquals(
            ConferenceTopologyContract.anchorStarEdges("M02", roster4),
            next.actualMediaEdges
        )
        assertTrue(ConferenceTopologyContract.isStaleSnapshot(old, next))
    }
}

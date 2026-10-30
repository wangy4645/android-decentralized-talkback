package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2-5 contract fixtures H1–H8.
 * Exit: H1–H8 PASS → 2-5 contract PASS → Health implementation NOT AUTHORIZED until signed.
 */
class ConferenceHealthProjectionPhase25FixturesTest {

    private val members4 = listOf("M01", "M02", "M03", "M04")
    private val starEdge = MediaEdge("M01", "M02")

    private fun anchorSnapshot() = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = "c1",
            hostModuleId = "M01",
            anchorId = "M01",
            members = members4,
            meshGeneration = 5L,
            anchorEpoch = 10L
        )
    )

    @Test
    fun h1_admittedEdgeUsable_mediaUsable() {
        val snapshot = anchorSnapshot()
        val projection = ConferenceHealthProjectionContract.project(
            ConferenceHealthProjectionInput(
                snapshot = snapshot,
                mediaObservations = setOf(
                    MediaEdgeUsabilityObservation(starEdge, usable = true)
                )
            )
        )
        assertTrue(projection.mediaUsable)
        assertTrue(projection.anchorHealthModel)
    }

    @Test
    fun h2_noUsableAdmittedEdge_notMediaUsable() {
        val snapshot = anchorSnapshot()
        val projection = ConferenceHealthProjectionContract.project(
            ConferenceHealthProjectionInput(
                snapshot = snapshot,
                mediaObservations = snapshot.actualMediaEdges.map {
                    MediaEdgeUsabilityObservation(it, usable = false)
                }.toSet()
            )
        )
        assertFalse(projection.mediaUsable)
    }

    @Test
    fun h3_reconnecting_doesNotDropTopologyEdge() {
        val snapshot = anchorSnapshot()
        ConferenceHealthProjectionContract.project(
            ConferenceHealthProjectionInput(
                snapshot = snapshot,
                mediaObservations = setOf(
                    MediaEdgeUsabilityObservation(starEdge, usable = false, reconnecting = true)
                )
            )
        )
        assertTrue(starEdge in snapshot.actualMediaEdges)
        assertTrue(ConferenceTopologyContract.edgeStillAdmitted(starEdge, snapshot))
    }

    @Test
    fun h4_recoveryInFlight_doesNotMutateTopology() {
        val snapshot = anchorSnapshot()
        val before = snapshot.copy(
            members = snapshot.members.toList(),
            actualMediaEdges = snapshot.actualMediaEdges.toSet()
        )
        val projection = ConferenceHealthProjectionContract.project(
            ConferenceHealthProjectionInput(
                snapshot = snapshot,
                mediaObservations = setOf(
                    MediaEdgeUsabilityObservation(starEdge, usable = true)
                ),
                recovery = RecoveryProgressFact(inFlight = true)
            )
        )
        assertEquals(before, snapshot)
        assertTrue(projection.mediaUsable)
        assertTrue(projection.recoveryInFlight)
    }

    @Test
    fun h5_recoverySuccess_healthMayRecover() {
        val snapshot = anchorSnapshot()
        val projection = ConferenceHealthProjectionContract.project(
            ConferenceHealthProjectionInput(
                snapshot = snapshot,
                mediaObservations = setOf(
                    MediaEdgeUsabilityObservation(starEdge, usable = true)
                ),
                recovery = RecoveryProgressFact(succeeded = true)
            )
        )
        assertTrue(projection.mediaUsable)
        assertEquals(snapshot.actualMediaEdges, anchorSnapshot().actualMediaEdges)
    }

    @Test
    fun h6_recoveryFailure_reflectsMedia_notTopology() {
        val snapshot = anchorSnapshot()
        val projection = ConferenceHealthProjectionContract.project(
            ConferenceHealthProjectionInput(
                snapshot = snapshot,
                mediaObservations = setOf(
                    MediaEdgeUsabilityObservation(starEdge, usable = false)
                ),
                recovery = RecoveryProgressFact(failed = true)
            )
        )
        assertFalse(projection.mediaUsable)
        assertTrue(projection.recoveryFailed)
        assertTrue(starEdge in snapshot.actualMediaEdges)
    }

    @Test
    fun h7_mesh_doesNotApplyAnchorHealth() {
        val mesh = ConferenceTopologyModeTransitionContract.composeMeshSnapshot(
            conferenceId = "c1",
            hostModuleId = "M01",
            members = listOf("M01", "M02", "M03"),
            rosterEpoch = 1L,
            meshGeneration = 2L
        )
        val projection = ConferenceHealthProjectionContract.project(
            ConferenceHealthProjectionInput(
                snapshot = mesh,
                mediaObservations = setOf(
                    MediaEdgeUsabilityObservation(MediaEdge("M01", "M02"), usable = true)
                )
            )
        )
        assertFalse(projection.anchorHealthModel)
        assertEquals(ConferenceTopologyMode.MESH, projection.topologyMode)
        assertFalse(projection.mediaUsable)
        assertTrue(mesh.actualMediaEdges.isEmpty())
    }

    @Test
    fun h8_health_readOnly_noTargetsOrEdgeMutation() {
        val snapshot = anchorSnapshot()
        val beforeEdges = snapshot.actualMediaEdges.toSet()
        ConferenceHealthProjectionContract.project(
            ConferenceHealthProjectionInput(snapshot = snapshot)
        )
        assertEquals(beforeEdges, snapshot.actualMediaEdges)
        val targets = RecoveryEdgeProvider.project(snapshot).targets
        assertTrue(targets.all { it.mediaEdge in beforeEdges })
    }
}

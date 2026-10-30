package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 UI contract fixtures U1–U8. Implementation NOT AUTHORIZED.
 */
class ConferenceHealthUiPhase3FixturesTest {

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

    private fun health(
        snapshot: ConferenceTopologySnapshot,
        usable: Boolean,
        inFlight: Boolean = false
    ): ConferenceHealth {
        val projection = ConferenceHealthProjectionContract.project(
            ConferenceHealthProjectionInput(
                snapshot = snapshot,
                mediaObservations = setOf(
                    MediaEdgeUsabilityObservation(starEdge, usable = usable)
                ),
                recovery = RecoveryProgressFact(inFlight = inFlight)
            )
        )
        return ConferenceHealth.from(snapshot, projection)
    }

    @Test
    fun u1_mediaUsable_roomOnline() {
        val snapshot = anchorSnapshot()
        val ui = ConferenceHealthUiProjectionContract.project(
            ConferenceHealthUiInput(health = health(snapshot, usable = true))
        )
        assertEquals(ConferenceRoomFacing.ONLINE, ui.roomFacing)
        assertTrue(ui.roomOnline)
    }

    @Test
    fun u2_noUsableMedia_notOnline() {
        val snapshot = anchorSnapshot()
        val ui = ConferenceHealthUiProjectionContract.project(
            ConferenceHealthUiInput(health = health(snapshot, usable = false))
        )
        assertEquals(ConferenceRoomFacing.NOT_ONLINE, ui.roomFacing)
    }

    @Test
    fun u3_recoveryInFlight_doesNotOverrideOnline() {
        val snapshot = anchorSnapshot()
        val ui = ConferenceHealthUiProjectionContract.project(
            ConferenceHealthUiInput(health = health(snapshot, usable = true, inFlight = true))
        )
        assertTrue(ui.roomOnline)
        assertTrue(ui.recoveryInFlightDiagnostic)
    }

    @Test
    fun u4_mesh_notAnchorRoomOnline() {
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
        val ui = ConferenceHealthUiProjectionContract.project(
            ConferenceHealthUiInput(health = ConferenceHealth.from(mesh, projection))
        )
        assertFalse(projection.anchorHealthModel)
        assertEquals(ConferenceRoomFacing.NOT_ONLINE, ui.roomFacing)
    }

    @Test
    fun u5_ui_readOnly_noTargetsOrEdgeMutation() {
        val snapshot = anchorSnapshot()
        val before = snapshot.actualMediaEdges.toSet()
        ConferenceHealthUiProjectionContract.project(
            ConferenceHealthUiInput(health = health(snapshot, usable = true))
        )
        assertEquals(before, snapshot.actualMediaEdges)
        val targets = RecoveryEdgeProvider.project(snapshot).targets
        assertTrue(targets.all { it.mediaEdge in before })
    }

    @Test
    fun u6_runtimeRecovering_doesNotOverrideOnline() {
        val snapshot = anchorSnapshot()
        val runtime = ConferenceRuntimeState(
            phase = ConferenceRuntimePhase.RECOVERING,
            mediaRecovering = true,
            edgeRecovering = true,
            conferenceDegraded = true
        )
        val ui = ConferenceHealthUiProjectionContract.project(
            ConferenceHealthUiInput(
                health = health(snapshot, usable = true, inFlight = true),
                runtime = runtime
            )
        )
        assertEquals(ConferenceRoomFacing.ONLINE, ui.roomFacing)
        assertTrue(ui.recoveryInFlightDiagnostic)
    }

    @Test
    fun u7_peerChrome_isNotRoomSyncing() {
        val snapshot = anchorSnapshot()
        val ui = ConferenceHealthUiProjectionContract.project(
            ConferenceHealthUiInput(
                health = health(snapshot, usable = true, inFlight = true),
                recoveringPeerIds = setOf("M02")
            )
        )
        assertEquals(ConferenceRoomFacing.ONLINE, ui.roomFacing)
        assertEquals(setOf("M02"), ui.recoveringPeerChrome)
        assertFalse(ui.roomFacing.name == "SYNCING")
    }

    @Test
    fun u8_nullHealth_notOnline_noTopologyInvent() {
        val ui = ConferenceHealthUiProjectionContract.project(
            ConferenceHealthUiInput(health = null)
        )
        assertEquals(ConferenceRoomFacing.NOT_ONLINE, ui.roomFacing)
        assertTrue(ui.recoveringPeerChrome.isEmpty())
    }
}

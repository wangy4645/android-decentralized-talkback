package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2-5 Health Integration Gate. Binder only; R28 / Provider / topology writes untouched.
 */
class ConferenceHealthPhase25IntegrationTest {

    private val members4 = listOf("M01", "M02", "M03", "M04")
    private val localHost = "M01"
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

    private fun iceMap(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    private fun bind(
        snapshot: ConferenceTopologySnapshot,
        ice: (String) -> String?,
        recovery: EdgeRecoveryFacts = EdgeRecoveryFacts()
    ) = ConferenceHealthBinder.project(
        snapshot,
        localHost,
        ice,
        recovery,
        sessionEstablished = true,
    )!!

    @Test
    fun i1_admittedIceConnected_mediaUsableOnline() {
        val snapshot = anchorSnapshot()
        val health = bind(snapshot, iceMap("M02" to "CONNECTED"))
        assertTrue(health.mediaUsable)
        assertTrue(health.userFacingOnline)
        assertTrue(health.anchorHealthModel)
    }

    @Test
    fun i2_noUsableAdmittedIce_notMediaUsable() {
        val snapshot = anchorSnapshot()
        val health = bind(
            snapshot,
            iceMap("M02" to "FAILED", "M03" to "FAILED", "M04" to "FAILED")
        )
        assertFalse(health.mediaUsable)
        assertFalse(health.userFacingOnline)
    }

    @Test
    fun i3_reconnecting_doesNotDropTopologyEdge() {
        val snapshot = anchorSnapshot()
        val before = snapshot.actualMediaEdges.toSet()
        bind(snapshot, iceMap("M02" to "CHECKING"))
        assertEquals(before, snapshot.actualMediaEdges)
        assertTrue(starEdge in snapshot.actualMediaEdges)
    }

    @Test
    fun i4_recoveryInFlight_doesNotBlockOnlineWhenMediaUsable() {
        val snapshot = anchorSnapshot()
        val health = bind(
            snapshot,
            iceMap("M02" to "COMPLETED"),
            EdgeRecoveryFacts(recoveringRemoteModuleIds = setOf("M02"), anyRecovering = true)
        )
        assertTrue(health.mediaUsable)
        assertTrue(health.userFacingOnline)
        assertTrue(health.recoveryInFlight)
        assertTrue(starEdge in snapshot.actualMediaEdges)
    }

    @Test
    fun i5_iceRestored_healthMayRecover() {
        val snapshot = anchorSnapshot()
        val down = bind(snapshot, iceMap("M02" to "FAILED"))
        assertFalse(down.mediaUsable)
        val up = bind(snapshot, iceMap("M02" to "CONNECTED"))
        assertTrue(up.mediaUsable)
        assertTrue(up.userFacingOnline)
        assertEquals(anchorSnapshot().actualMediaEdges, snapshot.actualMediaEdges)
    }

    @Test
    fun i6_recoveryFailure_reflectsMedia_notTopology() {
        val snapshot = anchorSnapshot()
        val health = bind(
            snapshot,
            iceMap("M02" to "FAILED"),
            EdgeRecoveryFacts(
                failedRemoteModuleIds = setOf("M02"),
                anyFailedMediaRecovery = true
            )
        )
        assertFalse(health.mediaUsable)
        assertTrue(health.recoveryFailed)
        assertTrue(starEdge in snapshot.actualMediaEdges)
    }

    @Test
    fun i7_mesh_doesNotApplyAnchorHealth() {
        val mesh = ConferenceTopologyModeTransitionContract.composeMeshSnapshot(
            conferenceId = "c1",
            hostModuleId = "M01",
            members = listOf("M01", "M02", "M03"),
            rosterEpoch = 1L,
            meshGeneration = 2L
        )
        val health = bind(mesh, iceMap("M02" to "CONNECTED"))
        assertFalse(health.anchorHealthModel)
        assertEquals(ConferenceTopologyMode.MESH, health.topologyMode)
        assertFalse(health.mediaUsable)
        assertFalse(health.userFacingOnline)
        assertTrue(mesh.actualMediaEdges.isEmpty())
    }

    @Test
    fun i8_health_readOnly_noTargetsFromHealth() {
        val snapshot = anchorSnapshot()
        val before = snapshot.actualMediaEdges.toSet()
        val health = ConferenceHealthBinder.project(
            snapshot,
            localHost,
            iceMap("M02" to "CONNECTED"),
            EdgeRecoveryFacts()
        )!!
        assertEquals(before, snapshot.actualMediaEdges)
        assertEquals(health.mediaUsable, health.userFacingOnline)
        val targets = RecoveryEdgeProvider.project(snapshot).targets
        assertTrue(targets.all { it.mediaEdge in before })
    }

    @Test
    fun i9_nullSnapshot_noHealth() {
        assertNull(
            ConferenceHealthBinder.project(
                snapshot = null,
                localModuleId = localHost,
                iceStateForModule = { "CONNECTED" },
                recoveryFacts = EdgeRecoveryFacts()
            )
        )
    }
}

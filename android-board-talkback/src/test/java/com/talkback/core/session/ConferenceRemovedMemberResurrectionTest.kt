package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceRemovedMemberResurrectionTest {

    private val members4 = listOf("M01", "M02", "M03", "M04")

    private fun staleTopologySnapshotWithM04Edge(): ConferenceTopologySnapshot =
        ConferenceTopologyContract.composeAnchorAdmission(
            AnchorAdmissionInput(
                conferenceId = "c1",
                hostModuleId = "M01",
                anchorId = "M01",
                members = members4
            )
        )

    private fun admittedRosterAfterM04Leave(): Set<String> = setOf("M01", "M02", "M03")

    @Test
    fun t1_staleTopologyEdgeExcludedWhenRosterPruned() {
        val snapshot = staleTopologySnapshotWithM04Edge()
        assertTrue(
            ConferenceMediaEdgeRealizationContract.offerRemotesIfAnchor("M01", snapshot)
                .contains("M04")
        )
        val remotes = ConferenceMediaEdgeRealizationContract.realizationRemotesForAdmittedMembers(
            localModuleId = "M01",
            snapshot = snapshot,
            admittedModuleIds = admittedRosterAfterM04Leave(),
        )
        assertFalse(remotes.contains("M04"))
        assertEquals(setOf("M02", "M03"), remotes)
    }

    @Test
    fun t2_failClosedAcceptMustNotRestoreRoster() {
        assertFalse(
            ConferenceGroupAcceptRosterMutationSupport
                .mayRestoreParticipantAfterHostRealizationAccept(
                    ConferenceHostGroupAcceptSupport.Action.FAIL_CLOSED_RECOVERY
                )
        )
        val manager = ConferenceParticipantManager()
        val sessionId = "CONF-resurrection"
        val local = EndpointAddress(ModuleId("M01"), EndpointId("E01"))
        manager.initSession(
            sessionId,
            local,
            members4.map { ModuleId(it) }.map {
                EndpointAddress(it, EndpointId("E01"))
            }
        )
        manager.applyPrune(sessionId, "M04")
        assertFalse(manager.containsParticipant(sessionId, "M04"))
        assertTrue(manager.leftMemberEndpoints(sessionId)?.containsKey("M04") == true)
    }

    @Test
    fun t3_authorizedLateRejoinStillRestoresRoster() {
        assertTrue(
            ConferenceGroupAcceptRosterMutationSupport
                .mayRestoreParticipantAfterHostRealizationAccept(
                    ConferenceHostGroupAcceptSupport.Action.APPLY_TO_EXISTING_ENGINE
                )
        )
        val manager = ConferenceParticipantManager()
        val sessionId = "CONF-late-rejoin"
        val local = EndpointAddress(ModuleId("M01"), EndpointId("E01"))
        val m04 = EndpointAddress(ModuleId("M04"), EndpointId("E04"))
        manager.initSession(sessionId, local, listOf(local, m04))
        manager.applyPrune(sessionId, "M04")
        manager.leftMemberEndpoints(sessionId)?.remove("M04")
        manager.onLateJoin(sessionId, "M04", m04)
        assertTrue(manager.containsParticipant(sessionId, "M04"))
        assertTrue(manager.roster(sessionId).any { it.moduleId.value == "M04" })
    }

    @Test
    fun t4_admittedPeersStillEligibleForRealization() {
        val snapshot = staleTopologySnapshotWithM04Edge()
        val remotes = ConferenceMediaEdgeRealizationContract.realizationRemotesForAdmittedMembers(
            localModuleId = "M01",
            snapshot = snapshot,
            admittedModuleIds = admittedRosterAfterM04Leave(),
        )
        assertEquals(setOf("M02", "M03"), remotes)
    }
}

package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceLeaveRejoinTombstoneTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m03 = ModuleId("M03")
    private val m04 = ModuleId("M04")
    private val m05 = ModuleId("M05")
    private val local = EndpointAddress(m01, EndpointId("E01"))
    private val e02 = EndpointAddress(m02, EndpointId("E01"))
    private val e03 = EndpointAddress(m03, EndpointId("E01"))
    private val e04 = EndpointAddress(m04, EndpointId("E01"))
    private val e05 = EndpointAddress(m05, EndpointId("E01"))

    private fun allFive() = listOf(local, e02, e03, e04, e05)

    private fun fourWithoutM05() = listOf(local, e02, e03, e04)

    private fun project(manager: ConferenceParticipantManager, sessionId: String): ConferenceParticipantProjector.Output {
        val snap = manager.snapshot(sessionId, m01)
        val left = manager.leftMemberEndpoints(sessionId)?.keys ?: emptySet()
        return ConferenceParticipantProjector.project(
            ConferenceParticipantProjector.Input(
                localModuleId = m01,
                localKey = local.key,
                sessionAccepted = true,
                roster = snap.roster,
                memberViews = snap.memberViews,
                leftModuleIds = left,
            )
        )
    }

    private fun seedJoined(manager: ConferenceParticipantManager, sessionId: String, members: List<EndpointAddress>) {
        manager.initSession(sessionId, local, members)
        manager.applyCanonicalMembershipRoster(sessionId, members, m01)
    }

    @Test
    fun firstJoin_showsRemoteTile() {
        val manager = ConferenceParticipantManager()
        seedJoined(manager, "conf-first", allFive())
        val out = project(manager, "conf-first")
        assertTrue(out.visibleParticipants.any { it.moduleId == "M05" })
        assertEquals(5, out.joinedParticipantCount)
    }

    @Test
    fun leave_hidesRemoteTileAndKeepsTombstone() {
        val manager = ConferenceParticipantManager()
        seedJoined(manager, "conf-leave", allFive())
        manager.applyPrune("conf-leave", "M05")
        val out = project(manager, "conf-leave")
        assertTrue(manager.leftMemberEndpoints("conf-leave")?.containsKey("M05") == true)
        assertTrue(out.visibleParticipants.none { it.moduleId == "M05" })
        assertEquals(4, out.joinedParticipantCount)
    }

    @Test
    fun appliedRejoinSnapshot_clearsOnlyReadmittedTombstone() {
        val manager = ConferenceParticipantManager()
        seedJoined(manager, "conf-rejoin", allFive())
        manager.applyPrune("conf-rejoin", "M04")
        manager.applyPrune("conf-rejoin", "M05")
        manager.applyCanonicalMembershipRoster("conf-rejoin", allFive(), m01)
        val cleared = manager.clearLeaveTombstonesForAdmittedMembers(
            "conf-rejoin",
            allFive().map { it.moduleId.value }.toSet(),
        )
        assertEquals(setOf("M04", "M05"), cleared)
        assertTrue(manager.leftMemberEndpoints("conf-rejoin").isNullOrEmpty())
        val out = project(manager, "conf-rejoin")
        assertTrue(out.visibleParticipants.any { it.moduleId == "M05" })
        assertTrue(out.visibleParticipants.any { it.moduleId == "M04" })
        assertEquals(5, out.joinedParticipantCount)
    }

    @Test
    fun snapshotWithoutLeaver_doesNotResurrectTombstone() {
        val manager = ConferenceParticipantManager()
        seedJoined(manager, "conf-true-leave", allFive())
        manager.applyPrune("conf-true-leave", "M05")
        manager.applyCanonicalMembershipRoster("conf-true-leave", fourWithoutM05(), m01)
        val cleared = manager.clearLeaveTombstonesForAdmittedMembers(
            "conf-true-leave",
            fourWithoutM05().map { it.moduleId.value }.toSet(),
        )
        assertTrue(cleared.isEmpty())
        assertTrue(manager.leftMemberEndpoints("conf-true-leave")?.containsKey("M05") == true)
        val out = project(manager, "conf-true-leave")
        assertTrue(out.visibleParticipants.none { it.moduleId == "M05" })
        assertEquals(4, out.joinedParticipantCount)
    }

    @Test
    fun canonicalRosterWithoutClearingTombstone_stillHidesTile() {
        val manager = ConferenceParticipantManager()
        seedJoined(manager, "conf-stale", allFive())
        manager.applyPrune("conf-stale", "M05")
        manager.applyCanonicalMembershipRoster("conf-stale", allFive(), m01)
        assertTrue(manager.leftMemberEndpoints("conf-stale")?.containsKey("M05") == true)
        val out = project(manager, "conf-stale")
        assertTrue(out.visibleParticipants.none { it.moduleId == "M05" })
        assertEquals(4, out.joinedParticipantCount)
    }

    @Test
    fun otherSessionSnapshot_doesNotClearThisSessionTombstone() {
        val manager = ConferenceParticipantManager()
        seedJoined(manager, "conf-a", allFive())
        seedJoined(manager, "conf-b", allFive())
        manager.applyPrune("conf-a", "M05")
        manager.applyCanonicalMembershipRoster("conf-b", allFive(), m01)
        manager.clearLeaveTombstonesForAdmittedMembers(
            "conf-b",
            allFive().map { it.moduleId.value }.toSet(),
        )
        assertTrue(manager.leftMemberEndpoints("conf-a")?.containsKey("M05") == true)
        assertTrue(project(manager, "conf-a").visibleParticipants.none { it.moduleId == "M05" })
    }
}

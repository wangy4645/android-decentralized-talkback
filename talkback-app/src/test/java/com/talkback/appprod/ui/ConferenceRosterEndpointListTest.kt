package com.talkback.appprod.ui

import com.talkback.app.TalkbackSessionSnapshot
import com.talkback.core.ptt.PttState
import com.talkback.core.session.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ConferenceRosterEndpointListTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val members = listOf("M01", "M02", "M03", "M04", "M05")

    @Before
    fun setUp() {
        MeetingPresenceDisplay.receivePathLivenessProvider = NoOpReceivePathLivenessProvider
    }

    @After
    fun tearDown() {
        MeetingPresenceDisplay.receivePathLivenessProvider = NoOpReceivePathLivenessProvider
    }

    @Test
    fun m03Host_m02View_visibleOnlySelfAndHost_listsAllFiveJoinedIds() {
        val session = anchorSession(host = "M03", anchor = "M03", visible = sparseM02M03Visible())
        val rows = ConferenceRosterEndpointList.planRows(
            context = context,
            session = session,
            localModuleId = "M02",
            speakingModuleId = null,
            conferenceMuted = false,
            mediaUnavailablePeer = { false },
        )
        assertEquals(members.toSet(), rows.map { it.moduleId }.toSet())
        assertEquals(5, rows.size)
    }

    @Test
    fun m03Host_m02View_visibleFailure_keepsFiveRowsWithDegradedOnFailedPeer() {
        val visible = listOf(
            view("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTED, isLocal = true),
            view("M03", ConferenceParticipantDisplayState.VISIBLE_CONNECTED),
            view("M04", ConferenceParticipantDisplayState.VISIBLE_EDGE_FAILED),
        )
        val session = anchorSession(host = "M03", anchor = "M03", visible = visible)
        val rows = ConferenceRosterEndpointList.planRows(
            context = context,
            session = session,
            localModuleId = "M02",
            speakingModuleId = null,
            conferenceMuted = false,
            mediaUnavailablePeer = { false },
        )
        assertEquals(5, rows.size)
        assertEquals(EndpointStatus.DEGRADED, rows.single { it.moduleId == "M04" }.endpointStatus)
    }

    @Test
    fun participantLeft_reducesRosterToFour() {
        val presence = fieldPresence().copy(
            joinedCount = 4,
            connectedCount = 4,
            participants = fieldPresence().participants.map {
                if (it.moduleId == "M05") {
                    it.copy(membership = CppMembership.NOT_JOINED, mediaRelation = CppMediaRelation.NONE)
                } else {
                    it
                }
            },
        )
        val session = anchorSession(host = "M03", anchor = "M03", visible = sparseM02M03Visible())
            .copy(conferencePresenceProjection = presence)
        val rows = ConferenceRosterEndpointList.planRows(
            context = context,
            session = session,
            localModuleId = "M02",
            speakingModuleId = null,
            conferenceMuted = false,
            mediaUnavailablePeer = { false },
        )
        assertEquals(setOf("M01", "M02", "M03", "M04"), rows.map { it.moduleId }.toSet())
        assertEquals(4, rows.size)
    }

    @Test
    fun m03Host_fieldLogSparseVisible_fiveIdsWithPerRowStatus() {
        val presence = ConferencePresenceProjection(
            joinedCount = 5,
            connectedCount = 4,
            participants = listOf(
                record("M03", CppMediaRelation.DIRECT),
                record("M01", CppMediaRelation.NONE),
                record("M02", CppMediaRelation.DIRECT),
                record("M04", CppMediaRelation.VIA_ANCHOR),
                record("M05", CppMediaRelation.VIA_ANCHOR),
            ),
        )
        val session = anchorSession(host = "M03", anchor = "M03", visible = sparseM02M03Visible())
            .copy(conferencePresenceProjection = presence)
        val rows = ConferenceRosterEndpointList.planRows(
            context = context,
            session = session,
            localModuleId = "M02",
            speakingModuleId = null,
            conferenceMuted = false,
            mediaUnavailablePeer = { false },
        )
        assertEquals(members.toSet(), rows.map { it.moduleId }.toSet())
        assertEquals(5, rows.size)
        val byId = rows.associateBy { it.moduleId }
        assertEquals(EndpointStatus.ONLINE, byId.getValue("M02").endpointStatus)
        assertEquals(EndpointStatus.ONLINE, byId.getValue("M03").endpointStatus)
        assertEquals(EndpointStatus.CONNECTING, byId.getValue("M01").endpointStatus)
        assertEquals(EndpointStatus.ONLINE, byId.getValue("M04").endpointStatus)
        assertEquals(EndpointStatus.ONLINE, byId.getValue("M05").endpointStatus)
    }

    @Test
    fun m01Host_fullVisible_listsAllFive_moduleIds() {
        val session = anchorSession(
            host = "M01",
            anchor = "M01",
            visible = members.map {
                view(it, ConferenceParticipantDisplayState.VISIBLE_CONNECTED, isLocal = it == "M02")
            },
        )
        val rows = ConferenceRosterEndpointList.planRows(
            context = context,
            session = session,
            localModuleId = "M02",
            speakingModuleId = null,
            conferenceMuted = false,
            mediaUnavailablePeer = { false },
        )
        assertEquals(members.toSet(), rows.map { it.moduleId }.toSet())
    }

    private fun sparseM02M03Visible() = listOf(
        view("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTED, isLocal = true),
        view("M03", ConferenceParticipantDisplayState.VISIBLE_CONNECTED),
    )

    private fun fieldPresence(): ConferencePresenceProjection {
        val records = listOf(
            record("M03", CppMediaRelation.DIRECT),
            record("M01", CppMediaRelation.VIA_ANCHOR),
            record("M02", CppMediaRelation.DIRECT),
            record("M04", CppMediaRelation.VIA_ANCHOR),
            record("M05", CppMediaRelation.VIA_ANCHOR),
        )
        return ConferencePresenceProjection(
            joinedCount = 5,
            connectedCount = 5,
            participants = records,
        )
    }

    private fun record(moduleId: String, relation: CppMediaRelation) =
        ParticipantPresenceRecord(moduleId, CppMembership.JOINED, relation, CppEvidence.FRESH)

    private fun anchorSession(
        host: String,
        anchor: String,
        visible: List<ConferenceParticipantViewState>,
    ): TalkbackSessionSnapshot {
        val topology = ConferenceTopologyContract.composeAnchorAdmission(
            AnchorAdmissionInput("meeting", host, anchor, members),
        )
        return TalkbackSessionSnapshot(
            sessionId = "meeting",
            type = SessionType.CONFERENCE,
            channelId = "CH-01",
            protocolFloorOwnerKey = null,
            localPttState = PttState.IDLE,
            memberKeys = members,
            memberViews = members.map { MemberView(it, it, InviteState.ACCEPTED, MediaState.CONNECTING) },
            visibleParticipants = visible,
            conferenceTopologySnapshot = topology,
            conferencePresenceProjection = fieldPresence(),
        )
    }

    private fun view(
        moduleId: String,
        display: ConferenceParticipantDisplayState,
        isLocal: Boolean = false,
    ) = ConferenceParticipantViewState(moduleId, moduleId, display, isLocal)
}
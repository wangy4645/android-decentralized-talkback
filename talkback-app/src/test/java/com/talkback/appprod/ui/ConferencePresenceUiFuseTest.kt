package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceParticipantDisplayState
import com.talkback.core.session.ConferenceParticipantViewState
import com.talkback.core.session.ConferencePresenceProjection
import com.talkback.core.session.CppEvidence
import com.talkback.core.session.CppMediaRelation
import com.talkback.core.session.CppMembership
import com.talkback.core.session.ParticipantPresenceRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConferencePresenceUiFuseTest {

    @Before
    fun setUp() {
        MeetingPresenceDisplay.receivePathLivenessProvider = object : ReceivePathLivenessProvider {
            override fun receivePathLive(sessionId: String, remoteModuleId: String): Boolean =
                remoteModuleId in setOf("M01", "M02", "M03", "M04")

            override fun mediaEverLive(sessionId: String, remoteModuleId: String): Boolean =
                remoteModuleId in setOf("M01", "M02")
        }
    }

    private fun presence(vararg peers: ParticipantPresenceRecord) = ConferencePresenceProjection(
        joinedCount = peers.size,
        connectedCount = peers.count { it.mediaConnected },
        participants = peers.toList(),
    )

    @Test
    fun joiningHint_excludesClassifiedFailureTerminal() {
        val projection = presence(
            ParticipantPresenceRecord("M01", CppMembership.JOINED, CppMediaRelation.VIA_ANCHOR, CppEvidence.FRESH),
            ParticipantPresenceRecord("M03", CppMembership.JOINED, CppMediaRelation.NONE, CppEvidence.UNKNOWN),
            ParticipantPresenceRecord("M04", CppMembership.JOINED, CppMediaRelation.NONE, CppEvidence.UNKNOWN),
        )
        val failures = setOf("M03", "M04")
        assertNull(ConferencePresenceUiFuse.joiningHint(projection, failures, localCaptureBlocked = false))
    }

    @Test
    fun classifiedFailureModuleIds_detectsEdgeAndDomain() {
        val visible = listOf(
            view("M03", ConferenceParticipantDisplayState.VISIBLE_EDGE_FAILED),
            view("M04", ConferenceParticipantDisplayState.VISIBLE_DOMAIN_BLOCKED),
            view("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTED),
        )
        assertEquals(setOf("M03", "M04"), ConferencePresenceUiFuse.classifiedFailureModuleIds(visible))
    }

    @Test
    fun fieldReplay_failureTerminals_degraded_noJoiningNoRecovering() {
        val presence = ConferencePresenceProjection(
            joinedCount = 4,
            connectedCount = 2,
            recoveringPeers = emptySet(),
            participants = listOf(
                ParticipantPresenceRecord("M01", CppMembership.JOINED, CppMediaRelation.VIA_ANCHOR, CppEvidence.FRESH),
                ParticipantPresenceRecord("M02", CppMembership.JOINED, CppMediaRelation.DIRECT, CppEvidence.FRESH),
                ParticipantPresenceRecord("M03", CppMembership.JOINED, CppMediaRelation.NONE, CppEvidence.UNKNOWN),
                ParticipantPresenceRecord("M04", CppMembership.JOINED, CppMediaRelation.NONE, CppEvidence.UNKNOWN),
            )
        )
        val visible = listOf(
            view("M01", ConferenceParticipantDisplayState.VISIBLE_LOCAL, isLocal = true),
            view("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTED),
            view("M03", ConferenceParticipantDisplayState.VISIBLE_EDGE_FAILED),
            view("M04", ConferenceParticipantDisplayState.VISIBLE_DOMAIN_BLOCKED),
        )
        val failureIds = ConferencePresenceUiFuse.classifiedFailureModuleIds(visible)
        assertEquals(setOf("M03", "M04"), failureIds)
        assertTrue(presence.recoveringPeers.isEmpty())
        assertNull(ConferencePresenceUiFuse.joiningHint(presence, failureIds, false))

        val facts = visible.map { participant ->
            MeetingPresenceDisplay.ParticipantPresentationFacts(
                sessionId = "b05588db",
                moduleId = participant.moduleId,
                isLocal = participant.isLocal,
                displayState = participant.displayState,
                isRecoveringPeer = false,
                mediaUnavailablePeer = false,
                speaking = false,
            )
        }
        val ui = MeetingPresenceDisplay.renderConferencePresence(presence, facts)
        assertEquals(EndpointStatus.DEGRADED, ui.avatarStatuses["M03"])
        assertEquals(EndpointStatus.DEGRADED, ui.avatarStatuses["M04"])
        assertNull(ui.connectingHint)
    }

    private fun view(
        moduleId: String,
        displayState: ConferenceParticipantDisplayState,
        isLocal: Boolean = false,
    ) = ConferenceParticipantViewState(
        key = "$moduleId-1",
        moduleId = moduleId,
        displayState = displayState,
        isLocal = isLocal,
    )
}

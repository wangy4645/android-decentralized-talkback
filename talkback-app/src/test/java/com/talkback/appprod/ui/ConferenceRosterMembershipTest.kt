package com.talkback.appprod.ui

import com.talkback.app.TalkbackSessionSnapshot
import com.talkback.core.ptt.PttState
import com.talkback.core.session.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceRosterMembershipTest {
    private val members = listOf("M01", "M02", "M03", "M04", "M05")

    private fun session(host: String, anchor: String): TalkbackSessionSnapshot {
        val topology = ConferenceTopologyContract.composeAnchorAdmission(
            AnchorAdmissionInput("meeting", host, anchor, members)
        )
        val records = members.map {
            ParticipantPresenceRecord(it, CppMembership.JOINED, CppMediaRelation.NONE, CppEvidence.UNKNOWN)
        }
        return TalkbackSessionSnapshot(
            sessionId = "meeting", type = SessionType.CONFERENCE, channelId = "CH-01",
            protocolFloorOwnerKey = null, localPttState = PttState.IDLE, memberKeys = members,
            memberViews = members.map { MemberView(it, it, InviteState.ACCEPTED, MediaState.CONNECTING) },
            visibleParticipants = members.map {
                ConferenceParticipantViewState(it, it, ConferenceParticipantDisplayState.VISIBLE_CONNECTING, false)
            },
            conferenceTopologySnapshot = topology,
            conferencePresenceProjection = ConferencePresenceProjection(5, 0, participants = records)
        )
    }

    @Test fun joinedSpokesWithoutLocalIceFollowEdgesForEitherHost() {
        for (host in listOf("M01", "M03")) {
            val s = session(host, host)
            assertEquals(members.toSet() - host - "M02", joinedIndirectSpokes(s, "M02"))
            assertTrue(joinedIndirectSpokes(s, host).isEmpty())
        }
    }

    @Test fun hostCanAlsoBeASpoke() {
        assertEquals(setOf("M02", "M04", "M05"), joinedIndirectSpokes(session("M03", "M01"), "M03"))
    }

    @Test fun preservePendingRecoveryFailureAndSessionBoundary() {
        val s = session("M03", "M03")
        val p = requireNotNull(s.conferencePresenceProjection)
        val updated = p.copy(recoveringPeers = setOf("M01"), participants = p.participants.map {
            when (it.moduleId) {
                "M04" -> it.copy(membership = CppMembership.PENDING)
                "M05" -> it.copy(mediaRelation = CppMediaRelation.DEGRADED)
                else -> it
            }
        })
        assertTrue(joinedIndirectSpokes(s.copy(conferencePresenceProjection = updated), "M02").isEmpty())
        assertTrue(joinedIndirectSpokes(s.copy(sessionId = "later"), "M02").isEmpty())
        assertTrue(joinedIndirectSpokes(s.copy(conferenceTopologySnapshot = null), "M02").isEmpty())
    }
}

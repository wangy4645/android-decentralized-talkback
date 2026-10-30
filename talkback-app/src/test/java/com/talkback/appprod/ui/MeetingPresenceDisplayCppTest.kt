package com.talkback.appprod.ui

import com.talkback.core.session.ConferencePresenceProjection
import com.talkback.core.session.CppEvidence
import com.talkback.core.session.CppMediaRelation
import com.talkback.core.session.CppMembership
import com.talkback.core.session.ParticipantPresenceRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeetingPresenceDisplayCppTest {

    private fun record(
        moduleId: String,
        membership: CppMembership = CppMembership.JOINED,
        media: CppMediaRelation = CppMediaRelation.NONE,
        evidence: CppEvidence = CppEvidence.UNKNOWN
    ) = ParticipantPresenceRecord(moduleId, membership, media, evidence)

    private fun projection(vararg records: ParticipantPresenceRecord): ConferencePresenceProjection {
        val list = records.toList()
        return ConferencePresenceProjection(
            joinedCount = list.count { it.membership == CppMembership.JOINED },
            connectedCount = list.count { it.mediaConnected },
            participants = list
        )
    }

    @Test
    fun renderFromProjection_joinedNoneUnknown_showsConnectingNotOffline() {
        val p = projection(
            record("M01", media = CppMediaRelation.VIA_ANCHOR, evidence = CppEvidence.FRESH),
            record("M02", media = CppMediaRelation.DIRECT, evidence = CppEvidence.FRESH),
            record("M03", media = CppMediaRelation.DIRECT, evidence = CppEvidence.FRESH),
            record("M04")
        )
        val ui = MeetingPresenceDisplay.renderFromProjection(
            projection = p,
            localModuleId = "M01",
            speakingModuleId = null,
            localCaptureBlocked = false
        )
        assertEquals(4, ui.participantStates.size)
        assertEquals(EndpointStatus.CONNECTING, ui.avatarStatuses["M04"])
        assertEquals("M04 joining...", ui.connectingHint)
    }

    @Test
    fun renderFromProjection_allConnected_noHint() {
        val p = projection(
            record("M01", media = CppMediaRelation.VIA_ANCHOR, evidence = CppEvidence.FRESH),
            record("M02", media = CppMediaRelation.DIRECT, evidence = CppEvidence.FRESH),
            record("M03", media = CppMediaRelation.DIRECT, evidence = CppEvidence.FRESH),
            record("M04", media = CppMediaRelation.DIRECT, evidence = CppEvidence.FRESH)
        )
        val ui = MeetingPresenceDisplay.renderFromProjection(
            projection = p,
            localModuleId = "M01",
            speakingModuleId = null,
            localCaptureBlocked = false
        )
        assertNull(ui.connectingHint)
        assertEquals(EndpointStatus.ONLINE, ui.avatarStatuses["M04"])
    }
}


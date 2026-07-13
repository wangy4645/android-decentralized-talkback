package com.talkback.appprod.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingPresenceDisplayTest {

    @Test
    fun label_fullMesh_singleNumber() {
        assertEquals("3", MeetingPresenceDisplay.participantCountLabel(connectedCount = 3, joinedCount = 3))
    }

    @Test
    fun label_diverged_showsSplit() {
        assertEquals("2/3", MeetingPresenceDisplay.participantCountLabel(connectedCount = 2, joinedCount = 3))
    }

    @Test
    fun label_postTimeout_stillSplitWhenMembershipRemains() {
        assertEquals("2/3", MeetingPresenceDisplay.participantCountLabel(connectedCount = 2, joinedCount = 3))
    }

    @Test
    fun label_afterPrune_singleNumber() {
        assertEquals("2", MeetingPresenceDisplay.participantCountLabel(connectedCount = 2, joinedCount = 2))
    }
}

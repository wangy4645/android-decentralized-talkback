package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceParticipantProjectorTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m03 = ModuleId("M03")
    private val localKey = "M01-E01"

    private fun view(
        moduleId: String,
        invite: InviteState,
        media: MediaState
    ) = MemberView(
        key = "$moduleId-E01",
        moduleId = moduleId,
        invite = invite,
        media = media
    )

    private fun input(
        memberViews: List<MemberView>,
        sessionAccepted: Boolean = true,
        leftModuleIds: Set<String> = emptySet()
    ) = ConferenceParticipantProjector.Input(
        localModuleId = m01,
        localKey = localKey,
        sessionAccepted = sessionAccepted,
        roster = listOf(
            EndpointAddress(m01, EndpointId("E01")),
            EndpointAddress(m02, EndpointId("E01")),
            EndpointAddress(m03, EndpointId("E01"))
        ),
        memberViews = memberViews,
        leftModuleIds = leftModuleIds
    )

    @Test
    fun visible_soloHostOnlyLocal() {
        val out = ConferenceParticipantProjector.project(
            input(
                memberViews = listOf(
                    view("M02", InviteState.INVITING, MediaState.NONE),
                    view("M03", InviteState.RINGING, MediaState.NONE)
                )
            )
        )
        assertEquals(1, out.visibleParticipantCount)
        assertEquals(1, out.joinedParticipantCount)
        assertEquals(2, out.pendingInviteeCount)
        assertEquals("M01", out.visibleParticipants.single().moduleId)
        assertTrue(out.awaitingAdditionalParticipants)
    }

    @Test
    fun visible_remoteAcceptedConnecting() {
        val out = ConferenceParticipantProjector.project(
            input(
                memberViews = listOf(
                    view("M02", InviteState.ACCEPTED, MediaState.CONNECTING),
                    view("M03", InviteState.INVITING, MediaState.NONE)
                )
            )
        )
        assertEquals(2, out.visibleParticipantCount)
        assertEquals(
            ConferenceParticipantDisplayState.VISIBLE_CONNECTING,
            out.visibleParticipants.first { it.moduleId == "M02" }.displayState
        )
        assertTrue(out.awaitingAdditionalParticipants)
    }

    @Test
    fun visible_threeWayAllConnected() {
        val out = ConferenceParticipantProjector.project(
            input(
                memberViews = listOf(
                    view("M02", InviteState.ACCEPTED, MediaState.CONNECTED),
                    view("M03", InviteState.ACCEPTED, MediaState.CONNECTED)
                )
            )
        )
        assertEquals(3, out.visibleParticipantCount)
        assertFalse(out.awaitingAdditionalParticipants)
    }

    @Test
    fun visible_failedStillShown() {
        val out = ConferenceParticipantProjector.project(
            input(
                memberViews = listOf(
                    view("M02", InviteState.ACCEPTED, MediaState.FAILED)
                )
            )
        )
        assertEquals(2, out.visibleParticipantCount)
        assertEquals(
            ConferenceParticipantDisplayState.VISIBLE_FAILED,
            out.visibleParticipants.first { it.moduleId == "M02" }.displayState
        )
    }

    @Test
    fun visible_leftRemoteExcluded() {
        val out = ConferenceParticipantProjector.project(
            input(
                memberViews = listOf(
                    view("M02", InviteState.ACCEPTED, MediaState.CONNECTED),
                    view("M03", InviteState.ACCEPTED, MediaState.CONNECTED)
                ),
                leftModuleIds = setOf("M02")
            )
        )
        assertEquals(2, out.visibleParticipantCount)
        assertTrue(out.visibleParticipants.none { it.moduleId == "M02" })
    }

    @Test
    fun visible_acceptedMediaNoneNotShownYet() {
        val out = ConferenceParticipantProjector.project(
            input(
                memberViews = listOf(
                    view("M02", InviteState.ACCEPTED, MediaState.NONE)
                )
            )
        )
        assertEquals(1, out.visibleParticipantCount)
        assertTrue(out.awaitingAdditionalParticipants)
    }

    @Test
    fun explainMemberVisibility_matchesProjector() {
        val input = input(
            memberViews = listOf(
                view("M02", InviteState.ACCEPTED, MediaState.CONNECTED),
                view("M03", InviteState.NONE, MediaState.NONE)
            )
        )
        val explanations = ConferenceParticipantProjector.explainMemberVisibility(input)
        assertEquals("INVITE_NONE", explanations.single { it.moduleId == "M03" }.reason)
        assertEquals("VISIBLE", explanations.single { it.moduleId == "M02" }.reason)
    }

    @Test
    fun displayStateForRemote_table() {
        assertEquals(
            ConferenceParticipantDisplayState.VISIBLE_CONNECTED,
            ConferenceParticipantProjector.displayStateForRemote(
                view("M02", InviteState.ACCEPTED, MediaState.CONNECTED),
                left = false
            )
        )
        assertEquals(
            null,
            ConferenceParticipantProjector.displayStateForRemote(
                view("M02", InviteState.INVITING, MediaState.NONE),
                left = false
            )
        )
    }
}

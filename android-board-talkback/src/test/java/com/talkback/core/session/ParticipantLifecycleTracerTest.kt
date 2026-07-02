package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ParticipantLifecycleTracerTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m03 = ModuleId("M03")
    private val manager = ConferenceParticipantManager()

    @Before
    fun setUp() {
        ParticipantLifecycleTracer.resetForTests()
    }

    @Test
    fun explainMemberVisibility_inviteNone_isExcluded() {
        val input = ConferenceParticipantProjector.Input(
            localModuleId = m01,
            localKey = "M01-E01",
            sessionAccepted = true,
            roster = listOf(
                EndpointAddress(m01, EndpointId("E01")),
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            ),
            memberViews = listOf(
                MemberView("M02-E01", "M02", InviteState.ACCEPTED, MediaState.CONNECTED),
                MemberView("M03-E01", "M03", InviteState.NONE, MediaState.NONE)
            )
        )
        val m03Line = ConferenceParticipantProjector.explainMemberVisibility(input)
            .single { it.moduleId == "M03" }
        assertFalse(m03Line.visible)
        assertEquals("INVITE_NONE", m03Line.reason)
    }

    @Test
    fun participantContext_rosterMemberWithInviteNone_notAwaiting() {
        val sessionId = "CONF-trace"
        manager.initSession(
            sessionId,
            EndpointAddress(m01, EndpointId("E01")),
            listOf(
                EndpointAddress(m01, EndpointId("E01")),
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01"))
            )
        )
        manager.onInviteAccepted(sessionId, "M02")

        val context = manager.participantContext(sessionId, "M03", m01)
        assertEquals(3, context.rosterSize)
        assertTrue(context.exists)
        assertEquals(InviteState.NONE, context.invite)
        assertFalse(context.awaiting)
    }
}

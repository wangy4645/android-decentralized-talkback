package com.talkback.appprod.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presentation mapping aligned with legacy TalkFragment (b1d09fe). */
class TalkButtonPresentationResolverTest {

    @Test
    fun meetingTab_idle_tapToJoin() {
        val presentation = resolve(
            tab = UserSelectedTab.MEETING,
            participantCountLabel = "0 Participants"
        )
        assertEquals("Meeting", presentation.label)
        assertTrue(presentation.hint!!.contains("Tap to Join"))
        assertEquals(true, presentation.enabled)
    }

    @Test
    fun meetingTab_active_tapToView() {
        val presentation = resolve(
            tab = UserSelectedTab.MEETING,
            participantCountLabel = "3 Participants",
            meetingLive = true
        )
        assertEquals("Meeting", presentation.label)
        assertTrue(presentation.hint!!.contains("Tap to View"))
        assertEquals(TalkButtonVisualState.ACTIVE, presentation.visualState)
    }

    @Test
    fun meetingTab_reconnecting_staysEnabled() {
        val presentation = resolve(
            tab = UserSelectedTab.MEETING,
            participantCountLabel = "2 Participants",
            conferenceReconnecting = true
        )
        assertEquals("Meeting", presentation.label)
        assertTrue(presentation.hint!!.contains("Reconnecting"))
        assertEquals(true, presentation.enabled)
        assertEquals(TalkButtonVisualState.DEFAULT, presentation.visualState)
    }

    @Test
    fun pttTab_defaultHoldHint() {
        val presentation = resolve(
            tab = UserSelectedTab.PTT,
            participantCountLabel = "3 Participants"
        )
        assertEquals("PTT", presentation.label)
        assertEquals("Press & Hold\nto Talk", presentation.hint)
    }

    private fun resolve(
        tab: UserSelectedTab,
        participantCountLabel: String,
        meetingLive: Boolean = false,
        conferenceReconnecting: Boolean = false
    ) = TalkButtonPresentationResolver.resolve(
        tab = tab,
        context = TalkButtonContext(
            participantCountLabel = participantCountLabel,
            meetingLive = meetingLive,
            conferenceReconnecting = conferenceReconnecting
        )
    )
}

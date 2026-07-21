package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceRuntimePhase
import org.junit.Assert.assertEquals
import org.junit.Test

class EffectiveInteractionModeResolverTest {

    @Test
    fun groupReady_afterRemoteEnd_projectsGroupPtt() {
        assertEquals(
            EffectiveInteractionMode.GROUP_PTT,
            EffectiveInteractionModeResolver.resolve(
                conferenceActive = false,
                runtimePhase = null,
                hasPendingInvite = false
            )
        )
    }

    @Test
    fun meetingTabWithoutConference_stillGroupPtt() {
        assertEquals(
            EffectiveInteractionMode.GROUP_PTT,
            EffectiveInteractionModeResolver.resolve(
                conferenceActive = false,
                runtimePhase = null,
                hasPendingInvite = false
            )
        )
    }

    @Test
    fun pendingInvite_projectsConferenceStarting() {
        assertEquals(
            EffectiveInteractionMode.CONFERENCE_STARTING,
            EffectiveInteractionModeResolver.resolve(
                conferenceActive = false,
                runtimePhase = null,
                hasPendingInvite = true
            )
        )
    }

    @Test
    fun conferenceConnecting_projectsConferenceStarting() {
        assertEquals(
            EffectiveInteractionMode.CONFERENCE_STARTING,
            EffectiveInteractionModeResolver.resolve(
                conferenceActive = true,
                runtimePhase = ConferenceRuntimePhase.CONNECTING,
                hasPendingInvite = false
            )
        )
    }

    @Test
    fun conferenceActive_projectsConferenceActive() {
        assertEquals(
            EffectiveInteractionMode.CONFERENCE_ACTIVE,
            EffectiveInteractionModeResolver.resolve(
                conferenceActive = true,
                runtimePhase = ConferenceRuntimePhase.ACTIVE,
                hasPendingInvite = false
            )
        )
    }
}

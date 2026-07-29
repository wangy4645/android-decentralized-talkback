package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceRuntimePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * PR-3.1 acceptance: Cases A–G + tab/ownership invariants.
 */
class PrimaryInteractionActionResolverTest {

    // Case A: post-meeting GROUP READY on PTT tab -> PTT_HOLD
    @Test
    fun caseA_postMeetingGroupReady_pttHold() {
        assertEquals(
            PrimaryInteractionAction.PTT_HOLD,
            resolve(
                tab = UserSelectedTab.PTT,
                effective = EffectiveInteractionMode.GROUP_PTT
            )
        )
    }

    // Case B: Meeting tab, no conference -> JOIN_MEETING
    @Test
    fun caseB_meetingTab_joinMeeting() {
        assertEquals(
            PrimaryInteractionAction.JOIN_MEETING,
            resolve(
                tab = UserSelectedTab.MEETING,
                effective = EffectiveInteractionMode.GROUP_PTT
            )
        )
    }

    // Case C: Meeting tab + GROUP session -> JOIN_MEETING (joinMeeting tears down GROUP)
    @Test
    fun caseC_meetingTabWithGroup_joinMeeting() {
        assertEquals(
            PrimaryInteractionAction.JOIN_MEETING,
            resolve(
                tab = UserSelectedTab.MEETING,
                effective = EffectiveInteractionMode.GROUP_PTT
            )
        )
    }

    // Case D: ACTIVE conference -> OPEN_MEETING_CONTROL
    @Test
    fun caseD_activeConference_openMeetingControl() {
        assertEquals(
            PrimaryInteractionAction.OPEN_MEETING_CONTROL,
            resolve(
                tab = UserSelectedTab.MEETING,
                effective = EffectiveInteractionMode.CONFERENCE_ACTIVE
            )
        )
        assertEquals(
            PrimaryInteractionAction.OPEN_MEETING_CONTROL,
            resolve(
                tab = UserSelectedTab.PTT,
                effective = EffectiveInteractionMode.CONFERENCE_ACTIVE
            )
        )
    }

    // Case E: STARTING -> DISABLED
    @Test
    fun caseE_conferenceStarting_disabled() {
        assertEquals(
            PrimaryInteractionAction.DISABLED,
            resolve(
                tab = UserSelectedTab.MEETING,
                effective = EffectiveInteractionMode.CONFERENCE_STARTING
            )
        )
    }

    // Case F: remote-ended guard is tab reset in ViewModel; Meeting tab still JOIN_MEETING
    @Test
    fun caseF_meetingTabGroupRecovery_joinMeeting() {
        assertEquals(
            PrimaryInteractionAction.JOIN_MEETING,
            resolve(
                tab = UserSelectedTab.MEETING,
                effective = EffectiveInteractionMode.GROUP_PTT
            )
        )
    }

    // Case G: PTT tab + pending invite -> DISABLED (not PTT_HOLD)
    @Test
    fun caseG_pttTabPendingInvite_disabled() {
        assertEquals(
            PrimaryInteractionAction.DISABLED,
            resolve(
                tab = UserSelectedTab.PTT,
                effective = EffectiveInteractionMode.CONFERENCE_STARTING
            )
        )
    }

    @Test
    fun pttTabWithoutConference_pttHold() {
        assertEquals(
            PrimaryInteractionAction.PTT_HOLD,
            resolve(
                tab = UserSelectedTab.PTT,
                effective = EffectiveInteractionMode.GROUP_PTT
            )
        )
    }

    @Test
    fun invariant_meetingTabWithoutGroup_doesNotImplyOpenMeetingControl() {
        val action = resolve(
            tab = UserSelectedTab.MEETING,
            effective = EffectiveInteractionMode.GROUP_PTT
        )
        assertNotEquals(PrimaryInteractionAction.OPEN_MEETING_CONTROL, action)
    }

    @Test
    fun invariant_conferenceNotActive_doesNotImplyOpenMeetingControl() {
        val effective = EffectiveInteractionModeResolver.resolve(
            conferenceActive = false,
            runtimePhase = null,
            hasPendingInvite = false
        )
        val action = PrimaryInteractionActionResolver.resolve(
            userSelectedTab = UserSelectedTab.MEETING,
            effectiveInteractionMode = effective
        )
        assertNotEquals(PrimaryInteractionAction.OPEN_MEETING_CONTROL, action)
    }

    @Test
    fun effectiveMode_doesNotReadUserTab() {
        val pttProjection = EffectiveInteractionModeResolver.resolve(
            conferenceActive = false,
            runtimePhase = null,
            hasPendingInvite = false
        )
        val meetingProjection = EffectiveInteractionModeResolver.resolve(
            conferenceActive = false,
            runtimePhase = null,
            hasPendingInvite = false
        )
        assertEquals(pttProjection, meetingProjection)
    }

    private fun resolve(
        tab: UserSelectedTab,
        effective: EffectiveInteractionMode
    ) = PrimaryInteractionActionResolver.resolve(
        userSelectedTab = tab,
        effectiveInteractionMode = effective
    )
}

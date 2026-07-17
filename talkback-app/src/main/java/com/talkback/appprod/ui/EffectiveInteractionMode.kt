package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceRuntimePhase

/** User navigation intent on the Talk page tab strip. Does not drive input binding. */
enum class UserSelectedTab {
    PTT,
    MEETING
}

/**
 * Runtime capability projection (ADR-0020). Read-only; must not read [UserSelectedTab]
 * or meetingPreferred. Button actions come from [PrimaryInteractionActionResolver].
 */
enum class EffectiveInteractionMode {
    GROUP_PTT,
    CONFERENCE_STARTING,
    CONFERENCE_ACTIVE
}

object EffectiveInteractionModeResolver {

    fun resolve(
        conferenceActive: Boolean,
        runtimePhase: ConferenceRuntimePhase?,
        hasPendingInvite: Boolean
    ): EffectiveInteractionMode {
        if (conferenceActive && runtimePhase == ConferenceRuntimePhase.ACTIVE) {
            return EffectiveInteractionMode.CONFERENCE_ACTIVE
        }
        if (hasPendingInvite || conferenceActive) {
            return EffectiveInteractionMode.CONFERENCE_STARTING
        }
        return EffectiveInteractionMode.GROUP_PTT
    }
}

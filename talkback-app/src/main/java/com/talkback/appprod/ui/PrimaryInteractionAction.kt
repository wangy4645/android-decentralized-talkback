package com.talkback.appprod.ui

/**
 * One-shot primary button action derived from user tab + runtime capability + session facts.
 * Not a runtime mode — describes what this press does, not what the system owns.
 */
enum class PrimaryInteractionAction {
    PTT_HOLD,
    JOIN_MEETING,
    OPEN_MEETING_CONTROL,
    DISABLED
}

object PrimaryInteractionActionResolver {

    fun resolve(
        userSelectedTab: UserSelectedTab,
        effectiveInteractionMode: EffectiveInteractionMode
    ): PrimaryInteractionAction = when (effectiveInteractionMode) {
        EffectiveInteractionMode.CONFERENCE_ACTIVE -> PrimaryInteractionAction.OPEN_MEETING_CONTROL
        EffectiveInteractionMode.CONFERENCE_STARTING -> PrimaryInteractionAction.DISABLED
        EffectiveInteractionMode.GROUP_PTT -> when (userSelectedTab) {
            UserSelectedTab.MEETING -> PrimaryInteractionAction.JOIN_MEETING
            UserSelectedTab.PTT -> PrimaryInteractionAction.PTT_HOLD
        }
    }
}

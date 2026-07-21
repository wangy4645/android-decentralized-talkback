package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceRuntimePhase

/**
 * Render model for the Talk page primary button. Not stored or projected — derived per frame
 * from tab + display context only. Does not read [PrimaryInteractionAction].
 */
data class TalkButtonPresentation(
    val label: String,
    val hint: String?,
    val enabled: Boolean,
    val icon: TalkButtonIcon?,
    val visualState: TalkButtonVisualState
)

enum class TalkButtonIcon {
    NONE
}

enum class TalkButtonVisualState {
    DEFAULT,
    ACTIVE,
    DISABLED
}

/** Display-only inputs for [TalkButtonPresentationResolver]. */
data class TalkButtonContext(
    val participantCountLabel: String = "",
    val meetingLive: Boolean = false,
    val runtimePhase: ConferenceRuntimePhase? = null,
    val conferenceReconnectFailed: Boolean = false,
    val conferenceReconnecting: Boolean = false,
    val conferenceRejoinInProgress: Boolean = false,
    val hasRejoinableMeeting: Boolean = false,
    val pttActive: Boolean = false,
    val pttHeld: Boolean = false,
    val labels: TalkButtonPresentationLabels = TalkButtonPresentationLabels()
)

data class TalkButtonPresentationLabels(
    val meetingTitle: String = "Meeting",
    val pttLabel: String = "PTT",
    val defaultPttHint: String = "Press & Hold\nto Talk",
    val tapToJoin: String = "Tap to Join",
    val tapToView: String = "Tap to View",
    val reconnectFailed: String = "Reconnect failed",
    val reconnecting: String = "Reconnecting…"
)

object TalkButtonPresentationResolver {

    fun resolve(
        tab: UserSelectedTab,
        context: TalkButtonContext
    ): TalkButtonPresentation {
        val labels = context.labels
        return if (tab == UserSelectedTab.MEETING) {
            TalkButtonPresentation(
                label = labels.meetingTitle,
                hint = context.participantCountLabel + "\n" + meetingPhaseHint(context, labels),
                enabled = true,
                icon = TalkButtonIcon.NONE,
                visualState = if (context.meetingLive) {
                    TalkButtonVisualState.ACTIVE
                } else {
                    TalkButtonVisualState.DEFAULT
                }
            )
        } else {
            TalkButtonPresentation(
                label = labels.pttLabel,
                hint = labels.defaultPttHint,
                enabled = true,
                icon = TalkButtonIcon.NONE,
                visualState = if (context.pttActive || context.pttHeld) {
                    TalkButtonVisualState.ACTIVE
                } else {
                    TalkButtonVisualState.DEFAULT
                }
            )
        }
    }

    private fun meetingPhaseHint(
        context: TalkButtonContext,
        labels: TalkButtonPresentationLabels
    ): String = when {
        context.meetingLive -> labels.tapToView
        context.conferenceReconnectFailed -> labels.reconnectFailed
        context.conferenceReconnecting ||
            context.runtimePhase == ConferenceRuntimePhase.RECOVERING ||
            context.conferenceRejoinInProgress -> labels.reconnecting
        context.hasRejoinableMeeting -> labels.tapToJoin
        else -> labels.tapToJoin
    }
}

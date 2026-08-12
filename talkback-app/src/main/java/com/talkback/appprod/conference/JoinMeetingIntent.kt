package com.talkback.appprod.conference

/**
 * Explicit conference admission intent (ADR-0051).
 * Navigation MUST NOT construct these; only user admission actions may.
 */
sealed interface JoinMeetingIntent {
    /** Meeting mode primary control button. */
    data object PttMeeting : JoinMeetingIntent

    /** Online area tap when the primary action is join-meeting. */
    data object TapToJoin : JoinMeetingIntent

    val traceName: String
        get() = when (this) {
            PttMeeting -> "PttMeeting"
            TapToJoin -> "TapToJoin"
        }
}

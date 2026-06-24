package com.talkback.core.session

import com.talkback.core.model.ModuleId

enum class InviteState {
    NONE,
    INVITING,
    RINGING,
    ACCEPTED,
    DECLINED,
    EXPIRED
}

enum class MediaState {
    NONE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED
}

data class ParticipantState(
    val moduleId: ModuleId,
    var invite: InviteState = InviteState.NONE,
    var media: MediaState = MediaState.NONE,
    var invitedAtMs: Long = 0L,
    var lastMediaChangeMs: Long = 0L
)

data class MemberView(
    val key: String,
    val moduleId: String,
    val invite: InviteState,
    val media: MediaState
)

package com.talkback.appprod.runtime

import com.talkback.app.TalkbackSessionSnapshot
import com.talkback.appprod.data.AppConfig
import com.talkback.appprod.data.ChannelMode
import com.talkback.core.session.ChannelReadiness
import com.talkback.core.session.SessionType

object ChannelWarmupPolicy {

    fun shouldWarmup(
        config: AppConfig,
        manager: TalkbackRuntimeManager,
        talkTabMode: ChannelMode,
        meetingTabPreferred: Boolean,
        hasUnicast: Boolean,
        activeSession: TalkbackSessionSnapshot?
    ): Boolean {
        if (!manager.isRunning()) return false
        if (talkTabMode != ChannelMode.GROUP_PTT) return false
        if (meetingTabPreferred) return false
        if (manager.pendingConferenceInvite(config.defaultChannelId) != null) return false
        if (hasUnicast) return false
        if (!manager.hasReachableTeammates(config)) return false
        if (activeSession?.type == SessionType.CONFERENCE) return false
        if (activeSession?.type == SessionType.GROUP) return false
        if (!manager.isWarmupBackoffElapsed()) return false
        return true
    }

    fun isAwaitingHost(config: AppConfig, manager: TalkbackRuntimeManager): Boolean =
        manager.channelReadiness(config, meetingTabPreferred = false) == ChannelReadiness.AWAITING_PRIMARY
}

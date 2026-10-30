package com.talkback.appprod.debug

import android.util.Log
import com.talkback.appprod.data.AppConfigStore
import com.talkback.appprod.runtime.TalkbackRuntimeManager

/**
 * Field PRE-G harness: force leave via product [TalkbackRuntimeManager.leaveChannelSession].
 * Invoked from the live foreground service (debug broadcast) so the running runtime is used.
 * Does not clear app data, trust, or session stores.
 */
object P1cGroupPreconditionLeaveDebug {
    private const val LOG_TAG = "Talkback"
    private const val PREFIX = "P1C_GROUP_PRECONDITION_LEAVE"

    fun forceLeave(runtimeManager: TalkbackRuntimeManager, configStore: AppConfigStore): Boolean {
        val config = configStore.load()
        val channelId = config.defaultChannelId
        val localModuleId = config.moduleId.trim().uppercase()
        val log: (String) -> Unit = { line -> Log.i(LOG_TAG, line) }

        if (!runtimeManager.isRunning() || runtimeManager.getRuntime() == null) {
            log(
                "$PREFIX outcome=SKIPPED_RUNTIME_UNAVAILABLE device=$localModuleId " +
                    "channel=$channelId"
            )
            return false
        }

        runtimeManager.setMeetingPreferred(true, channelId)
        runtimeManager.rejectPendingConferenceInvite(channelId, reason = "P1C_GROUP_PRECONDITION")

        val before = runtimeManager.activeChannelSession(config)
        val beforeMode = runtimeManager.getRuntime()?.channelMode(channelId)?.name
        log(
            "$PREFIX phase=BEFORE device=$localModuleId channel=$channelId " +
                "session=${before?.sessionId ?: "none"} type=${before?.type?.name ?: "none"} " +
                "channelMode=${beforeMode ?: "unknown"}"
        )

        val leaveInvoked = before != null
        if (before != null) {
            runtimeManager.leaveChannelSession(
                config,
                reason = "USER_LEAVE",
                caller = "P1cGroupPreconditionLeaveDebug.forceLeave",
            )
        }

        // leaveChannelMode may clear uiMeetingPreferredChannels — re-assert Meeting preference.
        runtimeManager.setMeetingPreferred(true, channelId)
        runtimeManager.rejectPendingConferenceInvite(channelId, reason = "P1C_GROUP_PRECONDITION")

        val after = runtimeManager.activeChannelSession(config)
        val afterMode = runtimeManager.getRuntime()?.channelMode(channelId)?.name
        val clear = after == null && (afterMode == null || afterMode == "IDLE")

        log(
            "$PREFIX phase=AFTER device=$localModuleId channel=$channelId " +
                "session=${after?.sessionId ?: "none"} type=${after?.type?.name ?: "none"} " +
                "channelMode=${afterMode ?: "unknown"} leaveInvoked=$leaveInvoked clear=$clear"
        )
        log(
            "$PREFIX outcome=${if (clear) "CLEAR" else "RESIDUAL"} device=$localModuleId " +
                "channel=$channelId beforeSession=${before?.sessionId ?: "none"} " +
                "beforeType=${before?.type?.name ?: "none"} afterSession=${after?.sessionId ?: "none"} " +
                "afterType=${after?.type?.name ?: "none"} afterMode=${afterMode ?: "unknown"}"
        )
        return clear
    }
}

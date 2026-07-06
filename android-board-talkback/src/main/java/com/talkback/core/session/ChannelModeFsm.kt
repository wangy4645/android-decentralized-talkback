package com.talkback.core.session

import com.talkback.core.util.ChannelObservabilityLog

/**
 * Per-channel mode authority: GROUP PTT and CONFERENCE are mutually exclusive on one channel.
 * All mode transitions go through [requestMode].
 */
class ChannelModeFsm(val channelId: String) {
    @Volatile
    var mode: ChannelMode = ChannelMode.IDLE
        private set

    @Volatile
    var switching: Boolean = false
        private set

    @Volatile
    var modeOwnerModuleId: String? = null
        private set


    fun requestMode(target: ChannelMode, byModuleId: String): Boolean {
        if (switching && target != mode) return false
        val previous = mode
        val applied = when (mode) {
            ChannelMode.IDLE -> {
                if (target == ChannelMode.IDLE) return true
                mode = target
                modeOwnerModuleId = byModuleId
                true
            }
            ChannelMode.GROUP_PTT -> when (target) {
                ChannelMode.GROUP_PTT -> true
                ChannelMode.CONFERENCE -> {
                    switching = true
                    mode = target
                    modeOwnerModuleId = byModuleId
                    switching = false
                    true
                }
                ChannelMode.IDLE -> {
                    mode = ChannelMode.IDLE
                    modeOwnerModuleId = null
                    true
                }
            }
            ChannelMode.CONFERENCE -> when (target) {
                ChannelMode.CONFERENCE -> true
                ChannelMode.GROUP_PTT -> false
                ChannelMode.IDLE -> {
                    mode = ChannelMode.IDLE
                    modeOwnerModuleId = null
                    true
                }
            }
        }
        if (applied) {
            ChannelObservabilityLog.channelModeTransition(
                channelId = channelId,
                from = previous,
                to = mode,
                byModuleId = byModuleId,
                op = "requestMode"
            )
        }
        return applied
    }

    fun allowsIncomingGroup(): Boolean = !switching && mode != ChannelMode.CONFERENCE

    fun allowsIncomingConference(): Boolean = !switching

    fun isConferenceMode(): Boolean = mode == ChannelMode.CONFERENCE

    fun reset() {
        val previous = mode
        mode = ChannelMode.IDLE
        switching = false
        modeOwnerModuleId = null
        ChannelObservabilityLog.channelModeTransition(
            channelId = channelId,
            from = previous,
            to = mode,
            byModuleId = null,
            op = "reset"
        )
    }
}

enum class ChannelMode {
    IDLE,
    GROUP_PTT,
    CONFERENCE
}

package com.talkback.governance.transition

data class TransitionRecord(
    val id: TransitionId,
    val channelId: String,
    val trigger: TransitionTrigger,
    val phase: TransitionPhase,
    val startedAtMs: Long,
    val deadlineMs: Long,
    val terminal: TransitionTerminalState? = null,
    val terminalAtMs: Long? = null,
    val abortReason: String? = null
) {
    val isActive: Boolean = terminal == null

    fun isTimedOut(nowMs: Long): Boolean = isActive && nowMs >= deadlineMs
}

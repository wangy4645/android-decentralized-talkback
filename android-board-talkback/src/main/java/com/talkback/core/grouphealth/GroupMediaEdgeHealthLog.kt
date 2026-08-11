package com.talkback.core.grouphealth

import com.talkback.core.util.TalkbackLog

/**
 * P0 diagnostic: recovery lifecycle trace for GROUP mesh transmit edges.
 * Behavior-neutral — observability only.
 */
object GroupMediaEdgeHealthLog {

    enum class Event {
        ICE_ENTER_CHECKING,
        RECOVERY_ACTION,
        RECOVERY_SUPPRESSED,
        STUCK_EVALUATION,
        TRANSMIT_READY
    }

    data class Snapshot(
        val channelId: String,
        val localModuleId: String,
        val remoteModuleId: String? = null,
        val sessionTraceId: String? = null,
        val sessionId: String? = null,
        val mediaGeneration: Long? = null,
        val iceState: String? = null,
        val pcState: String? = null,
        val checkingSinceMs: Long? = null,
        val sessionAgeMs: Long? = null,
        val lastActiveMsAge: Long? = null,
        val lastMediaProgressMsAge: Long? = null,
        val lastRecoveryAction: String? = null,
        val lastRecoveryAtMs: Long? = null,
        val recoveryLevel: String? = null,
        val suppressReason: String? = null,
        val action: String? = null,
        val reason: String? = null,
        val stuckResult: Boolean? = null,
        val stuckReason: String? = null
    )

    fun emit(event: Event, snapshot: Snapshot) {
        val parts = mutableListOf<String>()
        parts += "event=${event.name}"
        parts += "channel=${snapshot.channelId}"
        parts += "local=${snapshot.localModuleId}"
        snapshot.remoteModuleId?.let { parts += "remote=$it" }
        snapshot.sessionTraceId?.let { parts += "sessionTraceId=$it" }
        snapshot.sessionId?.let { parts += "sessionId=$it" }
        snapshot.mediaGeneration?.let { parts += "mediaGeneration=$it" }
        snapshot.iceState?.let { parts += "iceState=$it" }
        snapshot.pcState?.let { parts += "pcState=$it" }
        snapshot.checkingSinceMs?.let { parts += "checkingSinceMs=$it" }
        snapshot.sessionAgeMs?.let { parts += "sessionAgeMs=$it" }
        snapshot.lastActiveMsAge?.let { parts += "lastActiveMsAge=$it" }
        snapshot.lastMediaProgressMsAge?.let { parts += "lastMediaProgressMsAge=$it" }
        snapshot.lastRecoveryAction?.let { parts += "lastRecoveryAction=$it" }
        snapshot.lastRecoveryAtMs?.let { parts += "lastRecoveryAtMs=$it" }
        snapshot.recoveryLevel?.let { parts += "recoveryLevel=$it" }
        snapshot.suppressReason?.let { parts += "suppressReason=$it" }
        snapshot.action?.let { parts += "action=$it" }
        snapshot.reason?.let { parts += "reason=$it" }
        snapshot.stuckResult?.let { parts += "stuckResult=$it" }
        snapshot.stuckReason?.let { parts += "stuckReason=$it" }
        TalkbackLog.i("GROUP_MEDIA_EDGE_HEALTH ${parts.joinToString(" ")}")
    }
}

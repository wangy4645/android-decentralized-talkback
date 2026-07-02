package com.talkback.core.session

import com.talkback.core.model.ModuleId
import com.talkback.core.util.TalkbackLog
import java.util.concurrent.atomic.AtomicLong

/**
 * F0: Conference lifecycle forensics only — observe, never enforce.
 *
 * Local [seq] is a per-device diagnostic counter (e.g. M02-124). It is NOT synchronized
 * across nodes and must never be treated as a protocol field.
 *
 * Cross-node correlation keys: sessionId + sender + event + epoch + signalTsMs.
 */
enum class LifecycleDirection {
    SEND,
    RECV
}

enum class LifecycleEvent {
    HANGUP,
    GROUP_LEAVE
}

enum class LifecycleBranch {
    HOST_TERMINATE,
    PARTICIPANT_LEAVE,
    REMOTE_HANGUP,
    REMOTE_LEAVE
}

data class ConferenceSessionSnapshot(
    val participants: Int,
    val connected: Int,
    val authority: String,
    val local: String
) {
    fun format(): String =
        "snapshot={participants=$participants,connected=$connected,authority=$authority,local=$local}"
}

object ConferenceLifecycleTrace {
    private val localSeq = AtomicLong(0)

    fun recordSend(
        localModuleId: ModuleId,
        branch: LifecycleBranch,
        event: LifecycleEvent,
        sessionId: String,
        authority: ModuleId?,
        epoch: Long,
        snapshot: ConferenceSessionSnapshot
    ) {
        val authorityId = authority?.value
        val isAuthority = authorityId != null && localModuleId.value == authorityId
        emit(
            direction = LifecycleDirection.SEND,
            seq = nextSeq(localModuleId),
            branch = branch,
            event = event,
            sessionId = sessionId,
            from = localModuleId.value,
            local = localModuleId.value,
            authority = authorityId,
            isAuthority = isAuthority,
            epoch = epoch,
            authorityMatched = null,
            accepted = true,
            snapshot = snapshot,
            signalTsMs = null
        )
    }

    fun recordRecv(
        localModuleId: ModuleId,
        branch: LifecycleBranch,
        event: LifecycleEvent,
        sessionId: String,
        fromModuleId: ModuleId,
        authority: ModuleId?,
        epoch: Long,
        snapshot: ConferenceSessionSnapshot,
        signalTsMs: Long
    ) {
        val authorityId = authority?.value
        val isAuthority = authorityId != null && localModuleId.value == authorityId
        val authorityMatched = authorityId != null && fromModuleId.value == authorityId
        // F0: always accept; authorityMatched=false + accepted=true flags R30 observation only.
        emit(
            direction = LifecycleDirection.RECV,
            seq = nextSeq(localModuleId),
            branch = branch,
            event = event,
            sessionId = sessionId,
            from = fromModuleId.value,
            local = localModuleId.value,
            authority = authorityId,
            isAuthority = isAuthority,
            epoch = epoch,
            authorityMatched = authorityMatched,
            accepted = true,
            snapshot = snapshot,
            signalTsMs = signalTsMs
        )
    }

    private fun nextSeq(localModuleId: ModuleId): String {
        val n = localSeq.incrementAndGet()
        return "${localModuleId.value}-$n"
    }

    private fun emit(
        direction: LifecycleDirection,
        seq: String,
        branch: LifecycleBranch,
        event: LifecycleEvent,
        sessionId: String,
        from: String,
        local: String,
        authority: String?,
        isAuthority: Boolean,
        epoch: Long,
        authorityMatched: Boolean?,
        accepted: Boolean,
        snapshot: ConferenceSessionSnapshot,
        signalTsMs: Long?
    ) {
        val authorityMatchedField = authorityMatched?.toString() ?: "n/a"
        val signalTsField = signalTsMs?.toString() ?: "n/a"
        TalkbackLog.i(
            "[CONF-TRACE] dir=$direction seq=$seq event=$event branch=$branch " +
                "session=$sessionId from=$from local=$local authority=${authority ?: "?"} " +
                "isAuthority=$isAuthority epoch=$epoch authorityMatched=$authorityMatchedField " +
                "accepted=$accepted signalTsMs=$signalTsField ${snapshot.format()}"
        )
    }
}

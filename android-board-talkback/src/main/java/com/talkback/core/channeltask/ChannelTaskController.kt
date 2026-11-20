package com.talkback.core.channeltask

import com.talkback.core.model.ChannelTaskPhase
import com.talkback.core.model.TaskAckPayload
import com.talkback.core.model.TaskClosePayload
import com.talkback.core.model.TaskIssuePayload
import java.util.concurrent.ConcurrentHashMap

enum class ChannelTaskAckStatus {
    PENDING,
    ACKED,
    UNACKED,
}

data class ChannelTaskMember(
    val moduleId: String,
    val status: ChannelTaskAckStatus,
)

data class ChannelTaskSnapshot(
    val taskId: String,
    val channelId: String,
    val issuerModuleId: String,
    val title: String,
    val body: String,
    val deadlineEpochMs: Long,
    val phase: ChannelTaskPhase,
    val members: List<ChannelTaskMember>,
    val localAcked: Boolean,
) {
    val ackedCount: Int get() = members.count { it.status == ChannelTaskAckStatus.ACKED }
    val targetCount: Int get() = members.size
    val needsLocalAck: Boolean get() = phase == ChannelTaskPhase.OPEN && !localAcked
}

internal data class MutableChannelTask(
    val taskId: String,
    val channelId: String,
    val issuerModuleId: String,
    val title: String,
    val body: String,
    val deadlineEpochMs: Long,
    val targets: List<String>,
    val acked: MutableSet<String>,
    var phase: ChannelTaskPhase,
)

class ChannelTaskController {
    private val tasks = ConcurrentHashMap<String, MutableChannelTask>()

    fun snapshot(taskId: String, nowMs: Long, localModuleId: String): ChannelTaskSnapshot? {
        expireIfDue(taskId, nowMs)
        val task = tasks[taskId] ?: return null
        return toSnapshot(task, localModuleId)
    }

    fun list(channelId: String, nowMs: Long, localModuleId: String): List<ChannelTaskSnapshot> {
        tasks.keys.forEach { expireIfDue(it, nowMs) }
        return tasks.values
            .filter { it.channelId == channelId }
            .map { toSnapshot(it, localModuleId) }
            .sortedWith(
                compareBy<ChannelTaskSnapshot> {
                    when (it.phase) {
                        ChannelTaskPhase.OPEN -> 0
                        ChannelTaskPhase.CLOSED, ChannelTaskPhase.EXPIRED -> 1
                    }
                }.thenByDescending { it.deadlineEpochMs }
            )
    }

    fun createLocalIssue(
        issuerModuleId: String,
        payload: TaskIssuePayload,
    ): ChannelTaskSnapshot {
        val acked = mutableSetOf(issuerModuleId.uppercase())
        val task = MutableChannelTask(
            taskId = payload.taskId,
            channelId = payload.channelId,
            issuerModuleId = issuerModuleId.uppercase(),
            title = payload.title,
            body = payload.body,
            deadlineEpochMs = payload.deadlineEpochMs,
            targets = payload.targets.map { it.uppercase() },
            acked = acked,
            phase = ChannelTaskPhase.OPEN,
        )
        tasks[payload.taskId] = task
        return toSnapshot(task, issuerModuleId)
    }

    /**
     * @return true if this is a new task for [localModuleId] (show banner).
     * Duplicate ISSUE still reports whether a re-ACK should be sent.
     */
    fun onIssue(
        issuerModuleId: String,
        payload: TaskIssuePayload,
        localModuleId: String,
        nowMs: Long,
    ): IssueResult {
        val local = localModuleId.uppercase()
        val issuer = issuerModuleId.uppercase()
        if (!payload.targets.map { it.uppercase() }.contains(local) && local != issuer) {
            return IssueResult.Ignored
        }
        val existing = tasks[payload.taskId]
        if (existing != null) {
            expireIfDue(existing.taskId, nowMs)
            val latest = tasks[payload.taskId] ?: existing
            val shouldReack = latest.phase == ChannelTaskPhase.OPEN &&
                local in latest.targets &&
                local in latest.acked
            return if (shouldReack) IssueResult.DuplicateResendAck(toSnapshot(latest, local))
            else IssueResult.Duplicate(toSnapshot(latest, local))
        }
        val acked = mutableSetOf(issuer)
        if (local == issuer) acked += local
        val task = MutableChannelTask(
            taskId = payload.taskId,
            channelId = payload.channelId,
            issuerModuleId = issuer,
            title = payload.title,
            body = payload.body,
            deadlineEpochMs = payload.deadlineEpochMs,
            targets = payload.targets.map { it.uppercase() },
            acked = acked,
            phase = ChannelTaskPhase.OPEN,
        )
        expireIfDueLocked(task, nowMs)
        tasks[payload.taskId] = task
        return IssueResult.New(toSnapshot(task, local))
    }

    fun onAck(fromModuleId: String, payload: TaskAckPayload, nowMs: Long): ChannelTaskSnapshot? {
        val from = fromModuleId.uppercase()
        val task = tasks[payload.taskId] ?: return null
        expireIfDue(task.taskId, nowMs)
        val latest = tasks[payload.taskId] ?: return null
        if (latest.phase != ChannelTaskPhase.OPEN) return toSnapshot(latest, from)
        if (from !in latest.targets) return toSnapshot(latest, from)
        latest.acked += from
        return toSnapshot(latest, from)
    }

    fun ackLocal(taskId: String, localModuleId: String, nowMs: Long): ChannelTaskSnapshot? {
        return onAck(localModuleId, TaskAckPayload(taskId), nowMs)
    }

    fun onClose(fromModuleId: String, payload: TaskClosePayload, nowMs: Long): ChannelTaskSnapshot? {
        val task = tasks[payload.taskId] ?: return null
        if (fromModuleId.uppercase() != task.issuerModuleId) return toSnapshot(task, fromModuleId)
        expireIfDue(task.taskId, nowMs)
        val latest = tasks[payload.taskId] ?: return null
        if (latest.phase == ChannelTaskPhase.OPEN) {
            latest.phase = if (payload.phase == ChannelTaskPhase.EXPIRED) {
                ChannelTaskPhase.EXPIRED
            } else {
                ChannelTaskPhase.CLOSED
            }
        }
        return toSnapshot(latest, fromModuleId)
    }

    fun closeLocal(taskId: String, issuerModuleId: String, nowMs: Long): ChannelTaskSnapshot? {
        return onClose(
            issuerModuleId,
            TaskClosePayload(taskId, ChannelTaskPhase.CLOSED),
            nowMs,
        )
    }

    fun pendingRetryTargets(taskId: String, nowMs: Long): List<String> {
        expireIfDue(taskId, nowMs)
        val task = tasks[taskId] ?: return emptyList()
        if (task.phase != ChannelTaskPhase.OPEN) return emptyList()
        return task.targets.filter { it !in task.acked }
    }

    fun openIssuerTaskIds(issuerModuleId: String, nowMs: Long): List<String> {
        val issuer = issuerModuleId.uppercase()
        return tasks.values.mapNotNull { task ->
            expireIfDue(task.taskId, nowMs)
            val latest = tasks[task.taskId] ?: return@mapNotNull null
            if (latest.issuerModuleId == issuer && latest.phase == ChannelTaskPhase.OPEN) {
                latest.taskId
            } else {
                null
            }
        }
    }

    private fun expireIfDue(taskId: String, nowMs: Long) {
        val task = tasks[taskId] ?: return
        expireIfDueLocked(task, nowMs)
    }

    private fun expireIfDueLocked(task: MutableChannelTask, nowMs: Long) {
        if (task.phase == ChannelTaskPhase.OPEN &&
            task.deadlineEpochMs > 0L &&
            nowMs >= task.deadlineEpochMs
        ) {
            task.phase = ChannelTaskPhase.EXPIRED
        }
    }

    private fun toSnapshot(task: MutableChannelTask, localModuleId: String): ChannelTaskSnapshot {
        val local = localModuleId.uppercase()
        val members = task.targets.map { id ->
            val status = when {
                id in task.acked -> ChannelTaskAckStatus.ACKED
                task.phase == ChannelTaskPhase.OPEN -> ChannelTaskAckStatus.PENDING
                else -> ChannelTaskAckStatus.UNACKED
            }
            ChannelTaskMember(id, status)
        }
        return ChannelTaskSnapshot(
            taskId = task.taskId,
            channelId = task.channelId,
            issuerModuleId = task.issuerModuleId,
            title = task.title,
            body = task.body,
            deadlineEpochMs = task.deadlineEpochMs,
            phase = task.phase,
            members = members,
            localAcked = local in task.acked || local == task.issuerModuleId,
        )
    }

    sealed class IssueResult {
        object Ignored : IssueResult()
        data class New(val snapshot: ChannelTaskSnapshot) : IssueResult()
        data class Duplicate(val snapshot: ChannelTaskSnapshot) : IssueResult()
        data class DuplicateResendAck(val snapshot: ChannelTaskSnapshot) : IssueResult()
    }
}

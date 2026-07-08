package com.talkback.governance.transition

import com.talkback.governance.capability.CapabilityProbe
import com.talkback.governance.capability.CapabilitySnapshot
import com.talkback.governance.capability.capabilitySnapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

sealed interface BeginTransitionResult {
    data class Started(val record: TransitionRecord) : BeginTransitionResult
    data class Rejected(val active: TransitionRecord) : BeginTransitionResult
}

/**
 * Observes and aggregates transition lifecycle. Does not execute runtime mutations (ADR-0015).
 */
class TransitionCoordinator(
    private val probes: List<CapabilityProbe>,
    private val policyProvider: (TransitionTrigger) -> TransitionPolicyRule = PolicyRegistry::rule,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idSource: () -> Long = { nextId.incrementAndGet() }
) {
    private val activeByChannel = ConcurrentHashMap<String, TransitionRecord>()

    fun beginTransition(trigger: TransitionTrigger, channelId: String): BeginTransitionResult {
        expireTimeouts(channelId)
        val existing = activeByChannel[channelId]
        if (existing?.isActive == true) {
            return BeginTransitionResult.Rejected(existing)
        }
        val now = clock()
        val timeoutMs = policyProvider(trigger).timeoutMs
        val record = TransitionRecord(
            id = TransitionId(idSource()),
            channelId = channelId,
            trigger = trigger,
            phase = TransitionPhase.PREPARING,
            startedAtMs = now,
            deadlineMs = now + timeoutMs
        )
        activeByChannel[channelId] = record
        return BeginTransitionResult.Started(record)
    }

    fun abortTransition(channelId: String, reason: String): TransitionRecord? {
        val current = activeByChannel[channelId] ?: return null
        if (!current.isActive) return current
        val aborted = current.copy(
            terminal = TransitionTerminalState.ABORTED,
            terminalAtMs = clock(),
            abortReason = reason
        )
        activeByChannel.remove(channelId)
        return aborted
    }

    fun markReconciling(channelId: String): TransitionRecord? {
        val current = activeByChannel[channelId] ?: return null
        if (!current.isActive) return current
        val updated = current.copy(phase = TransitionPhase.RECONCILING)
        activeByChannel[channelId] = updated
        return updated
    }

    fun completeTransition(channelId: String): TransitionRecord? {
        val current = activeByChannel[channelId] ?: return null
        if (!current.isActive) return current
        val completed = current.copy(
            phase = TransitionPhase.READY,
            terminal = TransitionTerminalState.READY,
            terminalAtMs = clock()
        )
        activeByChannel.remove(channelId)
        return completed
    }

    fun failTransition(channelId: String, reason: String? = null): TransitionRecord? {
        val current = activeByChannel[channelId] ?: return null
        if (!current.isActive) return current
        val failed = current.copy(
            terminal = TransitionTerminalState.FAILED,
            terminalAtMs = clock(),
            abortReason = reason
        )
        activeByChannel.remove(channelId)
        return failed
    }

    fun activeTransition(channelId: String): TransitionRecord? {
        expireTimeouts(channelId)
        return activeByChannel[channelId]
    }

    fun capabilitySnapshot(channelId: String): CapabilitySnapshot =
        capabilitySnapshot(channelId, probes)

    fun expireTimeouts(channelId: String? = null): List<TransitionRecord> {
        val now = clock()
        val channels = if (channelId != null) listOf(channelId) else activeByChannel.keys.toList()
        val timedOut = mutableListOf<TransitionRecord>()
        channels.forEach { ch ->
            val current = activeByChannel[ch] ?: return@forEach
            if (!current.isTimedOut(now)) return@forEach
            val expired = current.copy(
                terminal = TransitionTerminalState.TIMED_OUT,
                terminalAtMs = now
            )
            activeByChannel.remove(ch)
            timedOut += expired
        }
        return timedOut
    }

    companion object {
        private val nextId = AtomicLong(1L)
    }
}

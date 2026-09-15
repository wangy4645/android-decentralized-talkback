package com.talkback.core.media

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-[moduleId] gate: at most one lifecycle owner on the underlying [ModuleMediaEngineFactory] entry.
 * See media-engine-ownership-hangup-gate.md §3.
 */
class EngineOwnershipGate {
    enum class Owner {
        NONE,
        CONFERENCE_EDGE,
        GROUP_REUSE
    }

    enum class State {
        IDLE,
        RELEASING,
        RELEASED,
        FAILED
    }

    private data class Record(
        var owner: Owner,
        var state: State
    )

    private val records = ConcurrentHashMap<String, Record>()
    private val deferredGroupActions = ConcurrentHashMap<String, MutableList<() -> Unit>>()

    fun onConferenceEngineProvisioned(moduleId: String) {
        records[moduleId] = Record(Owner.CONFERENCE_EDGE, State.IDLE)
    }

    fun onGroupEngineProvisioned(moduleId: String) {
        records[moduleId] = Record(Owner.GROUP_REUSE, State.IDLE)
    }

    fun beginConferenceRelease(moduleId: String) {
        val record = records.getOrPut(moduleId) { Record(Owner.CONFERENCE_EDGE, State.IDLE) }
        record.owner = Owner.CONFERENCE_EDGE
        record.state = State.RELEASING
    }

    fun completeConferenceRelease(moduleId: String, success: Boolean) {
        val record = records[moduleId] ?: return
        if (success) {
            record.owner = Owner.NONE
            record.state = State.RELEASED
            flushDeferredGroupActions(moduleId)
        } else {
            record.owner = Owner.CONFERENCE_EDGE
            record.state = State.FAILED
            deferredGroupActions.remove(moduleId)
        }
    }

    fun mayGroupMutateEngine(moduleId: String, hasActiveConferenceEntry: Boolean): Boolean {
        val record = records[moduleId]
        if (record?.state == State.FAILED) return false
        if (record?.state == State.RELEASING) return false
        if (record?.owner == Owner.CONFERENCE_EDGE && record.state != State.RELEASED) return false
        if (hasActiveConferenceEntry && record?.state != State.RELEASED) return false
        return true
    }

    fun mayProvisionAfterRelease(moduleId: String): Boolean {
        val record = records[moduleId] ?: return true
        if (record.state == State.FAILED) return false
        if (record.state == State.RELEASED) return true
        if (record.owner == Owner.CONFERENCE_EDGE) return false
        return true
    }

    fun consumeReleaseBarrier(moduleId: String) {
        val record = records[moduleId] ?: return
        if (record.state == State.RELEASED) {
            record.state = State.IDLE
        }
    }

    fun deferGroupAction(moduleId: String, action: () -> Unit) {
        deferredGroupActions.computeIfAbsent(moduleId) { mutableListOf() }.add(action)
    }

    fun stateSnapshot(moduleId: String): Pair<Owner, State>? =
        records[moduleId]?.let { it.owner to it.state }

    private fun flushDeferredGroupActions(moduleId: String) {
        val actions = deferredGroupActions.remove(moduleId).orEmpty()
        actions.forEach { it() }
    }
}

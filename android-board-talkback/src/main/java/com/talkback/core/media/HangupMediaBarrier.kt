package com.talkback.core.media

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Waits for per-module conference release terminals before hangup channel / GROUP bootstrap.
 */
class HangupMediaBarrier(
    moduleIds: Collection<String>,
    private val onComplete: (allSucceeded: Boolean) -> Unit
) {
    private val pending = ConcurrentHashMap.newKeySet<String>().apply { addAll(moduleIds) }
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val completed = AtomicReference(false)

    fun onModuleTerminal(moduleId: String, success: Boolean) {
        if (!pending.remove(moduleId)) return
        if (!success) {
            failed.add(moduleId)
        }
        if (pending.isEmpty() && completed.compareAndSet(false, true)) {
            onComplete(failed.isEmpty())
        }
    }

    fun resolveImmediatelyAbsent(moduleId: String) {
        onModuleTerminal(moduleId, success = true)
    }
}

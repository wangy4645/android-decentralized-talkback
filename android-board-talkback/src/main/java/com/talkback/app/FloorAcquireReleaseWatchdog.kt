package com.talkback.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * ADR-0004 Phase 3: schedules auto [FLOOR_RELEASE] when local protocol floor owner
 * has not started capture within [timeoutMs] after GRANT_APPLIED (R12, R16).
 */
internal class FloorAcquireReleaseWatchdog(
    private val timeoutMs: () -> Long,
    private val scheduler: ScheduledExecutorService,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val onTimeout: (sessionId: String) -> Unit
) {
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val grantAtBySession = ConcurrentHashMap<String, Long>()

    fun onLocalGrantApplied(sessionId: String, alreadyCapturing: Boolean) {
        cancel(sessionId)
        if (alreadyCapturing) return
        grantAtBySession[sessionId] = nowMs()
        val delay = timeoutMs().coerceAtLeast(1L)
        val future = scheduler.schedule({
            if (grantAtBySession.remove(sessionId) == null) return@schedule
            pending.remove(sessionId)
            onTimeout(sessionId)
        }, delay, TimeUnit.MILLISECONDS)
        pending[sessionId] = future
    }

    fun onCaptureStarted(sessionId: String) = cancel(sessionId)

    fun onFloorLost(sessionId: String) = cancel(sessionId)

    fun cancelAll() {
        pending.keys.toList().forEach { cancel(it) }
    }

    fun cancel(sessionId: String) {
        pending.remove(sessionId)?.cancel(false)
        grantAtBySession.remove(sessionId)
    }

    internal fun grantAtMsForTest(sessionId: String): Long? = grantAtBySession[sessionId]
}

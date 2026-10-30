package com.talkback.core.media

/**
 * Schedules A0.7 release watchdog checks without blocking the coordinator thread.
 */
fun interface ReleaseWatchdogScheduler {
    fun schedule(delayMs: Long, action: () -> Unit)
}

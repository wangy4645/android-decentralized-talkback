package com.talkback.core.webrtc

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded SDP wait. Leave/hangup can abort without waiting for PeerConnection.
 * Timeout here is deadlock break only — not edge isolation.
 */
internal class PendingSdpWait(
    private val timeoutMs: Long = 3_000L,
    private val pollMs: Long = 50L,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private val aborted = AtomicBoolean(false)

    fun abort() {
        aborted.set(true)
    }

    fun begin() {
        aborted.set(false)
    }

    fun await(latch: CountDownLatch, timeoutMessage: String) {
        val deadlineNs = nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            check(!aborted.get()) { "Aborted pending SDP" }
            val remainingNs = deadlineNs - nanoTime()
            check(remainingNs > 0) { timeoutMessage }
            val waitMs = TimeUnit.NANOSECONDS.toMillis(remainingNs).coerceIn(1L, pollMs)
            if (latch.await(waitMs, TimeUnit.MILLISECONDS)) return
        }
    }
}

package com.talkback.core.media

import java.util.concurrent.atomic.AtomicReference

/**
 * A0.7.4 — one-shot terminal latch for conference release transactions.
 */
internal class ReleaseTransactionLatch {
    private enum class Terminal {
        RELEASED,
        FAILED
    }

    private val terminal = AtomicReference<Terminal?>(null)

    fun isRunning(): Boolean = terminal.get() == null

    fun isFailed(): Boolean = terminal.get() == Terminal.FAILED

    fun isReleased(): Boolean = terminal.get() == Terminal.RELEASED

    /** RUNNING → RELEASED; returns false if already FAILED. */
    fun markReleasedIfRunning(): Boolean = terminal.compareAndSet(null, Terminal.RELEASED)

    /** RUNNING → FAILED; returns false if already RELEASED. */
    fun markFailedIfRunning(): Boolean = terminal.compareAndSet(null, Terminal.FAILED)
}

object ReleaseWatchdogContracts {
    const val CAUSE_HUNG_RUNTIME = "RELEASE_PREEMPTED_HUNG_RUNTIME"
}

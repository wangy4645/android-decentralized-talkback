package com.talkback.core.conference.transport

import com.talkback.core.conference.runtime.MediaJitterConstants

/**
 * Absolute 20ms media playout scheduler (harness / Phase 1).
 *
 * - One tick per due slot; no catch-up burst when late.
 * - Not coupled to [receive] blocking.
 * - Does not alter Profile 03 jitter / deadline / PLC / Top-K.
 */
class AbsoluteMediaPlayoutClock(
    private val anchorMs: Long,
    private val slotMs: Long = MediaJitterConstants.MEDIA_SLOT_MS,
    private val threadName: String = "absolute-playout-clock",
    private val onTick: (tickMediaTimeMs: Long) -> Unit,
) {
    @Volatile
    private var running = false

    private var thread: Thread? = null

    /** Fired playout ticks (at most one per loop iteration). */
    @Volatile
    var ticksFired: Long = 0
        private set

    /** Slots skipped because the loop woke late (no burst catch-up). */
    @Volatile
    var ticksSkipped: Long = 0
        private set

    fun start() {
        if (running) return
        running = true
        var tickIndex = -1L
        thread =
            Thread(
                {
                    while (running) {
                        val now = System.currentTimeMillis()
                        val dueIndex = ((now - anchorMs) / slotMs).coerceAtLeast(0L)
                        if (dueIndex > tickIndex) {
                            val skipped = (dueIndex - tickIndex - 1).coerceAtLeast(0L)
                            if (skipped > 0) ticksSkipped += skipped
                            tickIndex = dueIndex
                            ticksFired += 1
                            onTick(anchorMs + tickIndex * slotMs)
                        }
                        val nextDueMs = anchorMs + (tickIndex + 1) * slotMs
                        val sleepMs =
                            (nextDueMs - System.currentTimeMillis()).coerceIn(0L, 5L)
                        if (sleepMs > 0L) {
                            try {
                                Thread.sleep(sleepMs)
                            } catch (_: InterruptedException) {
                                if (!running) break
                            }
                        }
                    }
                },
                threadName,
            ).apply {
                isDaemon = true
                start()
            }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        try {
            thread?.join(3_000L)
        } catch (_: InterruptedException) {
            // best-effort shutdown
        }
        thread = null
    }

    fun snapshot(): Map<String, Any?> =
        mapOf(
            "policy" to "ABSOLUTE_20MS_NO_BURST",
            "anchorMs" to anchorMs,
            "slotMs" to slotMs,
            "ticksFired" to ticksFired,
            "ticksSkipped" to ticksSkipped,
        )
}

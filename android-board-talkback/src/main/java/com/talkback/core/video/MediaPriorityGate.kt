package com.talkback.core.video

/**
 * App-level send gate: PTT voice > live video > bulk file.
 * RF mesh has no QoS; this only paces this process.
 */
class MediaPriorityGate(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val restoreHoldMs: Long = VideoMediaPolicy.PTT_RESTORE_HOLD_MS,
) {
    @Volatile
    var pttActive: Boolean = false
        private set

    @Volatile
    var localVideoPublishing: Boolean = false
        private set

    @Volatile
    private var pttReleasedAtMs: Long = 0L

    fun onPtt(active: Boolean) {
        if (active) {
            pttActive = true
            pttReleasedAtMs = 0L
        } else {
            pttActive = false
            pttReleasedAtMs = clockMs()
        }
    }

    fun onLocalVideoPublishing(active: Boolean) {
        localVideoPublishing = active
        if (!active) return
    }

    fun inRestoreHold(): Boolean {
        if (pttActive) return false
        val releasedAt = pttReleasedAtMs
        if (releasedAt <= 0L) return false
        return clockMs() - releasedAt < restoreHoldMs
    }

    fun videoSendEnabled(): Boolean {
        if (!localVideoPublishing) return false
        if (pttActive || inRestoreHold()) return false
        return true
    }

    fun bulkAllowed(): Boolean {
        if (pttActive || inRestoreHold()) return false
        if (localVideoPublishing) return false
        return true
    }

    fun videoMaxBitrateBps(): Int =
        if (videoSendEnabled()) VideoMediaPolicy.DEFAULT_MAX_BITRATE_BPS else 0
}

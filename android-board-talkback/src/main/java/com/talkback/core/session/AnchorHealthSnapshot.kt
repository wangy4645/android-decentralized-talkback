package com.talkback.core.session

/**
 * Per-module health learned from HELLO (and optional local provider).
 */
data class AnchorHealthSnapshot(
    val charging: Boolean = false,
    val batteryPercent: Int = 100,
    val onlineSinceMs: Long = 0L,
    val updatedMs: Long = 0L
) {
    companion object {
        fun fromLocal(local: LocalDeviceHealthSnapshot, nowMs: Long = System.currentTimeMillis()): AnchorHealthSnapshot =
            AnchorHealthSnapshot(
                charging = local.charging,
                batteryPercent = local.batteryPercent.coerceIn(0, 100),
                onlineSinceMs = local.onlineSinceMs,
                updatedMs = nowMs
            )

        /** Deterministic fallback when remote health is unknown. */
        fun unknown(nowMs: Long = System.currentTimeMillis()): AnchorHealthSnapshot =
            AnchorHealthSnapshot(
                charging = false,
                batteryPercent = 50,
                onlineSinceMs = 0L,
                updatedMs = nowMs
            )
    }
}

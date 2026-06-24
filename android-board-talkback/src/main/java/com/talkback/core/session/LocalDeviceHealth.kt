package com.talkback.core.session

/**
 * Local device health snapshot used for deterministic anchor ranking.
 */
data class LocalDeviceHealthSnapshot(
    val charging: Boolean = false,
    val batteryPercent: Int = 100,
    val onlineSinceMs: Long = System.currentTimeMillis()
)

fun interface LocalDeviceHealthProvider {
    fun snapshot(): LocalDeviceHealthSnapshot
}

object DefaultLocalDeviceHealthProvider : LocalDeviceHealthProvider {
    private val bootMs = System.currentTimeMillis()

    override fun snapshot(): LocalDeviceHealthSnapshot =
        LocalDeviceHealthSnapshot(
            charging = false,
            batteryPercent = 100,
            onlineSinceMs = bootMs
        )
}

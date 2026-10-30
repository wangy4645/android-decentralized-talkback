package com.talkback.core.conference.capacity

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Thermal observability preflight (C3 gate prerequisite).
 * UNKNOWN thermal state MUST NOT count as PASS for formal qualifying evidence.
 */
object ThermalPreflight {
    data class SysfsZone(
        val name: String,
        val type: String?,
        val tempMilliC: Long?,
        val readable: Boolean,
    )

    data class ApiSource(
        val name: String,
        val readable: Boolean,
        val detail: String,
    )

    data class Result(
        val observable: Boolean,
        val apiSources: List<ApiSource>,
        val sysfsZones: List<SysfsZone>,
        val blockerReason: String?,
    ) {
        val passesPreflight: Boolean
            get() = observable && blockerReason == null
    }

    fun run(context: Context): Result {
        val apiSources = probeAndroidApis(context)
        val sysfsZones = probeSysfsThermal()
        val apiReadable = apiSources.any { it.readable }
        val sysfsReadable = sysfsZones.any { it.readable && it.tempMilliC != null }
        val observable = apiReadable || sysfsReadable
        val blockerReason =
            when {
                !observable ->
                    "thermal not observable via Android API or /sys/class/thermal; formal qualifying evidence blocked"
                else -> null
            }
        return Result(
            observable = observable,
            apiSources = apiSources,
            sysfsZones = sysfsZones,
            blockerReason = blockerReason,
        )
    }

    private fun probeAndroidApis(context: Context): List<ApiSource> {
        val out = mutableListOf<ApiSource>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (powerManager != null) {
                val currentThermalStatus = powerManager.currentThermalStatus
                out.add(
                    ApiSource(
                        name = "PowerManager.currentThermalStatus",
                        readable = currentThermalStatus != android.os.PowerManager.THERMAL_STATUS_NONE ||
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                        detail = "status=$currentThermalStatus",
                    ),
                )
            } else {
                out.add(
                    ApiSource(
                        name = "PowerManager",
                        readable = false,
                        detail = "service unavailable",
                    ),
                )
            }
        } else {
            out.add(
                ApiSource(
                    name = "PowerManager.currentThermalStatus",
                    readable = false,
                    detail = "requires API 29+",
                ),
            )
        }
        return out
    }

    private fun probeSysfsThermal(): List<SysfsZone> {
        val root = File("/sys/class/thermal")
        if (!root.isDirectory) {
            return emptyList()
        }
        return root.listFiles()
            ?.filter { it.name.startsWith("thermal_zone") }
            ?.sortedBy { it.name }
            ?.map { zoneDir ->
                val typeFile = File(zoneDir, "type")
                val tempFile = File(zoneDir, "temp")
                val type = runCatching { typeFile.readText().trim() }.getOrNull()
                val tempRaw = runCatching { tempFile.readText().trim() }.getOrNull()
                val tempMilliC = tempRaw?.toLongOrNull()
                SysfsZone(
                    name = zoneDir.name,
                    type = type,
                    tempMilliC = tempMilliC,
                    readable = tempRaw != null,
                )
            }
            ?: emptyList()
    }
}

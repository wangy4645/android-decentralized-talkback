package com.talkback.core.conference.capacity

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager

/**
 * H1d.2 sched-tail context and post-run attribution hints (diagnostics only).
 */
enum class Gres3SchedTailAttributionHint {
    APP_RUNTIME_CONTENTION,
    ANDROID_SCHEDULER_JITTER,
    HARNESS_INSTRUMENTATION_OVERHEAD,
    THERMAL_THROTTLE,
    LIFECYCLE_NOT_FOREGROUND,
    SCREEN_POWER_STATE,
}

data class FanOutStallContext(
    val uptimeMs: Long,
    val threadId: Int,
    val threadName: String,
    val processThreadPriority: Int,
    val javaThreadPriority: Int,
    val javaHeapUsedBytes: Long,
    val nativeHeapBytes: Long,
    val gcCount: Long,
    val thermalStatus: Int?,
    val processImportance: Int?,
    val screenInteractive: Boolean?,
)

object FanOutStallContextCapture {
    fun capture(context: Context?): FanOutStallContext {
        val runtime = Runtime.getRuntime()
        val heapUsed = runtime.totalMemory() - runtime.freeMemory()
        val tid = android.os.Process.myTid()
        val importance = context?.let { resolveProcessImportance(it) }
        val thermal = context?.let { resolveThermalStatus(it) }
        val screenOn = context?.let { resolveScreenInteractive(it) }
        return FanOutStallContext(
            uptimeMs = android.os.SystemClock.uptimeMillis(),
            threadId = tid,
            threadName = Thread.currentThread().name,
            processThreadPriority = android.os.Process.getThreadPriority(tid),
            javaThreadPriority = Thread.currentThread().priority,
            javaHeapUsedBytes = heapUsed,
            nativeHeapBytes = android.os.Debug.getNativeHeapAllocatedSize(),
            gcCount = readGcCount(),
            thermalStatus = thermal,
            processImportance = importance,
            screenInteractive = screenOn,
        )
    }

    private fun resolveProcessImportance(context: Context): Int? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val pid = android.os.Process.myPid()
        return am.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.importance
    }

    private fun resolveThermalStatus(context: Context): Int? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return try {
            pm.currentThermalStatus
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveScreenInteractive(context: Context): Boolean? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return try {
            pm.isInteractive
        } catch (_: Exception) {
            null
        }
    }

    private fun readGcCount(): Long {
        return try {
            android.os.Debug.getRuntimeStat("art.gc.gc-count").toLongOrNull() ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }
}

object Gres3SchedTailAttributor {
    private const val HEAP_PRESSURE_BYTES: Long = 48L * 1024L * 1024L
    private const val THERMAL_MODERATE: Int = PowerManager.THERMAL_STATUS_MODERATE

    fun hintsFor(
        event: FanOutTailEvent,
        harnessInstrumentationActive: Boolean,
    ): List<Gres3SchedTailAttributionHint> {
        if (!event.schedDominant) {
            return emptyList()
        }
        val ctx = event.stallContext
        val hints = LinkedHashSet<Gres3SchedTailAttributionHint>()
        if (harnessInstrumentationActive) {
            hints += Gres3SchedTailAttributionHint.HARNESS_INSTRUMENTATION_OVERHEAD
        }
        if (ctx.javaHeapUsedBytes >= HEAP_PRESSURE_BYTES || ctx.gcCount > 0L) {
            hints += Gres3SchedTailAttributionHint.APP_RUNTIME_CONTENTION
        }
        val thermal = ctx.thermalStatus
        if (thermal != null && thermal >= THERMAL_MODERATE) {
            hints += Gres3SchedTailAttributionHint.THERMAL_THROTTLE
        }
        val importance = ctx.processImportance
        if (importance != null && importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
            hints += Gres3SchedTailAttributionHint.LIFECYCLE_NOT_FOREGROUND
        }
        if (ctx.screenInteractive == false) {
            hints += Gres3SchedTailAttributionHint.SCREEN_POWER_STATE
        }
        if (hints.isEmpty()) {
            hints += Gres3SchedTailAttributionHint.ANDROID_SCHEDULER_JITTER
        }
        return hints.toList()
    }
}

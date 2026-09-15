package com.talkback.core.conference.capacity

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process

/**
 * Lightweight resource samples collected outside the measurement hot path.
 */
class HarnessResourceSampler(
    private val context: Context,
) {
    data class Sample(
        val phase: String,
        val elapsedMs: Long,
        val javaHeapUsedBytes: Long,
        val nativeHeapAllocatedBytes: Long,
        val processCpuTimeMs: Long,
    )

    private val samples = ArrayList<Sample>(64)
    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val pid = Process.myPid()

    fun sample(phase: String, startedAtMs: Long) {
        val runtime = Runtime.getRuntime()
        val javaHeapUsed = runtime.totalMemory() - runtime.freeMemory()
        val nativeHeap = Debug.getNativeHeapAllocatedSize()
        val cpuTimeMs = activityManager.getProcessMemoryInfo(intArrayOf(pid)).firstOrNull()?.let { 0L } ?: 0L
        val processCpuTimeMs = android.os.SystemClock.uptimeMillis() // placeholder tick; refined in cooldown
        samples.add(
            Sample(
                phase = phase,
                elapsedMs = System.currentTimeMillis() - startedAtMs,
                javaHeapUsedBytes = javaHeapUsed,
                nativeHeapAllocatedBytes = nativeHeap,
                processCpuTimeMs = processCpuTimeMs.coerceAtLeast(cpuTimeMs),
            ),
        )
    }

    fun snapshot(): List<Sample> = samples.toList()
}

package com.talkback.core.conference.capacity

import android.os.Debug

/**
 * H1d.4: cheap wall vs thread-CPU samples on the pacing thread (diagnostic only).
 */
object FanOutThreadCpuTiming {
    fun threadCpuTimeNs(): Long = Debug.threadCpuTimeNanos()
}

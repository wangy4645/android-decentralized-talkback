package com.talkback.core.conference.capacity

import android.os.Process
import android.util.Log

/**
 * Applies production-reproducible pacing-thread scheduling and records what actually took effect.
 * Uses [Process.setThreadPriority] (Linux nice); does not rely on Java [Thread.setPriority].
 */
object Gres3PacingThreadBootstrap {
    data class Profile(
        val threadId: Int,
        val threadName: String,
        val configuredProcessPriority: Int,
        val observedProcessPriority: Int,
        val observedJavaPriority: Int,
    ) {
        val priorityApplied: Boolean
            get() = observedProcessPriority == configuredProcessPriority
    }

    fun applyAndVerify(configuredProcessPriority: Int): Profile {
        Process.setThreadPriority(configuredProcessPriority)
        val tid = Process.myTid()
        val observed = Process.getThreadPriority(tid)
        val profile =
            Profile(
                threadId = tid,
                threadName = Thread.currentThread().name,
                configuredProcessPriority = configuredProcessPriority,
                observedProcessPriority = observed,
                observedJavaPriority = Thread.currentThread().priority,
            )
        if (!profile.priorityApplied) {
            Log.w(
                Gres3CapacityHarnessConstants.LOG_TAG,
                "PACING_PRIORITY_MISMATCH configured=$configuredProcessPriority observed=$observed tid=$tid",
            )
        } else {
            Log.i(
                Gres3CapacityHarnessConstants.LOG_TAG,
                "PACING_PRIORITY_APPLIED tid=$tid processPriority=$observed javaPriority=${profile.observedJavaPriority}",
            )
        }
        return profile
    }
}

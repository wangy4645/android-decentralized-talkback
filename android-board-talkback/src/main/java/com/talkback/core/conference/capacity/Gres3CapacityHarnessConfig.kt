package com.talkback.core.conference.capacity

import android.os.Process

/**
 * Frozen G-RES-3-H1 harness configuration.
 */
data class Gres3CapacityHarnessConfig(
    val runId: String,
    val runClass: Gres3RunClass,
    val deviceLabel: String,
    val appBuildSha: String,
    val warmupSec: Int,
    val measurementSec: Int,
    val cooldownSec: Int,
    val endpoints: List<PerReceiverUnicastEndpoint>,
    val artifactRingSize: Int = ProtectedArtifactRing.DEFAULT_RING_SIZE,
    val pacingThreadPriority: Int = Gres3CapacityHarnessConstants.DEFAULT_PACING_THREAD_PRIORITY,
    val senderBindPort: Int = 0,
    val executionModel: Gres3SenderExecutionModel = Gres3SenderExecutionModel.SHARED_SOCKET_SEQUENTIAL,
    val harnessPhase: Gres3HarnessPhase = Gres3HarnessPhase.H1a,
    val formalTopology: Gres3FormalTopology? = null,
    val localSinksEnabled: Boolean = true,
    val sinkBindingsVerified: Boolean = false,
) {
    init {
        require(warmupSec >= 0) { "warmupSec must be >= 0" }
        require(measurementSec > 0) { "measurementSec must be > 0" }
        require(cooldownSec >= 0) { "cooldownSec must be >= 0" }
        require(endpoints.size == Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT) {
            "G-RES-3 requires exactly ${Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT} distinct legs"
        }
        val keys = endpoints.map { it.endpointKey() }.toSet()
        require(keys.size == endpoints.size) { "endpoints must be distinct" }
    }

    val benchmarkConfigHash: String
        get() = Gres3BenchmarkConfigHash.compute(this)

    /** H1d-only detailed tail capture; H1c/qualifying keep C2/C3 lightweight metrics only. */
    val tailLatencyDiagnosticsEnabled: Boolean
        get() = harnessPhase == Gres3HarnessPhase.H1d

    /** Per-leg send timing on hot path (H1d diagnostics only). */
    val perLegSendTimingEnabled: Boolean
        get() = harnessPhase == Gres3HarnessPhase.H1d
}

object Gres3CapacityHarnessConstants {
    const val LOG_TAG: String = "GRES3_CAPACITY_H1"
    const val REQUIRED_LEG_COUNT: Int = 9
    /** Linux nice via [Process.setThreadPriority]; production-reproducible, not RT-extreme. */
    const val DEFAULT_PACING_THREAD_PRIORITY: Int = Process.THREAD_PRIORITY_URGENT_DISPLAY
    const val QUALIFICATION_WARMUP_SEC: Int = 5
    const val QUALIFICATION_MEASUREMENT_SEC: Int = 30
    const val QUALIFICATION_COOLDOWN_SEC: Int = 5
    const val H1C_WARMUP_SEC: Int = 60
    const val H1C_MEASUREMENT_SEC: Int = 90
    const val H1C_COOLDOWN_SEC: Int = 30
    const val QUALIFYING_WARMUP_SEC: Int = 60
    const val QUALIFYING_MEASUREMENT_SEC: Int = 600
    const val QUALIFYING_COOLDOWN_SEC: Int = 30
    /**
     * Instrumented test await: full lifecycle + teardown margin.
     * 60+600+30 = 690s core; + evidence flush + OEM scheduling slack → 1200s.
     */
    const val QUALIFYING_INSTRUMENTATION_TIMEOUT_SEC: Long = 1_200L
    const val H1C_INSTRUMENTATION_TIMEOUT_SEC: Long =
        (H1C_WARMUP_SEC + H1C_MEASUREMENT_SEC + H1C_COOLDOWN_SEC + 120).toLong()
}

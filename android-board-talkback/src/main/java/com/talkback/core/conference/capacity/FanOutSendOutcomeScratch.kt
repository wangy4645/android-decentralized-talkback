package com.talkback.core.conference.capacity

/**
 * Preallocated per-slot fan-out outcome (A8 semantics, no per-send allocation).
 */
class FanOutSendOutcomeScratch {
    var legsAttempted: Int = 0
    var legsSucceeded: Int = 0
    var legsFailed: Int = 0
    var legFailedMask: Long = 0L
    var maxLegIndex: Int = -1
    var maxLegDurationNs: Long = 0L
    /** H1d.4 diagnostic: wall vs pacing-thread CPU inside send loop. */
    var fanoutWallNs: Long = 0L
    var fanoutThreadCpuNs: Long = 0L
    /** E3 probe: native sendmmsg batch observability. */
    var sendmmsgRequestedMessages: Int = 0
    var sendmmsgReturnedMessages: Int = 0
    var sendmmsgSyscallWallNs: Long = 0L
    var sendmmsgErrno: Int = 0
    var sendmmsgEagain: Boolean = false

    val fanoutNonCpuNs: Long
        get() = (fanoutWallNs - fanoutThreadCpuNs).coerceAtLeast(0L)

    val allLegsSucceeded: Boolean
        get() = legsFailed == 0 && legsAttempted > 0
}

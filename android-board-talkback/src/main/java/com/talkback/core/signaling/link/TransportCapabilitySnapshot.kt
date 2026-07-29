package com.talkback.core.signaling.link

data class TransportCapabilitySnapshot(
    val linkQualification: LinkQualificationState = LinkQualificationState.UNKNOWN,
    val socketId: Long = 0L,
    val rebindGeneration: Long = 0L,
    val networkId: String = "none",
    val hasOutboundAfterRebind: Boolean = false,
    val hasInboundAfterRebind: Boolean = false,
    val qualificationRetryRequested: Boolean = false,
    val repairAttempt: Int = 0,
    val transportRepairState: TransportRepairState = TransportRepairState.IDLE,
    val repairStable: Boolean = false
)

package com.talkback.governance.capability

enum class CapabilityReadiness {
    READY,
    RECONCILING,
    NOT_READY,
    FAILED
}

fun CapabilityReadiness.blocksAdmission(): Boolean = this != CapabilityReadiness.READY

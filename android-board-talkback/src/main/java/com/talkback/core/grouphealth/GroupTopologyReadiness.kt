package com.talkback.core.grouphealth

/**
 * Internal Group PTT convergence label (ADR-0008). Transmit policy is the sole readiness authority.
 */
enum class GroupTopologyReadiness {
    DISCOVERING,
    MEMBERSHIP_PENDING,
    BUILDING,
    OPERATIONAL
}

package com.talkback.core.signaling.link

/**
 * ADR-0022 Q2 / INV-SIG-001: sole writer for signaling rebind generation.
 * Implemented by [LinkQualificationTracker].
 */
interface SignalingGenerationAuthority {
    fun currentRebindGeneration(): Long
    fun advanceRebindGeneration(): Long
}
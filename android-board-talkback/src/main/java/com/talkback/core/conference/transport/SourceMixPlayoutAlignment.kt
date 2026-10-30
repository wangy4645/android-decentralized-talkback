package com.talkback.core.conference.transport

/**
 * ADR-0058 F9.2 — Late-Join Source-to-Mix Playout Alignment.
 *
 * Fixed incarnation-scoped transform between shared session playout slot domain (M)
 * and source-incarnation slot domain (S). Established once at first QUEUED admit.
 */
data class SourceMixPlayoutAlignment(
    val mixReferenceSlot: Long,
    val sourceReferenceSlot: Long,
) {
    val offset: Long = mixReferenceSlot - sourceReferenceSlot

    fun sourceSlotForMix(sharedMixPlayoutSlot: Long): Long = sharedMixPlayoutSlot - offset

    /** F9.3 — inverse of [sourceSlotForMix]; cross-source compare only in M domain. */
    fun mixSlotForSource(sourceSlot: Long): Long = sourceSlot + offset
}

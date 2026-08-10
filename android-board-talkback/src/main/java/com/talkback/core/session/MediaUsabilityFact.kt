package com.talkback.core.session

/**
 * Media-axis facts for UVCP / diagnostics (ADR-0034 / INV-PRES-006; RCA-003 R5).
 *
 * Pure fact composition: does not own ICE, recovery obligation, or projection mapping.
 * [MediaState.RECONNECTING] / [MediaState.FAILED] encode ICE_DISCONNECTED / ICE_FAILED
 * (see MediaRuntime.mediaStateFromIce).
 *
 * **R5 / IC:** UVCP must use [currentUnavailable] only. Failed-media residency is an
 * incident residue — not current path unavailability ([FAILED_MEDIA] ≠ [CURRENT_UNAVAILABLE]).
 */
object MediaUsabilityFact {

    /**
     * Current path unusable for the UVCP media axis (live [MediaState] only).
     *
     * Must be true for ICE_DISCONNECTED / ICE_FAILED equivalents so sticky
     * receivePathLive cannot fake MEDIA_OK -> SYNCING while the peer is down.
     */
    fun currentUnavailable(mediaState: MediaState): Boolean =
        mediaState == MediaState.RECONNECTING || mediaState == MediaState.FAILED

    /**
     * Diagnostic OR of incident residency + current media unavailability.
     *
     * **Not** the UVCP media-axis input after RCA-003 IC (use [currentUnavailable]).
     * Retained for tests / tooling that still need the composed diagnostic bit.
     */
    fun isUnavailable(
        mediaState: MediaState,
        failedMediaResidency: Boolean
    ): Boolean = failedMediaResidency || currentUnavailable(mediaState)
}

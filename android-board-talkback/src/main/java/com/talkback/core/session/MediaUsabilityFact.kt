package com.talkback.core.session

/**
 * Media-axis input for UVCP (ADR-0034 / INV-PRES-006).
 *
 * Pure fact composition: does not own ICE, recovery obligation, or projection mapping.
 * MediaState.RECONNECTING / MediaState.FAILED already encode ICE_DISCONNECTED / ICE_FAILED
 * (see MediaRuntime.mediaStateFromIce); failed-media residency remains the ADR-0030 residency bit.
 */
object MediaUsabilityFact {

    /**
     * True when the media path is not usable for the UVCP media axis.
     *
     * Must be true for ICE_DISCONNECTED / ICE_FAILED equivalents so sticky
     * receivePathLive cannot fake MEDIA_OK -> SYNCING while the peer is down.
     */
    fun isUnavailable(
        mediaState: MediaState,
        failedMediaResidency: Boolean
    ): Boolean =
        failedMediaResidency ||
            mediaState == MediaState.RECONNECTING ||
            mediaState == MediaState.FAILED
}
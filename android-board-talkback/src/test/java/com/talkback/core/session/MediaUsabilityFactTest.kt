package com.talkback.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * INV-PRES-006 / RCA-003 IC: UVCP media axis uses [MediaUsabilityFact.currentUnavailable]
 * (live MediaState only). [isUnavailable] remains diagnostic OR with residency.
 */
class MediaUsabilityFactTest {

    @Test
    fun currentUnavailable_iceDisconnected_orFailed() {
        assertTrue(MediaUsabilityFact.currentUnavailable(MediaState.RECONNECTING))
        assertTrue(MediaUsabilityFact.currentUnavailable(MediaState.FAILED))
    }

    @Test
    fun currentUnavailable_connected_isFalse() {
        assertFalse(MediaUsabilityFact.currentUnavailable(MediaState.CONNECTED))
        assertFalse(MediaUsabilityFact.currentUnavailable(MediaState.CONNECTING))
        assertFalse(MediaUsabilityFact.currentUnavailable(MediaState.NONE))
    }

    @Test
    fun ic_uvcp_residencyAlone_doesNotMakeCurrentUnavailable() {
        // R5.3 / IC: FAILED_MEDIA residency ≠ CURRENT_UNAVAILABLE for UVCP input.
        assertFalse(MediaUsabilityFact.currentUnavailable(MediaState.CONNECTED))
        // Diagnostic OR still sees residency (not UVCP path).
        assertTrue(
            MediaUsabilityFact.isUnavailable(
                mediaState = MediaState.CONNECTED,
                failedMediaResidency = true
            )
        )
    }

    @Test
    fun case1_iceDisconnectedMediaState_isUnavailable_withoutFailedResidency() {
        assertTrue(
            MediaUsabilityFact.isUnavailable(
                mediaState = MediaState.RECONNECTING,
                failedMediaResidency = false
            )
        )
        assertTrue(MediaUsabilityFact.currentUnavailable(MediaState.RECONNECTING))
    }

    @Test
    fun iceFailedMediaState_isUnavailable_withoutFailedResidency() {
        assertTrue(
            MediaUsabilityFact.isUnavailable(
                mediaState = MediaState.FAILED,
                failedMediaResidency = false
            )
        )
    }

    @Test
    fun case2_connectedMedia_usable_evenIfResidencyFalse() {
        assertFalse(
            MediaUsabilityFact.isUnavailable(
                mediaState = MediaState.CONNECTED,
                failedMediaResidency = false
            )
        )
    }

    @Test
    fun connectingOrNone_notUnavailable_byMediaStateAlone() {
        assertFalse(
            MediaUsabilityFact.isUnavailable(MediaState.CONNECTING, failedMediaResidency = false)
        )
        assertFalse(
            MediaUsabilityFact.isUnavailable(MediaState.NONE, failedMediaResidency = false)
        )
    }
}

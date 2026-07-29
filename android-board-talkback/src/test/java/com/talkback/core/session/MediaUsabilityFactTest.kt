package com.talkback.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * INV-PRES-006 media-axis input: ICE disconnect/fail must yield unavailable,
 * even when failed-media residency has not yet latched.
 */
class MediaUsabilityFactTest {

    @Test
    fun case1_iceDisconnectedMediaState_isUnavailable_withoutFailedResidency() {
        // Field: ice=DISCONNECTED -> MediaState.RECONNECTING, mediaUnavailable residency=false
        assertTrue(
            MediaUsabilityFact.isUnavailable(
                mediaState = MediaState.RECONNECTING,
                failedMediaResidency = false
            )
        )
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
    fun case3_connectedStable_usable() {
        assertFalse(
            MediaUsabilityFact.isUnavailable(
                mediaState = MediaState.CONNECTED,
                failedMediaResidency = false
            )
        )
    }

    @Test
    fun failedResidency_alone_isUnavailable_evenIfMediaConnected() {
        // ADR-0030 residency still forces unavailable (e.g. FAILED_MEDIA_RECOVERY window).
        assertTrue(
            MediaUsabilityFact.isUnavailable(
                mediaState = MediaState.CONNECTED,
                failedMediaResidency = true
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
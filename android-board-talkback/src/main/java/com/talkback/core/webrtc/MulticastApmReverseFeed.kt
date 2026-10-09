package com.talkback.core.webrtc

import com.talkback.core.conference.runtime.OpusCodecConstants

/**
 * Profile01 multicast audible playout → WebRTC APM reverse reference.
 * Field-product desk build: no WebRTC JNI — inject is a no-op; PCM capture hooks remain for JVM tests.
 */
object MulticastApmReverseFeed {
    /** Desk-only capture — not field telemetry. */
    @Volatile
    internal var deskTestLastSubmittedPcm: ShortArray? = null

    @Volatile
    var enabled: Boolean = false

    fun setSessionActive(active: Boolean) {
        enabled = active
    }

    fun clearDeskTestCapture() {
        deskTestLastSubmittedPcm = null
    }

    fun submitPlayoutPcm(samples: ShortArray): Boolean {
        deskTestLastSubmittedPcm = samples.copyOf()
        if (!enabled || samples.isEmpty()) return false
        if (samples.size != OpusCodecConstants.FRAME_SAMPLES_20MS) return false
        return true
    }
}

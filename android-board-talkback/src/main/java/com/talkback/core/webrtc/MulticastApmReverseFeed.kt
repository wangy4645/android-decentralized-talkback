package com.talkback.core.webrtc

import com.talkback.core.conference.runtime.OpusCodecConstants
import org.webrtc.TalkbackApmReverseFeed

/**
 * Profile01 multicast audible playout → WebRTC APM reverse reference.
 * Must use the same PCM buffer (and order: reverse before [AndroidAudioTrack.write]).
 */
object MulticastApmReverseFeed {
    /** Desk-only capture — not field telemetry. */
    @Volatile
    internal var deskTestLastSubmittedPcm: ShortArray? = null

    @Volatile
    var enabled: Boolean = false

    fun setSessionActive(active: Boolean) {
        enabled = active
        TalkbackApmReverseFeed.setEnabled(active)
    }

    fun clearDeskTestCapture() {
        deskTestLastSubmittedPcm = null
    }

    fun submitPlayoutPcm(samples: ShortArray): Boolean {
        deskTestLastSubmittedPcm = samples.copyOf()
        if (!enabled || samples.isEmpty()) return false
        if (samples.size != OpusCodecConstants.FRAME_SAMPLES_20MS) return false
        return TalkbackApmReverseFeed.injectPcm16(
            samples,
            OpusCodecConstants.SAMPLE_RATE_HZ,
            1,
        )
    }
}
